package com.flashseats.queue.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.flashseats.queue.config.QueueProperties;

/**
 * Cluster-wide occupancy for pending passes and active admissions (ADR-049).
 *
 * <p>The event-independent ZSET is shared by every replica and every open sale. Expired members
 * are removed by the reserve operation, so a lost browser cannot permanently consume capacity.
 */
@Component
public class GlobalAdmissionBudget {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = script("redis/queue_budget_reserve.lua");
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = script("redis/queue_budget_renew.lua");

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final QueueProperties properties;

    /** Creates the cluster-wide budget service with the shared clock and queue tuning. */
    public GlobalAdmissionBudget(StringRedisTemplate redis, Clock clock, QueueProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.properties = properties;
    }

    /** Atomically removes expired reservations and reserves one global admission slot. */
    public boolean tryReserve(String member, Instant expiresAt, int maximumOccupancy) {
        Long result = redis.execute(
                RESERVE_SCRIPT,
                List.of(QueueKeys.globalAdmissionBudget()),
                member,
                Long.toString(expiresAt.toEpochMilli()),
                Long.toString(clock.instant().toEpochMilli()),
                Integer.toString(maximumOccupancy));
        if (!Long.valueOf(1L).equals(result)) {
            return false;
        }
        refreshKeyExpiry(expiresAt);
        return true;
    }

    /** Extends a pass reservation when its single-use pass becomes an admission session. */
    public boolean renew(String member, Instant expiresAt) {
        Long result = redis.execute(
                RENEW_SCRIPT,
                List.of(QueueKeys.globalAdmissionBudget()),
                member,
                Long.toString(expiresAt.toEpochMilli()),
                Long.toString(clock.instant().toEpochMilli()));
        if (!Long.valueOf(1L).equals(result)) {
            return false;
        }
        refreshKeyExpiry(expiresAt);
        return true;
    }

    /** Releases a reservation after the buyer completes the sale and no longer needs admission. */
    public void release(String member) {
        redis.opsForZSet().remove(QueueKeys.globalAdmissionBudget(), member);
    }

    /** Returns the number of currently live reservations after removing expired members. */
    public long liveReservations() {
        double now = clock.instant().toEpochMilli();
        redis.opsForZSet().removeRangeByScore(
                QueueKeys.globalAdmissionBudget(), Double.NEGATIVE_INFINITY, now);
        Long count = redis.opsForZSet().count(
                QueueKeys.globalAdmissionBudget(), now, Double.POSITIVE_INFINITY);
        return count == null ? 0 : count;
    }

    /** Builds a Redis script definition with the result type expected by the queue operations. */
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    /** Keeps the global ZSET temporary while allowing all live reservations to expire. */
    private void refreshKeyExpiry(Instant latestReservationExpiry) {
        Duration ttl = Duration.between(clock.instant(), latestReservationExpiry)
                .plusSeconds(properties.getKeyRetentionAfterSaleSeconds());
        redis.expire(QueueKeys.globalAdmissionBudget(), ttl);
    }
}
