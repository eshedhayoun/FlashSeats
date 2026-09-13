package com.flashseats.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rules that make a cache in front of {@code events} safe (ADR-051).
 *
 * <p>Every one of these is a defect that shipped before it was a test: an entry that never expired
 * made an operator's pause a no-op on every other replica, a remembered miss outlived the seed
 * insert that created the row, and a cached tier list reached pre-warm.
 */
class CatalogMetadataCacheTest {

    private final EventRepository events = mock(EventRepository.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final CatalogProperties properties = new CatalogProperties();
    private final TestClock clock = new TestClock(Instant.parse("2026-09-12T10:00:00Z"));

    private final CatalogMetadata metadata =
            new CatalogMetadata(events, tiers, properties, clock, new SimpleMeterRegistry());

    @Test
    void servesRepeatedReadsWithoutTouchingTheDatabase() {
        when(events.findById(1L)).thenReturn(Optional.of(event()));

        assertThat(metadata.event(1L).title()).isEqualTo("Cache Fest");
        assertThat(metadata.event(1L).title()).isEqualTo("Cache Fest");

        verify(events, times(1)).findById(1L);
    }

    /**
     * The TTL <em>is</em> the cross-replica invalidation. Eviction reaches only the replica that
     * served the operator's call; without an expiry the other two would answer {@code OPEN} for the
     * life of the process, and pause gates queue join, holds and checkout.
     */
    @Test
    void stopsServingAnEntryOnceItsTtlHasPassed() {
        Event live = event();
        when(events.findById(1L)).thenReturn(Optional.of(live));

        assertThat(metadata.event(1L).status()).isEqualTo(EventStatus.PUBLISHED);

        live.setStatus(EventStatus.PAUSED);
        clock.advance(Duration.ofMillis(properties.getMetadataEventTtlMs() + 1));

        assertThat(metadata.event(1L).status()).isEqualTo(EventStatus.PAUSED);
        verify(events, times(2)).findById(1L);
    }

    /** Rows are inserted straight into PostgreSQL by the seed scripts, so a miss must not stick. */
    @Test
    void doesNotRememberThatAnEventWasMissing() {
        when(events.findById(9001L)).thenReturn(Optional.empty(), Optional.of(event()));

        assertThatThrownBy(() -> metadata.event(9001L))
                .isInstanceOfSatisfying(
                        FlashSeatsException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.EVENT_NOT_FOUND));
        assertThat(metadata.event(9001L)).isNotNull();

        verify(events, times(2)).findById(9001L);
    }

    @Test
    void evictsOnCommittedMetadataChange() {
        when(events.findById(1L)).thenReturn(Optional.of(event()));

        metadata.event(1L);
        metadata.onEventMetadataChanged(new EventMetadataChanged(1L));
        metadata.event(1L);

        verify(events, times(2)).findById(1L);
    }

    /**
     * Pre-warm and a rebuild write inventory counters derived from this list. A list that is merely
     * probably right produces a counter that is definitely wrong: a tier missing from it is left
     * with no counter, and a tier with no counter answers {@code 503} for the rest of the sale.
     */
    @Test
    void readsTiersFromTheDatabaseForRecoveryPaths() {
        when(tiers.findByEventIdOrderByPriceCentsDesc(1L)).thenReturn(List.of(tier()));

        metadata.tiers(1L);
        metadata.tiersUncached(1L);

        verify(tiers, times(2)).findByEventIdOrderByPriceCentsDesc(1L);
    }

    @Test
    void bypassesEverythingWhenDisabled() {
        properties.setMetadataCacheEnabled(false);
        when(events.findById(1L)).thenReturn(Optional.of(event()));

        metadata.event(1L);
        metadata.event(1L);

        verify(events, times(2)).findById(1L);
    }

    @Test
    void cachesTiersForFarLongerThanEvents() {
        when(tiers.findByEventIdOrderByPriceCentsDesc(1L)).thenReturn(List.of(tier()));

        metadata.tiers(1L);
        clock.advance(Duration.ofMillis(properties.getMetadataEventTtlMs() * 2));
        metadata.tiers(1L);

        verify(tiers, times(1)).findByEventIdOrderByPriceCentsDesc(1L);
        verifyNoMoreInteractions(events);
    }

    private Event event() {
        Event event = new Event();
        event.setId(1L);
        event.setTitle("Cache Fest");
        event.setDescription("Metadata only");
        event.setVenueName("Test Arena");
        event.setEventStartTime(Instant.parse("2026-09-13T10:00:00Z"));
        event.setSaleStartTime(Instant.parse("2026-09-12T09:00:00Z"));
        event.setSaleEndTime(Instant.parse("2026-09-12T11:00:00Z"));
        event.setStatus(EventStatus.PUBLISHED);
        return event;
    }

    private TicketTier tier() {
        TicketTier tier = new TicketTier();
        tier.setId(10L);
        tier.setEventId(1L);
        tier.setTierName("GA");
        tier.setPriceCents(2_500);
        tier.setCurrency("USD");
        tier.setTotalCapacity(10);
        tier.setMaxPerOrder(6);
        return tier;
    }

    /** A clock that can be moved, because every rule above is about expiry. */
    private static final class TestClock extends Clock {

        private Instant now;

        private TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
