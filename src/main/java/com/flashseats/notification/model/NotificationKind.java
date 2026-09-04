package com.flashseats.notification.model;

/**
 * What was sent.
 *
 * <p>Part of the uniqueness constraint, which is what allows one order to legitimately receive both
 * a ticket and — on the refund path — a separate notice, while still preventing two of either.
 */
public enum NotificationKind {
    TICKET_DELIVERY,
    REFUND_NOTICE
}
