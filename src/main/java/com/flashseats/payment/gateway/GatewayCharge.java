package com.flashseats.payment.gateway;

/**
 * One charge request.
 *
 * <p>{@code amountCents} is computed by {@code order} from the tier's price. No client-supplied
 * value ever reaches this record (ADR-013).
 *
 * <p>{@code clientIdempotencyKey} is forwarded to the provider as its own idempotency header and is
 * used for nothing else — it is a convenience for network-level retries, never the guarantee. The
 * guarantee is {@code UNIQUE(hold_token)} on {@code orders} (ADR-014).
 */
public record GatewayCharge(
        String orderNumber,
        long amountCents,
        String currency,
        String paymentMethodId,
        String clientIdempotencyKey) {}
