package com.flashseats.queue.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.queue.config.QueueProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Lets buyers out of the waiting room at a rate the rest of the system can absorb. Three limits
 * apply: remaining inventory (ADR-008), the per-sale batch that protects the connection pool
 * (ADR-028), and {@link GlobalPromotionBudget} for the cluster (ADR-049).
 *
 * <p><strong>Nobody is ever evicted.</strong> An abandoned entry is promoted, never claims its pass,
 * and the pass expires in two minutes (ADR-026).
 */
@Slf4j
@Component
public class PromotionWorker {

    private static final String NODE_ID = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;
    private final CatalogFacade catalog;
    private final QueueTokens tokens;
    private final QueueProperties properties;
    private final QueueReplayService replay;
    private final GlobalPromotionBudget globalBudget;
    private final ObjectMapper json;
    private final Clock clock;

    private final Counter admitted;
    private final Counter budgetDenied;

    public PromotionWorker(
            StringRedisTemplate redis,
            CatalogFacade catalog,
            QueueTokens tokens,
            QueueProperties properties,
            QueueReplayService replay,
            GlobalPromotionBudget globalBudget,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        this.redis = redis;
        this.catalog = catalog;
        this.tokens = tokens;
        this.properties = properties;
        this.replay = replay;
        this.globalBudget = globalBudget;
        this.json = json;
        this.clock = clock;

        // Without these two the new limit is invisible: "admitted slowly" and "nobody waiting" look
        // identical from the outside, and the budget is a number somebody has to be able to tune.
        this.admitted = Counter.builder("flashseats.queue.admissions")
                .description("Buyers let out of a waiting room")
                .register(meters);
        this.budgetDenied = Counter.builder("flashseats.queue.admission.budget.denied")
                .description("Buyers the cluster admission budget held back this tick (ADR-049)")
                .register(meters);
    }

    /**
     * <strong>The order is shuffled, and that is what makes the shared budget fair</strong> (ADR-049).
     * Every replica reads the same ascending list, so a fixed order lets the lowest event id take the
     * whole allowance every tick. Dividing the budget by {@code E} is rejected: it caps a busy sale
     * beside quiet ones.
     */
    @Scheduled(
            fixedDelayString = "${flashseats.queue.promotion-interval-ms}",
            initialDelayString = "${flashseats.queue.promotion-interval-ms}")
    public void tick() {
        List<Long> openEvents = new ArrayList<>(catalog.findOpenEventIds());
        Collections.shuffle(openEvents);

        for (long eventId : openEvents) {
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

        // Claimed against real demand, not against the cap: a sale with three people waiting asks
        // for three, so the rest of the cluster's allowance stays available to the sales that can
        // use it (ADR-049).
        long budgeted = globalBudget.claim(front.size());
        if (budgeted < front.size()) {
            budgetDenied.increment(front.size() - Math.max(budgeted, 0));
        }
        if (budgeted <= 0) {
            return;
        }

        long promoted = 0;
        for (String sessionId : front) {
            if (promoted >= budgeted) {
                break;
            }
            issuePass(eventId, sessionId, now);
            promoted++;
        }
        admitted.increment(promoted);
        log.debug("Promoted {} session(s) for event {}", promoted, eventId);
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
                QueueChannelMessage.promotion(
                        sessionId, passToken, properties.getPassTtlSeconds()));
    }

    /**
     * Stock is gone and nobody holds a claim on it: say so once, and change nothing else. The waiting
     * set stays intact (ADR-035). The trigger is a live inventory read that a released hold can
     * reverse a second later. The {@code SETNX} marker makes {@code EXHAUSTED} a derived state, cleared
     * by the next tick with stock, and publishes the frame once.
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
        QueueKeys.expireWithSale(
                redis, key, now, saleEndTime, properties.getKeyRetentionAfterSaleSeconds());
    }

    /**
     * Makes the tick a singleton across replicas with Redis {@code SET NX PX} (ADR-032): the worker's
     * writes are Redis, so a transaction-scoped advisory lock cannot hold them (ADR-023). Never
     * released: the TTL is shorter than the tick, so no replica can release another's lock. An overrun
     * is bounded by the batch size.
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
            replay.publishAndFanOut(eventId, message);
        } catch (Exception failed) {
            log.error("Could not publish {} for event {}", message.type(), eventId, failed);
        }
    }
}
