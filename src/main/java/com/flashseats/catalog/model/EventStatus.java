package com.flashseats.catalog.model;

/** Publication state of an event. Only {@link #PUBLISHED} events can have an open sale window. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    /**
     * Selling is halted by an operator, and can be resumed. A publication state, not a fourth window
     * status: every gate already reads non-{@code PUBLISHED} as {@code CLOSED}, so pause needs no new
     * handling anywhere. Drift and the restart guard still watch a paused event (ADR-048).
     */
    PAUSED,
    CANCELLED
}
