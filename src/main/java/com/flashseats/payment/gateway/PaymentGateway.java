package com.flashseats.payment.gateway;

/**
 * The external payment provider, behind one interface.
 *
 * <p>The MVP ships {@link StubPaymentGateway}. Stripe arrives as a second implementation of exactly
 * this interface — no call site above it changes, which is the point of introducing the seam before
 * it is needed rather than after.
 *
 * <p>Implementations must <strong>never</strong> be called inside a transaction: a network round
 * trip holding a pooled connection throttles checkout for everyone, because under virtual threads
 * the connection pool is the system's real concurrency limit (ADR-023).
 */
public interface PaymentGateway {

    GatewayResult charge(GatewayCharge charge);

    /** Compensation when a charge settles but the seats cannot be delivered (ADR-012). */
    GatewayResult refund(String gatewayReference, long amountCents, String reason);
}
