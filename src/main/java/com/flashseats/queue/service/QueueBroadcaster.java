package com.flashseats.queue.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.facade.QueuePhase;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes position updates and heartbeats to this replica's own connections.
 *
 * <p>One scheduled sweep over the local registry, not one timer per connection: ten thousand waiting
 * buyers mean ten thousand emitters, and ten thousand independent timers would spend more time
 * scheduling than sending.
 *
 * <p>Safe to run on every replica because each only ever writes to sockets it owns.
 */
@Slf4j
@Component
public class QueueBroadcaster {

    private final SseEmitterRegistry emitters;
    private final QueueService queue;
    private final QueueDrainRateTracker drainRate;
    private final CatalogFacade catalog;
    private final StringRedisTemplate redis;
    private final java.time.Clock clock;

    public QueueBroadcaster(
            SseEmitterRegistry emitters,
            QueueService queue,
            QueueDrainRateTracker drainRate,
            CatalogFacade catalog,
            StringRedisTemplate redis,
            java.time.Clock clock) {
        this.emitters = emitters;
        this.queue = queue;
        this.drainRate = drainRate;
        this.catalog = catalog;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Sweeps this replica's own connections.
     *
     * <p><strong>Driven by the emitters, not by the open-event list</strong> (ADR-036). Sweeping
     * open events meant a sale that closed on the clock fell out of the loop entirely: nothing sent
     * another frame, nothing closed the stream, and everyone still waiting watched a frozen counter
     * until they thought to reload. The connections are what need serving, so they are what the
     * sweep iterates.
     *
     * <p>The window is resolved once per event and handed down, so adding it costs one read per
     * event rather than one per waiting buyer.
     */
    @Scheduled(
            fixedDelayString = "${flashseats.queue.sse-position-interval-ms}",
            initialDelayString = "${flashseats.queue.sse-position-interval-ms}")
    public void pushPositions() {
        for (long eventId : emitters.watchedEventIds()) {
            try {
                sweep(eventId);
            } catch (RuntimeException failure) {
                // One unreadable event must not stop the others from being served.
                log.warn("Could not sweep queue streams for event {}", eventId, failure);
            }
        }
    }

    private void sweep(long eventId) {
        EventWindowStatus window = catalog.getWindowStatus(eventId);

        if (window == EventWindowStatus.CLOSED) {
            emitters.closeAll(
                    eventId, "sale-closed", Map.of("closedAt", clock.instant().toString()));
            return;
        }

        sampleDepth(eventId);

        for (String sessionId : emitters.sessionsWatching(eventId)) {
            var state = queue.getQueueState(sessionId, eventId, window);
            if (state.phase() == QueuePhase.EXHAUSTED) {
                // Derived from live stock, so it is not terminal for the connection: if seats come
                // back the marker clears and this buyer's position is still theirs (ADR-035).
                emitters.send(sessionId, "sale-exhausted", Map.of("soldOutAt", clock.instant().toString()));
            } else if (state.position() != null) {
                emitters.sendPosition(sessionId, state.position(), state.estWaitSeconds());
            }
        }
    }

    /** Feeds the drain-rate estimate; see {@link QueueDrainRateTracker}. */
    private void sampleDepth(long eventId) {
        Long depth = redis.opsForZSet().zCard(QueueKeys.waiting(eventId));
        if (depth != null) {
            drainRate.record(eventId, depth, clock.instant());
        }
    }

    /**
     * Comment frames. Without them an idle stream looks dead to intermediate proxies and gets closed,
     * which the buyer sees as "disconnected" while they are doing nothing wrong.
     */
    @Scheduled(
            fixedDelayString = "${flashseats.queue.sse-heartbeat-ms}",
            initialDelayString = "${flashseats.queue.sse-heartbeat-ms}")
    public void pushHeartbeats() {
        emitters.watchedEventIds().forEach(emitters::heartbeat);
    }
}
