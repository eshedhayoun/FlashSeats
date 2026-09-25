package com.flashseats.payment.gateway;

/**
 * One charge request. {@code amountCents} is computed server-side from the tier (ADR-013).
 * {@code holdToken} travels as provider metadata so the webhook can find the order when the HTTP
 * response was lost (ADR-014). {@code clientIdempotencyKey} only dedupes network retries; the
 * guarantee is {@code UNIQUE(hold_token)} on {@code orders}.
 */
public record GatewayCharge(
        String orderNumber,
        String holdToken,
        long amountCents,
        String currency,
        String paymentMethodId,
        String clientIdempotencyKey) {}
