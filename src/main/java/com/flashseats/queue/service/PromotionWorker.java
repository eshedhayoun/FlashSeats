package com.flashseats.queue.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.queue.config.QueueProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Lets buyers out of the waiting room, at a rate the rest of the system can absorb.
 *
 * <p><strong>Two independent limits apply, and both matter.</strong> Admission control bounds
 * admission by <em>inventory</em> — promoting people into a sold-out sale just makes them wait
 * twenty minutes for a {@code 409} (ADR-008). The batch size bounds it by <em>capacity to serve</em>
 * — a tier with 5,000 seats left would otherwise admit 5,000 buyers into a checkout path backed by
 * 30 database connections, and under virtual threads nothing errors, requests simply pile up on the
 * pool while p99 collapses (ADR-028).
 *
 * <p><strong>Nobody is ever evicted.</strong> An abandoned entry reaches the front, is promoted,
 * never claims its pass, and that pass expires in two minutes — capacity comes back on its own. The
 * previous design removed entries whose heartbeat had lapsed, which deleted live buyers from the
 * line during an ordinary Wi-Fi to cellular handover (ADR-026).
 */
@Slf4j
@Component
public class PromotionWorker {

    private static final String NODE_ID = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;
    private final CatalogFacade catalog;
    private final QueueTokens tokens;
    private final QueueProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public PromotionWorker(
            StringRedisTemplate redis,
            CatalogFacade catalog,
            QueueTokens tokens,
            QueueProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.redis = redis;
        this.catalog = catalog;
        this.tokens = tokens;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${flashseats.queue.promotion-interval-ms}",
            initialDelayString = "${flashseats.queue.promotion-interval-ms}")
    public void tick() {
        for (long eventId : catalog.findOpenEventIds()) {
            try {
                promote(eventId);
            } catch (RuntimeException failure) {
                // One bad event must not stop the others from draining.
                log.error("Promotion tick failed for event {}", eventId, failure);
            }
        }
    }

    private void promote(long eventId) {
        if (!acquireTickLock(eventId)) {
            return; // another replica owns this tick
        }

        Instant now = clock.instant();
        double nowMillis = now.toEpochMilli();

        int remaining = catalog.getRemainingForEvent(eventId);
        if (remaining == CatalogFacade.COUNTER_UNAVAILABLE) {
            // Pause rather than guess. Admitting on a number we cannot read is how a sale oversells
            // — and, before ADR-035, declaring one exhausted on a number we could not read is how a
            // whole waiting room was told a sale had ended because a counter row was missing.
            log.warn("Inventory unreadable for event {}; promotion paused", eventId);
            return;
        }

        // Both sets are scored by expiry, so this drops exactly the members that have lapsed. They
        // are already excluded from the counts below; trimming keeps the keys from growing for the
        // length of the sale under a noeviction policy (ADR-036).
        trimExpired(QueueKeys.passes(eventId), nowMillis);
        trimExpired(QueueKeys.admissions(eventId), nowMillis);

        // ...and trimming alone is not enough: an emptied set still exists, and a key with no TTL
        // outlives the sale forever. Refreshed here, once per tick, which also covers a key created
        // by /queue/admit between ticks.
        Instant saleEndTime = catalog.getEventSummary(eventId).saleEndTime();
        expireWithSale(QueueKeys.passes(eventId), now, saleEndTime);
        expireWithSale(QueueKeys.admissions(eventId), now, saleEndTime);
        expireWithSale(QueueKeys.waiting(eventId), now, saleEndTime);

        long pendingPasses = count(QueueKeys.passes(eventId), nowMillis);
        long liveAdmissions = count(QueueKeys.admissions(eventId), nowMillis);

        if (remaining > 0) {
            // Exhaustion is derived, so it un-derives: a released hold or a rebuilt counter puts
            // seats back and the waiting room resumes exactly where it was (ADR-035).
            redis.delete(QueueKeys.exhausted(eventId));
        } else if (pendingPasses == 0 && liveAdmissions == 0) {
            exhaust(eventId, now);
            return;
        }

        long admittable = Math.min(
                properties.getPromotionBatchSize(),
                (long) Math.floor(remaining * properties.getOversubscribeFactor())
                        - pendingPasses
                        - liveAdmissions);
        if (admittable <= 0) {
            return;
        }

        Set<String> front = redis.opsForZSet().range(QueueKeys.waiting(eventId), 0, admittable - 1);
        if (front == null || front.isEmpty()) {
            return;
        }

        for (String sessionId : front) {
            issuePass(eventId, sessionId, now);
        }
        log.debug("Promoted {} session(s) for event {}", front.size(), eventId);
    }

    /**
     * Mints a pass, moves the buyer out of the line, and tells them.
     *
     * <p>The {@code PUBLISH} is what actually reaches the browser. This worker runs on one replica;
     * the buyer's stream may be held by another. Every replica subscribes and delivers to its own
     * connections (ADR-007).
     */
    private void issuePass(long eventId, String sessionId, Instant now) {
        String passToken = tokens.mintPass(eventId, sessionId);
        Instant expiresAt = now.plusSeconds(properties.getPassTtlSeconds());

        redis.opsForValue()
                .set(
                        QueueKeys.pass(eventId, sessionId),
                        passToken,
                        Duration.ofSeconds(properties.getPassTtlSeconds()));
        redis.opsForZSet()
                .add(QueueKeys.passes(eventId), sessionId, (double) expiresAt.toEpochMilli());
        redis.opsForZSet().remove(QueueKeys.waiting(eventId), sessionId);

        publish(
                eventId,
                QueueChannelMessage.toSession(
                        "queue-promoted",
                        sessionId,
                        Map.of(
                                "passToken", passToken,
                                "expiresInSeconds", properties.getPassTtlSeconds())));
    }

    /**
     * Stock is gone and nobody holds a claim on it. Say so — once — and change nothing else.
     *
     * <p><strong>The waiting set is deliberately left intact</strong> (ADR-035). Deleting it was the
     * original behaviour and it is unrecoverable: the condition that triggers this is a live
     * inventory read, a released hold puts seats straight back, and a missing counter used to look
     * exactly like a sold-out sale. Everyone in the line was deleted on the strength of a number
     * that could be wrong a second later.
     *
     * <p>Setting the marker instead makes the state <em>derived</em>: {@code getQueueState} reports
     * {@code EXHAUSTED} while it exists, the next tick with stock removes it, and every buyer's
     * position is exactly where they left it. {@code SETNX} is also what makes the frame publish
     * once rather than every second for the rest of the sale.
     */
    private void exhaust(long eventId, Instant now) {
        Boolean firstToSee = redis.opsForValue()
                .setIfAbsent(
                        QueueKeys.exhausted(eventId),
                        now.toString(),
                        Duration.ofSeconds(properties.getKeyRetentionAfterSaleSeconds()));
        if (!Boolean.TRUE.equals(firstToSee)) {
            return;
        }
        log.info("Event {} is exhausted; notifying the waiting room", eventId);
        publish(
                eventId,
                QueueChannelMessage.toAll("sale-exhausted", Map.of("soldOutAt", now.toString())));
    }

    /** Drops members whose score — their expiry — is already in the past. */
    private void trimExpired(String zsetKey, double nowMillis) {
        redis.opsForZSet().removeRangeByScore(zsetKey, Double.NEGATIVE_INFINITY, nowMillis);
    }

    private void expireWithSale(String key, Instant now, Instant saleEndTime) {
        QueueKeyLifetimes.expireWithSale(
                redis, key, now, saleEndTime, properties.getKeyRetentionAfterSaleSeconds());
    }

    /**
     * Makes the tick a singleton across replicas.
     *
     * <p>A plain Redis {@code SET NX PX} rather than a PostgreSQL advisory lock (ADR-032). A
     * transaction-scoped advisory lock only holds while a transaction is open, and this worker's
     * entire job is Redis writes — which may not happen inside a SQL transaction (ADR-023). The two
     * documented rules contradict each other here; a self-expiring Redis lock satisfies both.
     *
     * <p>Deliberately not released: the TTL is shorter than the tick interval, so it frees itself,
     * and never deleting it removes any chance of one replica releasing another's lock. If a tick
     * somehow overruns the TTL, two replicas may both promote — bounded by the batch size, and
     * already absorbed by the oversubscribe factor.
     */
    private boolean acquireTickLock(long eventId) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(
                        QueueKeys.promotionLock(eventId),
                        NODE_ID,
                        Duration.ofMillis(properties.getPromotionIntervalMs() * 9 / 10));
        return Boolean.TRUE.equals(acquired);
    }

    private long count(String zsetKey, double fromMillis) {
        Long count = redis.opsForZSet().count(zsetKey, fromMillis, Double.POSITIVE_INFINITY);
        return count == null ? 0 : count;
    }

    private void publish(long eventId, QueueChannelMessage message) {
        try {
            redis.convertAndSend(QueueKeys.events(eventId), json.writeValueAsString(message));
        } catch (Exception failed) {
            log.error("Could not publish {} for event {}", message.type(), eventId, failed);
        }
    }
}
