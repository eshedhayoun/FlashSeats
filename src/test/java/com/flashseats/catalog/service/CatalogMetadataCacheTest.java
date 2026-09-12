package com.flashseats.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogMetadataCacheTest {

    private final EventRepository events = mock(EventRepository.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final StockCounterRepository stock = mock(StockCounterRepository.class);
    private final StockEpoch epoch = mock(StockEpoch.class);
    private final CatalogProperties properties = new CatalogProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    private final CatalogService catalog =
            new CatalogService(events, tiers, stock, epoch, properties, clock);

    @Test
    void cachesEventMetadataForRepeatedWindowChecks() {
        Event event = event();
        when(events.findById(1L)).thenReturn(Optional.of(event));

        assertThat(catalog.getWindowStatus(1L)).isEqualTo(EventWindowStatus.OPEN);
        assertThat(catalog.getWindowStatus(1L)).isEqualTo(EventWindowStatus.OPEN);

        verify(events, times(1)).findById(1L);
    }

    @Test
    void cachesTierMetadataButReadsLiveCountersEachTime() {
        TicketTier tier = tier();
        when(tiers.findByEventIdOrderByPriceCentsDesc(1L)).thenReturn(List.of(tier));
        when(stock.readAll(1L, List.of(10L))).thenReturn(Map.of(10L, 4), Map.of(10L, 3));

        assertThat(catalog.getRemainingForEvent(1L)).isEqualTo(4);
        assertThat(catalog.getRemainingForEvent(1L)).isEqualTo(3);

        verify(tiers, times(1)).findByEventIdOrderByPriceCentsDesc(1L);
        verify(stock, times(2)).readAll(1L, List.of(10L));
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
}
