package com.flashseats.queue.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.config.QueueProperties;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

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
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(QueueKeys.replaySequence(1L))).thenReturn(1L);
        when(zsets.add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
        QueueReplayService replay = new QueueReplayService(redis, new ObjectMapper(), new QueueProperties());

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

        new QueueBroadcaster(emitters, queue, drainRate, catalog, redis, clock, replay).pushPositions();

        verify(queue).isExhausted(1L);
        verify(queue).getQueueState("a", 1L, EventWindowStatus.OPEN, true);
        verify(queue).getQueueState("b", 1L, EventWindowStatus.OPEN, true);
        verify(queue).getQueueState("c", 1L, EventWindowStatus.OPEN, true);
        verify(emitters).send("a", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
        verify(emitters).send("b", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
        verify(emitters).send("c", "sale-exhausted", Map.of("soldOutAt", "2026-09-12T16:12:00Z"));
    }

    /**
     * A pause is not an ending (ADR-066). It must not reach the replay log, where a reconnect after the
     * resume would be handed a pause that is over, and it must not close anyone's stream.
     */
    @Test
    @DisplayName("A paused sale says so on every stream, retains nothing, and says once when it resumes")
    void pauseIsAnnouncedLocallyAndResumeOnce() {
        SseEmitterRegistry emitters = mock(SseEmitterRegistry.class);
        QueueService queue = mock(QueueService.class);
        CatalogFacade catalog = mock(CatalogFacade.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        QueueReplayService replay = mock(QueueReplayService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);

        when(emitters.watchedEventIds()).thenReturn(Set.of(1L));
        when(emitters.sessionsWatching(1L)).thenReturn(Set.of("a", "b"));
        when(catalog.getTierAvailability(1L)).thenReturn(List.of());
        when(redis.opsForZSet()).thenReturn(zsets);
        when(queue.getQueueState(anyString(), anyLong(), any(), any()))
                .thenReturn(new QueueState(QueuePhase.WAITING, 3, null, null, null));
        when(catalog.getWindowStatus(1L))
                .thenReturn(EventWindowStatus.PAUSED, EventWindowStatus.PAUSED, EventWindowStatus.OPEN,
                        EventWindowStatus.OPEN);

        QueueBroadcaster broadcaster =
                new QueueBroadcaster(emitters, queue, mock(QueueDrainRateTracker.class), catalog, redis, clock, replay);

        broadcaster.pushPositions();
        broadcaster.pushPositions();

        verify(emitters, times(2)).send("a", "sale-paused", Map.of());
        verify(emitters, times(2)).send("b", "sale-paused", Map.of());
        verify(emitters, never()).sendPosition(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(emitters, never()).closeAll(anyLong(), anyString(), any());
        verifyNoInteractions(replay);

        broadcaster.pushPositions();
        broadcaster.pushPositions();

        verify(emitters, times(1)).send("a", "sale-resumed", Map.of());
        verify(emitters, times(1)).send("b", "sale-resumed", Map.of());
        verify(emitters, times(2)).sendPosition("a", 3, null);
    }
}
