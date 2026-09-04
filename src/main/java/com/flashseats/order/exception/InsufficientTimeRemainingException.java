package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * Too little of the reservation is left to complete a charge safely (ADR-030).
 *
 * <p>Refusing here is kinder than trying. Starting a charge that cannot finish inside the window
 * risks taking money for seats that expire mid-transaction — the exact situation the refund path
 * exists to clean up, and better avoided than compensated.
 */
public class InsufficientTimeRemainingException extends FlashSeatsException {

    public InsufficientTimeRemainingException(Instant expiresAt) {
        super(
                ErrorCode.INSUFFICIENT_TIME_REMAINING,
                "There is not enough time left on your reservation to complete this safely.");
        with("retryable", false);
        with("expiresAt", expiresAt);
    }
}
