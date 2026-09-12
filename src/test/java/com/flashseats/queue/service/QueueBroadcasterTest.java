package com.flashseats.queue.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.facade.QueuePhase;
import com.flashseats.queue.facade.QueueState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@DisplayName("QueueBroadcaster")
class QueueBroadcasterTest {

    @Test
    @DisplayName("The exhausted marker is read once per event sweep")
    void exhaustedMarkerIsReadOncePerEventSweep() {
        SseEmitterRegistry emitters = mock(SseEmitterRegistry.class);
        QueueService queue = mock(QueueService.class);
        QueueDrainRateTracker drainRate = mock(QueueDrainRateTracker.class);
        CatalogFacade catalog = mock(CatalogFacade.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-12T16:12:00Z"), ZoneOffset.UTC);

        when(emitters.watchedEventIds()).thenReturn(Set.of(1L));
        when(emitters.sessionsWatching(1L)).thenReturn(Set.of("a", "b", "c"));
        when(catalog.getWindowStatus(1L)).thenReturn(EventWindowStatus.OPEN);
        when(catalog.getTierAvailability(1L)).thenReturn(List.of());
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.zCard(QueueKeys.waiting(1L))).thenReturn(3L);
        when(queue.isExhausted(1L)).thenReturn(true);
        when(queue.getQueueState("a", 1L, EventWindowStatus.OPEN, true))
                .thenReturn(new QueueState(QueuePhase.EXHAUSTED, null, null, null, null));
        when(queue.getQueueState("b", 1L, EventWindowStatus.OPEN, true))
                .thenReturn(new QueueState(QueuePhase.EXHAUSTED, null, null, null, null));
        when(queue.getQueueState("c", 1L, EventWindowStatus.OPEN, true))
                .thenReturn(new QueueState(QueuePhase.EXHAUSTED, null, null, null, null));

        new QueueBroadcaster(emitters, queue, drainRate, catalog, redis, clock).pushPositions();

        verify(queue).isExhausted(1L);
        verify(queue).getQueueState("a", 1L, EventWindowStatus.OPEN, true);
        verify(queue).getQueueState("b", 1L, EventWindowStatus.OPEN, true);
        verify(queue).getQueueState("c", 1L, EventWindowStatus.OPEN, true);
        verify(emitters).send("a", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
        verify(emitters).send("b", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
        verify(emitters).send("c", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
    }
}
