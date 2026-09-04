package com.flashseats.catalog.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** No such tier, or the tier does not belong to the event named in the request. */
public class TierNotFoundException extends FlashSeatsException {

    public TierNotFoundException(long eventId, long tierId) {
        super(ErrorCode.TIER_NOT_FOUND, "Tier " + tierId + " does not belong to event " + eventId + ".");
    }
}
