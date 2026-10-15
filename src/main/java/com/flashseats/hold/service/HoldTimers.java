package com.flashseats.hold.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Arms and disarms the {@code hold:{token}} expiry timer. Everything here is best-effort and never
 * throws: the timer only makes expiry fast, and {@link HoldReconciliationSweeper} makes it correct.
 * Callers arm {@code AFTER_COMMIT}, so a timer never outlives a rolled-back hold (ADR-023).
 */
@Slf4j
@Component
public class HoldTimers {

    private final StringRedisTemplate redis;
    private final Clock clock;

    public HoldTimers(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Sets the key to expire when the hold does.
     *
     * <p>The value is the token rather than something structured, deliberately. Nothing reads it —
     * the expiry <em>event</em> carries the key name, which is all the listener needs — and a value
     * nobody parses cannot drift out of step with the row that owns the truth. This is the
     * {@code holdmeta} key ADR-019 deleted, and it is not coming back.
     */
    public void arm(String holdToken, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            // Already past. Setting a non-positive TTL is an error in Redis, and there is nothing to
            // accelerate anyway: the sweeper's next pass owns this hold.
            return;
        }
        try {
            redis.opsForValue().set(HoldKeys.timer(holdToken), holdToken, ttl);
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Could not arm the expiry timer for hold {}; the sweeper will reclaim it instead",
                    holdToken,
                    unavailable);
        }
    }

    /**
     * Removes the timer for a hold that has already ended.
     *
     * <p>Idempotent, and safe to call for a hold that never had one. Skipping it entirely would also
     * be safe — the key would expire on its own and the listener would find the hold already settled
     * and correctly do nothing — which is exactly why this can swallow its own failures.
     */
    public void disarm(String holdToken) {
        try {
            redis.delete(HoldKeys.timer(holdToken));
        } catch (RuntimeException unavailable) {
            log.debug("Could not disarm the expiry timer for hold {}", holdToken, unavailable);
        }
    }
}
