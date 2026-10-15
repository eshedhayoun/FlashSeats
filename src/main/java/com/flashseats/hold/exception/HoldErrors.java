package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The refusals {@code hold} raises (ADR-057, ADR-063). Five remain classes:
 * {@link InsufficientStockException} and {@link InventoryUnavailableException}, the pair ADR-004
 * keeps apart ("pick another tier" is not "we cannot see our inventory"), and the three
 * {@code Hold*} exceptions {@code PaymentSettlementService} catches as a set (ADR-053).
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
