package com.flashseats.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * A request to buy the seats held under {@code holdToken}.
 *
 * <p>Note what is absent: any amount, and any session id. The price is computed server-side from the
 * tier (ADR-013) and identity comes from the signed cookie (ADR-010). A client that sent either
 * would be ignored.
 *
 * <p>{@code userEmail} is collected here and nowhere else — it is where the tickets go, so a typo
 * has no recovery path and the client should show it back on the receipt.
 *
 * <p>{@code idempotencyKey} is forwarded to the payment provider and used for nothing else. It must
 * be generated <strong>once per hold</strong> and reused across retries; regenerating it per attempt
 * defeats the provider-level guard. It is not the guarantee — {@code UNIQUE(hold_token)} is.
 */
public record CheckoutRequest(
        @NotBlank String holdToken,
        @NotBlank @Email String userEmail,
        @NotBlank String paymentMethodId,
        String idempotencyKey) {}
