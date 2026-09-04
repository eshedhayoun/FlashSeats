package com.flashseats.order.model;

/**
 * {@code PENDING → PROCESSING → PROCESSED}.
 *
 * <p>A row stuck in {@code PROCESSING} means a relay died between claiming it and publishing it. The
 * stale-claim sweep returns those to {@code PENDING}, so delivery is at-least-once — which the
 * consumer's unique constraint absorbs (ADR-023).
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PROCESSED
}
