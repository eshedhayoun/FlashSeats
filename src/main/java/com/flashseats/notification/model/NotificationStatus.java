package com.flashseats.notification.model;

/**
 * {@code PENDING → SENT}, or {@code DLQ} when delivery could not be completed.
 *
 * <p>There is no {@code FAILED}. One was declared and never assigned, and the distinction it implied
 * would have been dangerous: {@code DLQ} is re-claimable by design, so a second terminal-looking
 * state that a replay did not recognise is how a buyer gets two tickets, or none (ADR-042).
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    DLQ
}
