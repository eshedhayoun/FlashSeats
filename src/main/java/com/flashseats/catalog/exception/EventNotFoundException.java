package com.flashseats.catalog.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** No such event. */
public class EventNotFoundException extends FlashSeatsException {

    public EventNotFoundException(long eventId) {
        super(ErrorCode.EVENT_NOT_FOUND, "No event with id " + eventId + ".");
    }
}
