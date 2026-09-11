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

    /**
     * Marker that this event's stock is gone and nobody holds a claim on it.
     *
     * <p>Set by the promotion worker and <strong>deleted again the moment stock returns</strong>, so
     * {@code EXHAUSTED} is a state derived from live inventory rather than an irreversible act
     * (ADR-035). The earlier design expressed exhaustion by deleting the waiting set, which cannot
     * be undone — and a released hold or a rebuilt counter routinely makes it wrong.
     *
     * <p>It also makes the terminal frame publish once instead of on every tick.
     */
    public static String exhausted(long eventId) {
        return "queue:exhausted:" + eventId;
    }
}
