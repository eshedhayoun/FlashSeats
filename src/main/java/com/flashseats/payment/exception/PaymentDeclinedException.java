package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * The card was refused.
 *
 * <p>Carries {@code attemptsRemaining} and {@code expiresAt} because the buyer's next decision
 * depends on both: they keep their seats and may try another card, but only while the reservation
 * lasts. No new grace extension is granted — the budget is per hold, not per attempt (ADR-030).
 */
public class PaymentDeclinedException extends FlashSeatsException {

    public PaymentDeclinedException(String detail, int attemptsRemaining, Instant expiresAt) {
        super(
                attemptsRemaining > 0
                        ? ErrorCode.PAYMENT_DECLINED
                        : ErrorCode.PAYMENT_ATTEMPTS_EXHAUSTED,
                detail);
        with("retryable", attemptsRemaining > 0);
        with("attemptsRemaining", attemptsRemaining);
        with("expiresAt", expiresAt);
    }
}
