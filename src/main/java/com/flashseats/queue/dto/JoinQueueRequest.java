package com.flashseats.queue.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A request to enter the waiting room.
 *
 * <p>{@code recaptchaToken} is now read, by {@code bot} (ADR-055). It stays optional: the documented
 * behaviour when verification is unavailable — no provider configured, no token sent, a provider
 * that times out — is to <strong>fail open</strong> and rely on rate limits (ADR-011). Making it
 * required would turn a third party's outage into a closed sale.
 */
public record JoinQueueRequest(@NotNull Long eventId, String recaptchaToken) {}
