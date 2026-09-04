package com.flashseats.hold.facade;

import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.exception.HoldNotFoundException;
import java.time.Instant;
import java.util.Optional;

/**
 * The only legal way into {@code hold} from another module.
 *
 * <p>Callers: {@code order} (the checkout path) and {@code saleflow} (rehydration).
 *
 * <p>Read the transaction notes on {@link #consumeHold} and {@link #grantGrace} before using them —
 * both have propagation requirements that are part of their contract, not implementation detail.
 */
public interface HoldFacade {

    /**
     * The live hold, ownership-checked.
     *
     * @throws HoldNotFoundException if it does not exist or belongs to another session
     * @throws HoldExpiredException if it has been settled or its window has passed
     */
    HoldSummary getActiveHold(String holdToken, String userSessionId);

    /** This session's live hold for an event, if any. Read-only; for rehydration. */
    Optional<HoldSummary> findActiveHold(String userSessionId, long eventId);

    /**
     * Claims the hold as sold.
     *
     * <p><strong>Must be called from inside the caller's transaction</strong>, and will fail fast if
     * there is none. It is a conditional {@code UPDATE}, so it rolls back with the order it belongs
     * to — that is the whole reason the claim lives in SQL (ADR-019).
     *
     * @throws HoldAlreadySettledException if the claim is lost. The seats are gone; the caller must
     *     roll back and refund any settled charge (ADR-012).
     */
    HoldSummary consumeHold(String holdToken);

    /** Hands seats back, restoring stock if this caller wins the settle-once claim. */
    void releaseHold(String holdToken, String reason);

    /**
     * Grants the single grace extension, if this hold has not used it (ADR-030).
     *
     * <p>Idempotent by design: a retry after a decline gets the current expiry back rather than a
     * second extension or an error.
     *
     * @return the hold's expiry after the call
     * @throws HoldExpiredException if the hold is no longer live. <strong>The caller must abort
     *     before charging</strong> — charging past this point takes money for seats we no longer
     *     hold.
     */
    Instant grantGrace(String holdToken);

    /**
     * Best-effort cleanup of the hold's expiry timer, called from {@code AFTER_COMMIT}.
     *
     * <p>A no-op until Redis carries the timer. It is safe to lose entirely: if it never runs, the
     * key expires by itself and the expiry handler finds the hold already {@code CONSUMED} and
     * correctly does nothing.
     */
    void discardTimer(String holdToken);
}
