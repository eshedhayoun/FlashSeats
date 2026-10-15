package com.flashseats.catalog.model;

/** Publication state of an event. Only {@link #PUBLISHED} events can have an open sale window. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    /**
     * Selling is halted by an operator, and can be resumed. Inside the sale window it reads as the
     * {@code PAUSED} window status, so buyers are told the sale is paused rather than over (ADR-066).
     * Drift and the restart guard still watch a paused event (ADR-048).
     */
    PAUSED,
    CANCELLED
}
