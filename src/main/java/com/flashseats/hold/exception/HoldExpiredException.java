package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * The reservation window passed.
 *
 * <p>{@code 410}, not {@code 409}: the opportunity existed and is gone, and the SPA renders a
 * different screen for each. The wording matters as much as the code — "Nothing was charged" is the
 * single most reassuring sentence in the product, and it is always true here.
 */
public class HoldExpiredException extends FlashSeatsException {

    public HoldExpiredException(String holdToken, Instant expiresAt) {
        super(ErrorCode.HOLD_EXPIRED, "Your reservation ended. Nothing was charged.");
        with("retryable", false);
        with("expiresAt", expiresAt);
    }
}
