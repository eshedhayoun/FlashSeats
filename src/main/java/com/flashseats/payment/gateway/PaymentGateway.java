package com.flashseats.payment.gateway;

/**
 * The external payment provider, behind one interface.
 *
 * <p>Two implementations ship: {@link StripePaymentGateway} when
 * {@code flashseats.payment.stripe.enabled} is set, and {@link StubPaymentGateway} otherwise — which
 * is what lets {@code dev}, {@code test} and the load harness drive every branch of the checkout
 * sequence, including 3-D Secure, with no network and no keys. Both are wrapped by
 * {@link CircuitBreakingGateway}.
 *
 * <p>Implementations must <strong>never</strong> be called inside a transaction: a network round
 * trip holding a pooled connection throttles checkout for everyone, because under virtual threads
 * the connection pool is the system's real concurrency limit (ADR-023).
 *
 * <p>Implementations signal a transport failure by throwing {@link GatewayTransportException}, never
 * by returning {@link GatewayResult#error}. The distinction is what the circuit breaker counts.
 */
public interface PaymentGateway {

    GatewayResult charge(GatewayCharge charge);

    /**
     * Re-reads an intent the buyer was sent away to authenticate (3-D Secure).
     *
     * <p><strong>The resume path must retrieve, never re-charge.</strong> The client mints one
     * idempotency key per hold and reuses it on every retry (FE_SPEC §1), so a second
     * {@link #charge} would replay the provider's cached {@code requires_action} response for ever;
     * varying the key per attempt instead would create a <em>second</em> intent and risk billing the
     * buyer twice for one authentication.
     */
    GatewayResult retrieve(String gatewayReference);

    /** Compensation when a charge settles but the seats cannot be delivered (ADR-012). */
    GatewayResult refund(String gatewayReference, long amountCents, String reason);
}
