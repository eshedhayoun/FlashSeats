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
import org.springframework.data.redis.core.script.RedisScript;
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
    private final RedisScript<Long> promotionScript;
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
            RedisScript<Long> promotionScript,
            CatalogFacade catalog,
            QueueTokens tokens,
            QueueProperties properties,
            QueueReplayService replay,
            GlobalPromotionBudget globalBudget,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        this.redis = redis;
        this.promotionScript = promotionScript;
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

        Set<Long> open = Set.copyOf(openEvents);
        for (long eventId : catalog.findManagedEventIds()) {
            if (!open.contains(eventId)) {
                try {
                    refreshExhaustion(eventId);
                } catch (RuntimeException failure) {
                    log.warn("Could not re-check exhaustion for paused event {}", eventId, failure);
                }
            }
        }
    }

    /**
     * A paused sale promotes nobody, but its stock still moves: an unpaid hold that expires during the
     * pause gives its seats back. Exhaustion is derived from that stock, so it must un-derive while
     * paused too, or a buyer who joins during the pause is told the sale has sold out — and the first
     * broadcaster sweep after the resume repeats it to everyone in line (ADR-035, ADR-066). Nothing is
     * ever marked exhausted here: no seat can be taken while paused.
     */
    private void refreshExhaustion(long eventId) {
        if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.exhausted(eventId)))
                && catalog.getRemainingForEvent(eventId) > 0) {
            redis.delete(QueueKeys.exhausted(eventId));
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

        if (remaining > 0) {
            // Exhaustion is derived, so it un-derives: a released hold or a rebuilt counter puts
            // seats back and the waiting room resumes exactly where it was (ADR-035).
            redis.delete(QueueKeys.exhausted(eventId));
        } else {
            // Even with passes and admissions still out: those buyers are past the line and will find
            // the same empty tiers. Waiting for their claims to lapse kept everyone still in line on
            // WAITING, going nowhere, for up to the admission TTL after the last seat went (ADR-079).
            exhaust(eventId, now);
            return;
        }

        long pendingPasses = count(QueueKeys.passes(eventId), nowMillis);
        long liveAdmissions = count(QueueKeys.admissions(eventId), nowMillis);
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

        List<Promotion> promotions = front.stream()
                .limit(budgeted)
                .map(sessionId -> new Promotion(sessionId, tokens.mintPass(eventId, sessionId)))
                .toList();
        issuePasses(eventId, promotions, now);
        admitted.increment(promotions.size());
        log.debug("Promoted {} session(s) for event {}", promotions.size(), eventId);
    }

    /**
     * Mints the passes, moves the buyers out of the line, and tells them.
     *
     * <p>The writes go in one script: three commands per buyer as separate round trips made the tick's
     * cost grow with every buyer it admitted, and the tick has to finish inside its own lock (ADR-032).
     * It was a pipeline until a pipeline turned out to cost a fresh connection per call, because it
     * cannot share the multiplexed one and no pool is configured (ADR-079).
     *
     * <p>The {@code PUBLISH} comes after every pass exists, so no browser is told about a pass it
     * cannot yet redeem. It is what actually reaches the browser: this worker runs on one replica, the
     * buyer's stream may be held by another, and every replica subscribes and delivers to its own
     * connections (ADR-007).
     */
    private void issuePasses(long eventId, List<Promotion> promotions, Instant now) {
        List<String> keys = new ArrayList<>(promotions.size() + 2);
        keys.add(QueueKeys.passes(eventId));
        keys.add(QueueKeys.waiting(eventId));
        List<Object> args = new ArrayList<>(promotions.size() * 2 + 2);
        args.add(String.valueOf(properties.getPassTtlSeconds()));
        args.add(String.valueOf(now.plusSeconds(properties.getPassTtlSeconds()).toEpochMilli()));
        for (Promotion promotion : promotions) {
            keys.add(QueueKeys.pass(eventId, promotion.sessionId()));
            args.add(promotion.sessionId());
            args.add(promotion.passToken());
        }
        redis.execute(promotionScript, keys, args.toArray());

        for (Promotion promotion : promotions) {
            publish(
                    eventId,
                    QueueChannelMessage.promotion(
                            promotion.sessionId(), promotion.passToken(), properties.getPassTtlSeconds()));
        }
    }

    /**
     * Stock is gone: say so once, and change nothing else. The waiting set stays intact (ADR-035). The
     * trigger is a live inventory read that a released hold can reverse a second later. The {@code SETNX}
     * marker makes {@code EXHAUSTED} a derived state, cleared by the next tick with stock, and publishes
     * the frame once.
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

    private record Promotion(String sessionId, String passToken) {}

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
