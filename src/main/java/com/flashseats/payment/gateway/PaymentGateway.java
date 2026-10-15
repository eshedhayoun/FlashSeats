package com.flashseats.payment.gateway;

/**
 * The external payment provider. {@link StripePaymentGateway} when
 * {@code flashseats.payment.stripe.enabled}, otherwise {@link StubPaymentGateway}, which drives every
 * checkout branch, 3-D Secure included, with no keys. Both are wrapped by {@link CircuitBreakingGateway}.
 *
 * <p>Never call it inside a transaction (ADR-023). Signal transport failure by throwing
 * {@link GatewayTransportException}, never by returning an error: that is what the breaker counts.
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
