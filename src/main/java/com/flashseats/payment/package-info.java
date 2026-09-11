/**
 * The payment gateway boundary.
 *
 * <p>Isolates charging and refunding behind one interface, enforces idempotency, and keeps a durable
 * transaction ledger.
 *
 * <p><strong>This module calls no facade at all</strong>, and that is load-bearing rather than
 * incidental. The webhook path already runs {@code payment → order} as an event, so any synchronous
 * edge out of here would close a cycle and fail the build (ADR-005). Grace extension is requested by
 * {@code order}; a decline deliberately retains the hold; abandonment is handled by expiry.
 *
 * <p>MVP scope: a stub gateway behind the final interface, in the final position in the checkout
 * sequence. Stripe, webhooks, 3-D Secure and the circuit breaker are additive — they replace one
 * implementation of {@link com.flashseats.payment.gateway.PaymentGateway} and touch nothing else.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment")
package com.flashseats.payment;
