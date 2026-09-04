package com.flashseats.payment.facade;

/**
 * Everything needed to attempt one charge.
 *
 * <p>A record rather than a seven-argument method, because several of those arguments are
 * same-typed strings and a transposed pair would be silent and expensive.
 *
 * <p>{@code amountCents} is derived server-side by {@code order} from the tier price — no client
 * value contributes to it (ADR-013).
 */
public record AuthorizeCommand(
        String orderNumber,
        String holdToken,
        String userSessionId,
        long amountCents,
        String currency,
        String paymentMethodId,
        String clientIdempotencyKey,
        int attemptNumber) {}
