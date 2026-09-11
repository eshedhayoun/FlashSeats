package com.flashseats.catalog.exception;

import com.flashseats.catalog.model.EventStatus;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * Pause or resume was asked for on an event that is neither {@code PUBLISHED} nor {@code PAUSED}.
 *
 * <p>Pausing a {@code DRAFT} would claim to halt a sale that was never running, and resuming a
 * {@code CANCELLED} one would publish something nobody published — an operator action with a much
 * larger blast radius than the one they asked for. Both are refused rather than interpreted.
 */
public class EventNotPausableException extends FlashSeatsException {

    public EventNotPausableException(long eventId, EventStatus status) {
        super(
                ErrorCode.SALE_PAUSED,
                "Event " + eventId + " is " + status + "; only a PUBLISHED or PAUSED sale can be"
                        + " paused or resumed.");
    }
}
