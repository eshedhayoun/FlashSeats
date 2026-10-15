package com.flashseats.payment.event;

/**
 * A charge settled at the provider, reported by the webhook, for the case the synchronous path
 * cannot cover: the buyer never saw the response.
 *
 * <p><strong>The one cross-module event</strong> (ADR-005). It runs {@code payment → order} at
 * runtime while the type dependency runs {@code order → payment}, so {@code payment} needs no facade
 * and the graph stays acyclic. {@code holdToken} is how settlement finds the order (ADR-014).
 * {@code amountCents} sizes the refund only; {@code order} prices from the tier (ADR-013).
 */
public record PaymentSettledEvent(
        String holdToken,
        String gatewayReference,
        String transactionReference,
        long amountCents,
        String currency) {}
