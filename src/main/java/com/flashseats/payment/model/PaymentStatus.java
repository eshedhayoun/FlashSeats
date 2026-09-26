package com.flashseats.payment.model;

/**
 * Gateway transaction lifecycle, internal to this module.
 *
 * <pre>
 *   INITIATED ──► PROCESSING ──► SUCCEEDED ──► REFUNDED
 *                      └───────► FAILED
 * </pre>
 *
 * <p>{@code PROCESSING} parks a 3-D Secure charge so a resumed checkout re-reads that intent rather
 * than opening a second one (ADR-054).
 */
public enum PaymentStatus {
    INITIATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
