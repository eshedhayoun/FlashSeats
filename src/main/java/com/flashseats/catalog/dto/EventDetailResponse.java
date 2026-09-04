package com.flashseats.catalog.dto;

import com.flashseats.catalog.facade.EventWindowStatus;
import java.time.Instant;
import java.util.List;

/**
 * The landing-page payload.
 *
 * <p>{@code serverTime} is not decoration. Every countdown in the client is computed as
 * {@code serverTime + elapsedLocalTime}, never from the device clock — without it, a user whose
 * laptop is four minutes fast watches a reservation expire the instant it is created, and the
 * fairness of queue ordering becomes a claim nobody can keep (ADR-016).
 */
public record EventDetailResponse(
        long eventId,
        String title,
        String description,
        String venueName,
        Instant eventStartTime,
        Instant saleStartTime,
        Instant saleEndTime,
        EventWindowStatus windowStatus,
        Instant serverTime,
        List<TierResponse> tiers) {}
