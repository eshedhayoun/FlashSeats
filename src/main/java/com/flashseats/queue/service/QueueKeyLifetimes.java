package com.flashseats.queue.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Gives this module's per-sale keys a lifetime tied to the sale they belong to (ADR-036).
 *
 * <p><strong>Every queue key expires; none is deleted by the application.</strong> Deleting a live
 * waiting room cannot be undone, and the condition that appeared to justify it — a zero stock read —
 * is routinely wrong a second later. Expiry is the safe way to reclaim the space: it happens only
 * once the sale is long over, and never because something looked finished.
 *
 * <p>The alternative is what the first pass shipped: keys with no TTL at all. Redis runs
 * {@code noeviction} precisely so it never discards anything quietly, which makes an unbounded key
 * the one leak nothing else cleans up — every sale ever run leaving its waiting, pass and admission
 * sets behind forever.
 *
 * <p>A static helper rather than a bean because it holds nothing. Both callers already have a Redis
 * template and the sale's end time.
 */
final class QueueKeyLifetimes {

    private QueueKeyLifetimes() {}

    /**
     * Sets {@code key} to expire {@code retentionSeconds} after the sale ends.
     *
     * <p>Idempotent and safe to repeat — the promotion tick refreshes these every second, which is
     * also what covers a key created between ticks. A sale that has already ended by more than the
     * retention window sets nothing: there is no positive lifetime left to grant, and Redis would
     * read a non-positive TTL as an instruction to delete the key immediately.
     */
    static void expireWithSale(
            StringRedisTemplate redis,
            String key,
            Instant now,
            Instant saleEndTime,
            long retentionSeconds) {

        Duration ttl = Duration.between(now, saleEndTime.plusSeconds(retentionSeconds));
        if (ttl.isPositive()) {
            redis.expire(key, ttl);
        }
    }
}
