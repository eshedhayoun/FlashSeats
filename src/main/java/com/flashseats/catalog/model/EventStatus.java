package com.flashseats.catalog.model;

/** Publication state of an event. Only {@link #PUBLISHED} events can have an open sale window. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    /**
     * Selling is halted by an operator, and can be resumed.
     *
     * <p><strong>It closes the sale for free.</strong> {@code SaleWindows.statusOf} already answers
     * {@code CLOSED} for anything that is not {@code PUBLISHED}, so every gate that consults the
     * window — the queue join, the hold, the checkout — refuses without a line of new code. That is
     * the whole reason pause is a publication state rather than a fourth window status: a new
     * {@code EventWindowStatus} would have to be handled correctly by every consumer, and the one
     * that forgot would be a sale still selling while an operator believed it stopped.
     *
     * <p>What it deliberately does <strong>not</strong> stop is measurement. A paused event is still
     * drift-checked and still covered by the Redis-restart guard, because pausing is exactly what an
     * operator does while investigating a counter — see {@code EventRepository.findManagedEventIds}.
     */
    PAUSED,
    CANCELLED
}
