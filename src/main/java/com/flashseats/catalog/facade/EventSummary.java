package com.flashseats.catalog.facade;

import java.time.Instant;

/** Event-level facts, for rehydration and for the queue's window gate. */
public record EventSummary(
        long eventId,
        String title,
        String venueName,
        Instant eventStartTime,
        Instant saleStartTime,
        Instant saleEndTime,
        EventWindowStatus windowStatus) {}
