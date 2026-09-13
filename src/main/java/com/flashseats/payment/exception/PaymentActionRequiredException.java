package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * The card issuer wants the buyer to authenticate (3-D Secure).
 *
 * <p>Carries {@code clientSecret} — what {@code stripe.handleNextAction} needs — and
 * {@code expiresAt}, because the challenge has to finish inside the reservation. No new grace is
 * granted: the one extension was already applied <em>before</em> the charge, so the challenge window
 * is already paid for (ADR-006, ADR-030).
 *
 * <p><strong>No payment attempt is consumed.</strong> Throwing lands in {@code CheckoutService}'s
 * existing catch-all, which marks the order {@code FAILED} — resumable on the same order number by
 * find-or-create (ADR-034) — without incrementing {@code payment_attempts}. A buyer who has to pass
 * a bank challenge has not used up one of their three cards.
 *
 * <p>{@code retryable} is true and the retry is the ordinary one: re-POST the same
 * {@code /orders/checkout} body once the challenge completes. There is no resume endpoint, and
 * building one would be a second retry mechanism (FE_SPEC §2).
 */
public class PaymentActionRequiredException extends FlashSeatsException {

    public PaymentActionRequiredException(String clientSecret, Instant expiresAt) {
        super(
                ErrorCode.PAYMENT_ACTION_REQUIRED,
                "Your bank needs to verify this payment. Your seats are still held.");
        with("retryable", true);
        with("clientSecret", clientSecret);
        with("expiresAt", expiresAt);
    }
}
