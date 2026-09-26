package com.flashseats.queue.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Every Redis key this module owns, formatted in one place.
 *
 * <p>A dozen call sites build these strings. Inlining the formats would make a single typo produce a
 * key that is written but never read — a bug that looks like "the queue silently does nothing" and
 * is very hard to see.
 *
 * <p>No other module reads or writes the {@code queue:} prefix.
 */
public final class QueueKeys {

    private QueueKeys() {}

    /**
     * ZSET of everyone waiting. Score is arrival time, so rank is position.
     *
     * <p>Like {@link #passes} and {@link #admissions} this key is <strong>never deleted by the
     * application</strong> — it expires (ADR-036). Deleting a live waiting room is a destructive act
     * that cannot be undone if the condition that triggered it turns out to be wrong, and once was:
     * a missing inventory counter looked like a sold-out sale and took the whole line with it.
     */
    public static String waiting(long eventId) {
        return "queue:waiting:" + eventId;
    }

    /**
     * The single-use pass, held only until it is exchanged for an admission session.
     *
     * <p><strong>Scoped by event, like every other key here</strong> (ADR-036). It was not, and one
     * visitor queueing for two concurrent sales had their promotion in one overwrite the other:
     * the second sale reported them {@code PROMOTED} holding a pass its own {@code /admit} then
     * refused, hiding their real position behind a token they could never spend.
     */
    public static String pass(long eventId, String sessionId) {
        return "queue:pass:" + eventId + ":" + sessionId;
    }

    /**
     * ZSET of live passes, scored by expiry.
     *
     * <p>Scored that way so a live count is {@code ZCOUNT now +inf} — self-cleaning, with no separate
     * counter to decrement and no way to leak.
     */
    public static String passes(long eventId) {
        return "queue:passes:" + eventId;
    }

    /** Proof that this session is inside the sale. */
    public static String admission(long eventId, String sessionId) {
        return "queue:admit:" + eventId + ":" + sessionId;
    }

    /** ZSET of live admissions, scored by expiry. */
    public static String admissions(long eventId) {
        return "queue:admissions:" + eventId;
    }

    /**
     * Pub/Sub channel carrying promotions to whichever replica holds the buyer's SSE connection.
     *
     * <p>Without this the promoter, which runs on one replica, would publish into the void for every
     * buyer connected to another — and behind three replicas most promotions would simply vanish
     * (ADR-007).
     */
    public static String events(long eventId) {
        return "queue:events:" + eventId;
    }

    /** Bounded replay log for durable, low-frequency SSE frames. */
    public static String replay(long eventId) {
        return "queue:replay:" + eventId;
    }

    /** Monotonic SSE event id for the replay log. */
    public static String replaySequence(long eventId) {
        return "queue:replay-seq:" + eventId;
    }

    /** Makes the promotion tick a singleton across replicas (ADR-032). */
    public static String promotionLock(long eventId) {
        return "queue:promote:" + eventId;
    }

    /**
     * The cluster-wide admission allowance for one promotion interval (ADR-049). It is the one key
     * deliberately not scoped by event: every sale shares it. Its own TTL is the window, so replicas
     * need not agree on the time.
     */
    public static String admissionBudget() {
        return "queue:budget";
    }

    /**
     * Marker that this event's stock is gone and nobody holds a claim on it. Deleted again the moment
     * stock returns, so {@code EXHAUSTED} is derived, never an irreversible act (ADR-035). It also makes
     * the terminal frame publish once.
     */
    public static String exhausted(long eventId) {
        return "queue:exhausted:" + eventId;
    }

    /**
     * Sets {@code key} to expire {@code retentionSeconds} after the sale ends (ADR-036).
     *
     * <p>Every queue key expires and none is deleted by the application: deleting a live waiting room
     * cannot be undone, and Redis runs {@code noeviction}, so a key with no TTL is a leak nothing
     * else cleans up. Idempotent; the promotion tick refreshes it every second. A sale already past
     * the retention window sets nothing, because Redis reads a non-positive TTL as "delete now".
     */
    static void expireWithSale(
            StringRedisTemplate redis, String key, Instant now, Instant saleEndTime, long retentionSeconds) {
        Duration ttl = Duration.between(now, saleEndTime.plusSeconds(retentionSeconds));
        if (ttl.isPositive()) {
            redis.expire(key, ttl);
        }
    }
}
