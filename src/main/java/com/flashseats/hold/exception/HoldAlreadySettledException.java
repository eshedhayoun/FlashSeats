package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The settle-once claim was lost — someone else ended this hold first.
 *
 * <p>Raised when the conditional {@code UPDATE} affects zero rows. For {@code order} this is not a
 * nuisance but a safety stop: it means the seats are no longer ours, so the transaction must roll
 * back and any settled charge must be refunded (ADR-012).
 */
public class HoldAlreadySettledException extends FlashSeatsException {

    public HoldAlreadySettledException(String holdToken) {
        super(ErrorCode.HOLD_ALREADY_SETTLED, "This reservation has already been used or released.");
    }
}
