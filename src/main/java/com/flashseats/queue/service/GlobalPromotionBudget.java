package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * One shared promotion allowance for the whole cluster (ADR-049).
 *
 * <p>Per-event promotion locks stop duplicate workers for one event; they do not stop five open
 * events from each sending a full batch into checkout. This budget is spent in Redis so every
 * replica and every sale draw from the same pool for the same promotion interval.
 */
@Component
public class GlobalPromotionBudget {

    private final StringRedisTemplate redis;
    private final QueueProperties properties;
    private final Clock clock;

    public GlobalPromotionBudget(StringRedisTemplate redis, QueueProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public long claim(long requested) {
        long budget = properties.getGlobalAdmissionBudgetPerTick();
        if (requested <= 0 || budget <= 0) {
            return 0;
        }

        long cappedRequest = Math.min(requested, budget);
        String key = QueueKeys.globalPromotionBudget(tickBucket());
        Long spent = redis.opsForValue().increment(key, cappedRequest);
        redis.expire(key, ttl());

        if (spent == null) {
            return 0;
        }

        long overBudget = spent - budget;
        if (overBudget <= 0) {
            return cappedRequest;
        }

        long allowed = Math.max(0, cappedRequest - overBudget);
        long unused = cappedRequest - allowed;
        if (unused > 0) {
            redis.opsForValue().decrement(key, unused);
        }
        return allowed;
    }

    private long tickBucket() {
        long interval = Math.max(1, properties.getPromotionIntervalMs());
        return clock.instant().toEpochMilli() / interval;
    }

    private Duration ttl() {
        long interval = Math.max(1, properties.getPromotionIntervalMs());
        return Duration.ofMillis(interval * 2);
    }
}
