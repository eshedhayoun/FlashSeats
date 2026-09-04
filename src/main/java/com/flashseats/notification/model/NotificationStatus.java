package com.flashseats.notification.model;

/** {@code PENDING → SENT}, or {@code FAILED}/{@code DLQ} when delivery could not be completed. */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    DLQ
}
