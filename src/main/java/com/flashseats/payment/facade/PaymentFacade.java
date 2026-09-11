package com.flashseats.payment.facade;

/**
 * The only legal way into {@code payment}. Its sole caller is {@code order}.
 *
 * <p><strong>Never call these inside a transaction.</strong> Both make a network round trip to an
 * external provider; holding a pooled connection across one throttles checkout for every other buyer,
 * because under virtual threads the connection pool — not the thread count — is the system's real
 * concurrency limit (ADR-023).
 */
public interface PaymentFacade {

    /**
     * Attempts one charge.
     *
     * <p>Returns rather than throws for a decline: a refused card is a <em>correct answer</em> the
     * caller must act on (keep the hold, let the buyer retry), not an exceptional condition. Only
     * genuine faults — a duplicate charge already in flight, or an unreachable provider — are thrown.
     *
     * @throws com.flashseats.payment.exception.DuplicatePaymentException if a charge for this hold is
     *     already in flight
     * @throws com.flashseats.payment.exception.PaymentGatewayUnavailableException if the provider
     *     could not be reached. The buyer's seats are retained.
     */
    PaymentResult authorize(AuthorizeCommand command);

    /**
     * Refunds a settled charge. Used when the money moved but the seats cannot be delivered — the
     * commit failed, or a concurrent expiry gave the seats away (ADR-012).
     */
    RefundResult refund(String transactionReference, long amountCents, String reason);
}
