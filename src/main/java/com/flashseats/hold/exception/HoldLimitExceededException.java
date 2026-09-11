package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * This session already holds seats for this event (ADR-017).
 *
 * <p>Enforced by a partial unique index rather than a preceding {@code SELECT}, so it cannot be
 * raced. The client's correct response is to rehydrate and resume the hold it already has.
 */
public class HoldLimitExceededException extends FlashSeatsException {

    public HoldLimitExceededException(long eventId) {
        super(
                ErrorCode.HOLD_LIMIT_EXCEEDED,
                "You already have seats reserved for this event. Release them to choose again.");
    }
}
