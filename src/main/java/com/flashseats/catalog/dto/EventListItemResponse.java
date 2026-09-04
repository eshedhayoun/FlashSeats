package com.flashseats.catalog.dto;

import com.flashseats.catalog.facade.EventWindowStatus;
import java.time.Instant;

/** One row of the event index. */
public record EventListItemResponse(
        long eventId,
        String title,
        String venueName,
        Instant eventStartTime,
        Instant saleStartTime,
        EventWindowStatus windowStatus) {}
