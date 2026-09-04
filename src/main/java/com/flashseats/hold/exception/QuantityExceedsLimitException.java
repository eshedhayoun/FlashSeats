package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** Requested more seats than the tier allows in one order (ADR-017). */
public class QuantityExceedsLimitException extends FlashSeatsException {

    public QuantityExceedsLimitException(int requested, int max) {
        super(
                ErrorCode.QUANTITY_EXCEEDS_LIMIT,
                "You can reserve at most " + max + " seats in one order (requested " + requested + ").");
    }
}
