package com.flashseats.catalog.model;

/** Publication state of an event. Only {@link #PUBLISHED} events can have an open sale window. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
