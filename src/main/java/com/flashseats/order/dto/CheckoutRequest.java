package com.flashseats.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 *
 * <p>The size limits are the columns they land in. Unbounded, an oversized key failed its insert on
 * every retry as a {@code 500}, and an oversized email was misread as a concurrent checkout and
 * answered {@code 409 DUPLICATE_PAYMENT}, which a client polls for ever (ADR-067).
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 64) String holdToken,
        @NotBlank @Email @Size(max = 255) String userEmail,
        @NotBlank @Size(max = 255) String paymentMethodId,
        @NotBlank @Size(max = 64) String idempotencyKey) {}
