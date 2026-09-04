package com.flashseats.queue.service;

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

    /** ZSET of everyone waiting. Score is arrival time, so rank is position. */
    public static String waiting(long eventId) {
        return "queue:waiting:" + eventId;
    }

    /** The single-use pass, held only until it is exchanged for an admission session. */
    public static String pass(String sessionId) {
        return "queue:pass:" + sessionId;
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

    /** Advisory liveness marker. Never consulted before promoting or evicting (ADR-026). */
    public static String heartbeat(String sessionId) {
        return "queue:hb:" + sessionId;
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

    /** Makes the promotion tick a singleton across replicas (ADR-032). */
    public static String promotionLock(long eventId) {
        return "queue:promote:" + eventId;
    }
}
