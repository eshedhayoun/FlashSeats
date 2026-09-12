package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * One shared promotion allowance for the whole cluster (ADR-049).
 *
 * <p>The per-event tick lock stops two replicas promoting the <em>same</em> sale in one second. It
 * does nothing about five healthy sales each sending a full batch into the <em>same</em> connection
 * pool: at {@code E} open events and {@code R} replicas that is {@code R × E × batchSize} buyers per
 * second arriving at a checkout backed by {@code R × 30} connections. Under virtual threads nothing
 * errors — the requests queue on HikariCP while p99 collapses, and the Pass 7 drill measured 31.3 s
 * against a 200 ms criterion with 202 connections pending.
 *
 * <p>So the allowance is spent in Redis, where every replica and every sale draw on one pool for the
 * same interval.
 *
 * <p><strong>It is claimed, not divided.</strong> Dividing the batch by the number of open sales is
 * simpler and wrong in both directions: with one open sale it throttles the system to a fraction of
 * what it can serve, and {@code E} changes whenever an operator publishes an event. A claim tracks
 * real demand — a quiet sale asks for nothing and its share goes to whoever is promoting.
 *
 * <p><strong>It fails closed.</strong> If the claim cannot be made, nobody is promoted this tick and
 * the next tick tries again. The waiting room is correctness-neutral: a late promotion costs a
 * second of someone's patience, while promoting without an allowance is the defect this class
 * exists to prevent.
 */
@Slf4j
@Component
public class GlobalPromotionBudget {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> claimScript;
    private final QueueProperties properties;

    public GlobalPromotionBudget(
            StringRedisTemplate redis,
            RedisScript<Long> promotionBudgetScript,
            QueueProperties properties) {
        this.redis = redis;
        this.claimScript = promotionBudgetScript;
        this.properties = properties;
    }

    /**
     * Claims up to {@code requested} admissions from the current window.
     *
     * @return how many may be promoted now — never more than was asked for, and never more than the
     *     window has left. {@code 0} means promote nobody.
     */
    public long claim(long requested) {
        long budget = properties.getGlobalAdmissionBudgetPerTick();
        if (requested <= 0 || budget <= 0) {
            return 0;
        }

        try {
            Long granted = redis.execute(
                    claimScript,
                    List.of(QueueKeys.admissionBudget()),
                    String.valueOf(budget),
                    String.valueOf(requested),
                    String.valueOf(properties.getPromotionIntervalMs()));
            return granted == null ? 0 : granted;
        } catch (RuntimeException failed) {
            log.error("Could not claim the admission budget; promoting nobody this tick", failed);
            return 0;
        }
    }
}
