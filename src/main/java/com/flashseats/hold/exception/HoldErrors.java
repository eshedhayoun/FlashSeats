package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The refusals {@code hold} raises that carry no branching of their own.
 *
 * <p>Five of this module's failures are still classes, and each earns it:
 *
 * <ul>
 *   <li>{@link InsufficientStockException} and {@link InventoryUnavailableException} — the pair
 *       ADR-004 exists to keep apart. "Pick another tier" and "we cannot see our own inventory" are
 *       different answers, and two sibling types make that visible in a way two factory methods
 *       would not.
 *   <li>{@link HoldNotFoundException}, {@link HoldExpiredException} and
 *       {@link HoldAlreadySettledException} — the three ways a webhook settlement can find the seats
 *       gone. {@code PaymentSettlementService} catches them as a set and refunds, so all three have
 *       to be types (ADR-053). {@code HoldExpiredException} also carries {@code expiresAt}, and is
 *       the one message in the product that must always be able to promise nothing was charged.
 * </ul>
 */
public final class HoldErrors {

    private HoldErrors() {}

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
