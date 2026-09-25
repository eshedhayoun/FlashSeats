package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * No such hold, or it is not this session's. Both are {@code 404}, so hold tokens cannot be
 * enumerated. A class because {@code PaymentSettlementService} catches it, with the other two
 * {@code Hold*} exceptions, as the ways a webhook settlement finds the seats gone (ADR-053).
 */
public class HoldNotFoundException extends FlashSeatsException {

    public HoldNotFoundException(String holdToken) {
        super(ErrorCode.HOLD_NOT_FOUND, "No reservation found for this session.");
    }
}
