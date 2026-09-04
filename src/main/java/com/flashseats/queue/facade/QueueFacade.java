package com.flashseats.queue.facade;

/**
 * The only legal way into {@code queue} from another module.
 *
 * <p>Callers: {@code hold} (gating seat reservation on a live admission session), {@code order}
 * (revoking that session once a purchase completes), {@code saleflow} (rehydration).
 *
 * <p>Note what is absent: nothing here exposes the queue <em>pass</em>. It is minted, delivered and
 * spent entirely inside this module, so no other module can accidentally accept one as proof of
 * admission (ADR-020).
 */
public interface QueueFacade {

    /**
     * True when this session holds a live admission for this event.
     *
     * <p>Verifies both the signature and the stored session, because either alone is insufficient: a
     * signature proves origin but not that the session is still live, and a stored value proves
     * liveness but not that the presented token is the right one.
     */
    boolean verifyAdmission(String admissionToken, String userSessionId, long eventId);

    /**
     * Ends a session's admission. Called after an order is confirmed — the buyer has what they came
     * for, and holding a place in the sale would deny it to someone still waiting.
     */
    void revokeAdmission(String userSessionId, long eventId);

    /** Where this session stands, for rehydrating a page reload. */
    QueueState getQueueState(String userSessionId, long eventId);
}
