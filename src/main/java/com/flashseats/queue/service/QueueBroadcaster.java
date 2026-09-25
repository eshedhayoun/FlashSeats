package com.flashseats.queue.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierAvailability;
import com.flashseats.queue.facade.QueuePhase;
import com.flashseats.queue.facade.QueueState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    /**
     * A wait can legitimately last an hour, so the stream must outlive any default. Nginx has a
     * matching {@code proxy_read_timeout}; a shorter value at either end severs every stream on a
     * timer.
     */
    private static final long STREAM_TIMEOUT_MS = Duration.ofHours(1).toMillis();

    private final SseEmitterRegistry emitters;
    private final QueueService queue;
    private final QueueDrainRateTracker drainRate;
    private final CatalogFacade catalog;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final QueueReplayService replay;
    private final Map<Long, List<TierAvailability>> lastAvailability = new ConcurrentHashMap<>();

    public QueueBroadcaster(
            SseEmitterRegistry emitters,
            QueueService queue,
            QueueDrainRateTracker drainRate,
            CatalogFacade catalog,
            StringRedisTemplate redis,
            Clock clock,
            QueueReplayService replay) {
        this.emitters = emitters;
        this.queue = queue;
        this.drainRate = drainRate;
        this.catalog = catalog;
        this.redis = redis;
        this.clock = clock;
        this.replay = replay;
    }

    /**
     * Opens a buyer's live stream: replay what they missed, then send their own state at once.
     *
     * <p>Only broadcast frames carry an SSE {@code id}, and they are exactly the ones
     * {@link QueueReplayService} retains, so a reconnect's {@code Last-Event-ID} always names a
     * sequence the replay log minted. Per-session state is <strong>re-derived, not replayed</strong>:
     * a buyer promoted while disconnected gets {@code queue-promoted} rebuilt from the live pass key,
     * which is the authority. That works with no {@code Last-Event-ID} at all, and it can never hand
     * back a spent or expired pass (ADR-058).
     */
    public SseEmitter connect(String sessionId, long eventId, String lastEventId) {
        SseEmitter emitter = emitters.register(sessionId, eventId, STREAM_TIMEOUT_MS);

        if (lastEventId != null) {
            for (var frame : replay.after(eventId, lastEventId)) {
                emitters.send(sessionId, frame.type(), frame.data(), frame.id());
            }
        }

        // Flush at once even if this buyer has no position to send; an empty stream for two seconds
        // looks like a failure to connect.
        emitters.comment(sessionId, "connected");

        QueueState state = queue.getQueueState(sessionId, eventId);
        if (state.phase() == QueuePhase.PROMOTED && state.passToken() != null) {
            Long expiresInSeconds = queue.passTimeToLiveSeconds(sessionId, eventId);
            if (expiresInSeconds != null) {
                var promotion = QueueChannelMessage.promotion(sessionId, state.passToken(), expiresInSeconds);
                emitters.send(sessionId, promotion.type(), promotion.data());
            }
        } else if (state.position() != null) {
            emitters.sendPosition(sessionId, state.position(), state.estWaitSeconds());
        }
        return emitter;
    }

    /**
     * Sweeps this replica's own connections, driven by the emitters rather than the open-event list, so
     * a sale that closes on the clock still reaches its streams (ADR-036). The window is resolved once
     * per event.
     */
    @Scheduled(
            fixedDelayString = "${flashseats.queue.sse-position-interval-ms}",
            initialDelayString = "${flashseats.queue.sse-position-interval-ms}")
    public void pushPositions() {
        Set<Long> watchedEventIds = emitters.watchedEventIds();
        lastAvailability.keySet().removeIf(eventId -> !watchedEventIds.contains(eventId));
        for (long eventId : watchedEventIds) {
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
            lastAvailability.remove(eventId);
            replay.publishAndFanOut(
                    eventId, QueueChannelMessage.toAll(
                            "sale-closed", Map.of("closedAt", clock.instant().toString())));
            return;
        }

        sampleDepth(eventId);
        publishAvailabilityIfChanged(eventId);
        boolean exhausted = queue.isExhausted(eventId);

        for (String sessionId : emitters.sessionsWatching(eventId)) {
            var state = queue.getQueueState(sessionId, eventId, window, exhausted);
            if (state.phase() == QueuePhase.EXHAUSTED) {
                // Derived from live stock, so it is not terminal for the connection: if seats come
                // back the marker clears and this buyer's position is still theirs (ADR-035).
                emitters.send(
                        sessionId, "sale-exhausted", Map.of("soldOutAt", clock.instant().toString()));
            } else if (state.position() != null) {
                emitters.sendPosition(sessionId, state.position(), state.estWaitSeconds());
            }
        }
    }

    private void publishAvailabilityIfChanged(long eventId) {
        List<TierAvailability> current = catalog.getTierAvailability(eventId);
        List<TierAvailability> previous = lastAvailability.put(eventId, current);
        if (!current.equals(previous)) {
            replay.publishAndFanOut(
                    eventId, QueueChannelMessage.toAll(
                            "tier-availability", Map.of("tiers", current)));
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
