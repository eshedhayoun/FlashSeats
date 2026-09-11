package com.flashseats.notification.dto;

import com.flashseats.notification.model.NotificationKind;
import java.time.Instant;

/**
 * One undelivered notification, as an operator needs to see it.
 *
 * <p>{@code failureReason} is the field this exists for. ADR-029 sends deterministic failures
 * straight here with no retries — a PDF that cannot be rendered will fail identically three times,
 * so retrying only delays the queue — which makes the stored reason the only account of what went
 * wrong. Pass 2 found a real one: a standard-14 font throwing on a Hebrew event title, which cost a
 * <strong>paid</strong> buyer their ticket.
 *
 * <p>{@code recipientEmail} is included deliberately. This endpoint is already behind
 * {@code ROLE_ADMIN}, and an operator who cannot see who was affected cannot tell them.
 */
public record DeadLetterResponse(
        String orderNumber,
        NotificationKind kind,
        String recipientEmail,
        int retryCount,
        String failureReason,
        Instant failedAt) {}
