package com.flashseats.queue.service;

import com.flashseats.catalog.facade.CatalogFacade;
import java.util.OptionalDouble;
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

    @Scheduled(
            fixedDelayString = "${flashseats.queue.sse-position-interval-ms}",
            initialDelayString = "${flashseats.queue.sse-position-interval-ms}")
    public void pushPositions() {
        for (long eventId : catalog.findOpenEventIds()) {
            sampleDepth(eventId);

            for (String sessionId : emitters.sessionsWatching(eventId)) {
                var state = queue.getQueueState(sessionId, eventId);
                if (state.position() != null) {
                    emitters.sendPosition(sessionId, state.position(), state.estWaitSeconds());
                }
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
        catalog.findOpenEventIds().forEach(emitters::heartbeat);
    }

    /** Exposed for tests that want the current estimate without waiting for a tick. */
    OptionalDouble drainRatePerSecond(long eventId) {
        return drainRate.perSecond(eventId);
    }
}
