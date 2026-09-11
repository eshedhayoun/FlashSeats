package com.flashseats.queue.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A request to enter the waiting room.
 *
 * <p>{@code recaptchaToken} is accepted and currently unused: bot verification is deferred, and the
 * documented behaviour when verification is unavailable is to <strong>fail open</strong> and rely on
 * rate limits (ADR-011). Keeping the field means the client contract does not change when
 * verification is switched on.
 */
public record JoinQueueRequest(@NotNull Long eventId, String recaptchaToken) {}
