package com.flashseats.notification.dto;

import com.flashseats.notification.model.NotificationKind;
import java.time.Instant;

/**
 * One undelivered notification, as an operator needs to see it. {@code failureReason} is the only
 * account of what went wrong, since deterministic failures are dead-lettered without retries
 * (ADR-029). {@code recipientEmail} is included because the endpoint is admin-only and an operator
 * must be able to tell the buyer.
 */
public record DeadLetterResponse(
        String orderNumber,
        NotificationKind kind,
        String recipientEmail,
        int retryCount,
        String failureReason,
        Instant failedAt) {}
