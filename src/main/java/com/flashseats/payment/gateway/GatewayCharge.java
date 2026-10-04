package com.flashseats.payment.gateway;

/**
 * One charge request. {@code amountCents} is computed server-side from the tier (ADR-013).
 * {@code holdToken} travels as provider metadata so the webhook can find the order when the HTTP
 * response was lost (ADR-014). {@code idempotencyKey} is the provider's: the client's key for the
 * hold, scoped to the attempt (ADR-074). It only dedupes retries of one attempt; the guarantee is
 * {@code UNIQUE(hold_token)} on {@code orders}.
 */
public record GatewayCharge(
        String orderNumber,
        String holdToken,
        long amountCents,
        String currency,
        String paymentMethodId,
        String idempotencyKey) {}
