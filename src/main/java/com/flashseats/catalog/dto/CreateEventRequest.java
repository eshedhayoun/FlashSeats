package com.flashseats.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * An operator's new sale: the event and every tier, in one request (ADR-080).
 *
 * <p>All at once because a tier-less event reads as unknowable stock, and an event whose tiers arrive
 * later would be published without them. There is no update: once published, price and capacity are
 * what checkout and the stock counters were built from. Currency is not here — every sale is USD.
 *
 * <p>The size limits are the columns they land in; the cross-field rules (sale start far enough
 * ahead, end after start) need the clock and are checked in the service.
 */
public record CreateEventRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 255) String venueName,
        @NotNull Instant eventStartTime,
        @NotNull Instant saleStartTime,
        @NotNull Instant saleEndTime,
        @NotEmpty @Size(max = 10) List<@NotNull @Valid Tier> tiers) {

    /**
     * Boxed, so a missing field is refused rather than read as {@code 0}. The price floor is Stripe's
     * USD minimum: below it every charge is refused as an invalid request, which the gateway counts
     * against the breaker shared by every sale (ADR-052). The quantity ceiling is the hold's own
     * limit, so the number an operator sets is the number a buyer gets (ADR-017).
     */
    public record Tier(
            @NotBlank @Size(max = 100) String tierName,
            @NotNull @Min(50) @Max(10_000_000) Long priceCents,
            @NotNull @Min(1) @Max(100_000) Integer totalCapacity,
            @NotNull @Min(1) @Max(6) Integer maxPerOrder) {}
}
