package com.flashseats.catalog.exception;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * Everything {@code catalog} can refuse, in one place (ADR-057). Public because {@code hold} raises
 * {@link #saleNotOpen}: the window rule is catalog's, so hold must not keep a second copy of it.
 */
public final class CatalogErrors {

    private CatalogErrors() {}

    /** No such event. */
    public static FlashSeatsException eventNotFound(long eventId) {
        return new FlashSeatsException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + eventId + ".");
    }

    /** No such tier, or the tier does not belong to the event named in the request. */
    public static FlashSeatsException tierNotFound(long eventId, long tierId) {
        return new FlashSeatsException(
                ErrorCode.TIER_NOT_FOUND, "Tier " + tierId + " does not belong to event " + eventId + ".");
    }

    /**
     * The action requires an open sale window (ADR-016).
     *
     * <p>Each refusing status has its own code, because each asks the buyer for something different: a
     * countdown, patience, or a sale-ended panel (ADR-066).
     */
    public static FlashSeatsException saleNotOpen(long eventId, EventWindowStatus actual) {
        return switch (actual) {
            case PAUSED -> new FlashSeatsException(
                            ErrorCode.SALE_PAUSED,
                            "Sales are paused for a moment. You can reserve as soon as they resume, while"
                                    + " your time to choose seats lasts.")
                    .with("retryable", true);
            case UPCOMING -> new FlashSeatsException(ErrorCode.SALE_NOT_OPEN, "This sale has not started yet.");
            case CLOSED -> new FlashSeatsException(ErrorCode.SALE_CLOSED, "Sales for this event have ended.");
            case OPEN -> throw new IllegalArgumentException("Event " + eventId + " is open; nothing to refuse");
        };
    }

    /**
     * Pre-warm was attempted on a sale that is no longer {@code UPCOMING}.
     *
     * <p>This refusal is a safety interlock, not a formality: seeding inventory from
     * {@code total_capacity} once a sale is open would silently resurrect every ticket already sold
     * (ADR-004). Recovery on an open sale is an explicit rebuild from the ledger, never a reseed.
     */
    public static FlashSeatsException prewarmWindowClosed(long eventId) {
        return new FlashSeatsException(
                ErrorCode.PREWARM_WINDOW_CLOSED,
                "Event " + eventId + " is not UPCOMING; pre-warm would overwrite live inventory.");
    }

    /**
     * Pause or resume was asked for on an event that is neither {@code PUBLISHED} nor {@code PAUSED}.
     *
     * <p>Pausing a {@code DRAFT} would claim to halt a sale that was never running, and resuming a
     * {@code CANCELLED} one would publish something nobody published — an operator action with a much
     * larger blast radius than the one they asked for. Both are refused rather than interpreted.
     */
    public static FlashSeatsException eventNotPausable(long eventId, EventStatus status) {
        return new FlashSeatsException(
                ErrorCode.EVENT_NOT_PAUSABLE,
                "Event " + eventId + " is " + status + "; only a PUBLISHED or PAUSED sale can be"
                        + " paused or resumed.");
    }
}
