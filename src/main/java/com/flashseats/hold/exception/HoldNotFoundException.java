package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * No such hold, or it is not this session's.
 *
 * <p>Both cases return {@code 404} deliberately: a {@code 403} for "exists but not yours" would let
 * anyone enumerate valid hold tokens.
 */
public class HoldNotFoundException extends FlashSeatsException {

    public HoldNotFoundException(String holdToken) {
        super(ErrorCode.HOLD_NOT_FOUND, "No reservation found for this session.");
    }
}
