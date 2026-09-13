package com.flashseats.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * "Nothing known" is never "nothing left" — the distinction this whole design spends its effort on
 * (ADR-004, ADR-035, ADR-040), checked at the one method every admission decision reads.
 */
class RemainingForEventTest {

    private final EventRepository events = mock(EventRepository.class);
    private final StockCounterRepository stock = mock(StockCounterRepository.class);
    private final StockEpoch epoch = mock(StockEpoch.class);
    private final CatalogMetadata metadata = mock(CatalogMetadata.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final CatalogService catalog = new CatalogService(
            events,
            stock,
            epoch,
            metadata,
            publisher,
            new CatalogProperties(),
            Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));

    /**
     * The regression. An event exists before its tiers do — in every seeder, every fixture, and any
     * future create-event endpoint — and summing an empty tier list answered {@code 0}. The promotion
     * worker reads 0 as sold out and marks the event exhausted; the marker clears only when
     * {@code remaining > 0}, which a tier-less event never reports, so the waiting room was told the
     * sale had ended <strong>permanently</strong>, on the strength of a sum over nothing.
     */
    @Test
    void anEventWithNoTiersIsUnreadableRatherThanSoldOut() {
        when(metadata.tiers(1L)).thenReturn(List.of());
        when(stock.readAll(anyLong(), any())).thenReturn(Map.of());

        assertThat(catalog.getRemainingForEvent(1L)).isEqualTo(CatalogFacade.COUNTER_UNAVAILABLE);
    }

    @Test
    void aTierWithNoCounterIsUnreadable() {
        when(metadata.tiers(1L)).thenReturn(List.of(tier(10L), tier(11L)));
        when(stock.readAll(1L, List.of(10L, 11L))).thenReturn(Map.of(10L, 5));

        assertThat(catalog.getRemainingForEvent(1L)).isEqualTo(CatalogFacade.COUNTER_UNAVAILABLE);
    }

    /** A genuinely drained tier is zero, and must stay distinguishable from the two above. */
    @Test
    void drainedCountersSumToZero() {
        when(metadata.tiers(1L)).thenReturn(List.of(tier(10L), tier(11L)));
        when(stock.readAll(1L, List.of(10L, 11L))).thenReturn(Map.of(10L, 0, 11L, 0));

        assertThat(catalog.getRemainingForEvent(1L)).isZero();
    }

    @Test
    void liveCountersSum() {
        when(metadata.tiers(1L)).thenReturn(List.of(tier(10L), tier(11L)));
        when(stock.readAll(1L, List.of(10L, 11L))).thenReturn(Map.of(10L, 40, 11L, 2));

        assertThat(catalog.getRemainingForEvent(1L)).isEqualTo(42);
    }

    private static TierRow tier(long id) {
        return new TierRow(id, 1L, "GA", 2_500, "USD", 500, 6);
    }
}
