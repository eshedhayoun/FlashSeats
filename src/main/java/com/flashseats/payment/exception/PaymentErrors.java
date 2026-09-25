package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * The refusals {@code payment} raises. {@link DuplicatePaymentException} stays a class because
 * {@code CheckoutService} catches it by type (ADR-057, ADR-063).
 */
public final class PaymentErrors {

    private PaymentErrors() {}

    /**
     * The card was refused. The buyer keeps the seats and may try another card while the
     * reservation lasts. No new grace is granted: the budget is per hold, not per attempt (ADR-030).
     * With no attempts left, the answer becomes {@code PAYMENT_ATTEMPTS_EXHAUSTED}.
     */
    public static FlashSeatsException declined(String detail, int attemptsRemaining, Instant expiresAt) {
        return new FlashSeatsException(
                        attemptsRemaining > 0
                                ? ErrorCode.PAYMENT_DECLINED
                                : ErrorCode.PAYMENT_ATTEMPTS_EXHAUSTED,
                        detail)
                .with("retryable", attemptsRemaining > 0)
                .with("attemptsRemaining", attemptsRemaining)
                .with("expiresAt", expiresAt);
    }

    /**
     * 3-D Secure. The client runs the challenge with {@code clientSecret} and re-POSTs the same
     * checkout body; there is no resume endpoint (ADR-054). No attempt is consumed, and the grace
     * applied before the charge already pays for the challenge window (ADR-030).
     */
    public static FlashSeatsException actionRequired(String clientSecret, Instant expiresAt) {
        return new FlashSeatsException(
                        ErrorCode.PAYMENT_ACTION_REQUIRED,
                        "Your bank needs to verify this payment. Your seats are still held.")
                .with("retryable", true)
                .with("clientSecret", clientSecret)
                .with("expiresAt", expiresAt);
    }

    /** The provider could not be reached. Our problem, not the buyer's: the seats are retained. */
    public static FlashSeatsException gatewayUnavailable() {
        return new FlashSeatsException(
                        ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                        "The payment provider is having trouble. Your seats are still held — please retry.")
                .with("retryable", true)
                .with("retryAfterSeconds", 5);
    }

    /**
     * The webhook body is not signed with this deployment's secret. The signature is the only thing
     * guarding an unauthenticated endpoint. {@code 400}, not retryable: a caller who cannot sign
     * will not do better on the next attempt.
     */
    public static FlashSeatsException webhookSignatureInvalid(Throwable cause) {
        return new FlashSeatsException(
                        ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Webhook signature verification failed.", cause)
                .with("retryable", false);
    }
}
