package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The refusals {@code hold} raises that carry no branching of their own.
 *
 * <p>Four of this module's failures are still classes, and each earns it:
 *
 * <ul>
 *   <li>{@link InsufficientStockException} and {@link InventoryUnavailableException} — the pair
 *       ADR-004 exists to keep apart. "Pick another tier" and "we cannot see our own inventory" are
 *       different answers, and two sibling types make that visible in a way two factory methods
 *       would not.
 *   <li>{@link HoldExpiredException} — carries {@code expiresAt}, and is the one message in the
 *       product that must always be able to promise nothing was charged.
 *   <li>{@link HoldAlreadySettledException} — a safety stop for {@code order}, not a nuisance: it
 *       means the seats are gone and a settled charge must be refunded.
 * </ul>
 */
public final class HoldErrors {

    private HoldErrors() {}

    /**
     * No such hold, or it is not this session's.
     *
     * <p>Both cases return {@code 404} deliberately: a {@code 403} for "exists but not yours" would
     * let anyone enumerate valid hold tokens.
     */
    public static FlashSeatsException holdNotFound(String holdToken) {
        return new FlashSeatsException(ErrorCode.HOLD_NOT_FOUND, "No reservation found for this session.");
    }

    /**
     * This session already holds seats for this event (ADR-017).
     *
     * <p>Enforced by a partial unique index rather than a preceding {@code SELECT}, so it cannot be
     * raced. The client's correct response is to rehydrate and resume the hold it already has.
     */
    public static FlashSeatsException holdLimitExceeded(long eventId) {
        return new FlashSeatsException(
                ErrorCode.HOLD_LIMIT_EXCEEDED,
                "You already have seats reserved for this event. Release them to choose again.");
    }

    /** Requested more seats than the tier allows in one order (ADR-017). */
    public static FlashSeatsException quantityExceedsLimit(int requested, int max) {
        return new FlashSeatsException(
                ErrorCode.QUANTITY_EXCEEDS_LIMIT,
                "You can reserve at most " + max + " seats in one order (requested " + requested + ").");
    }
}
