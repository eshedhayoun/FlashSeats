package com.flashseats.hold.dto;

import com.flashseats.hold.model.TicketHold;
import java.time.Duration;
import java.time.Instant;

/**
 * A reservation as the client sees it.
 *
 * <p>Both {@code expiresAt} and {@code serverTime} are present so the countdown can be computed as
 * {@code expiresAt - (serverTime + elapsedLocalTime)}. A client that counted down against its own
 * clock would show a buyer with a fast device a reservation that expires the moment it is created.
 *
 * <p>{@code ttlRemainingSeconds} is a convenience for the first paint; it is stale the instant it is
 * serialised, and the client must re-derive from the two timestamps thereafter.
 */
public record HoldResponse(
        String holdToken,
        long eventId,
        long tierId,
        int quantity,
        Instant expiresAt,
        long ttlRemainingSeconds,
        Instant serverTime) {

    public static HoldResponse of(TicketHold hold, Instant now) {
        long remaining = Math.max(0, Duration.between(now, hold.getExpiresAt()).toSeconds());
        return new HoldResponse(
                hold.getHoldToken(),
                hold.getEventId(),
                hold.getTierId(),
                hold.getQuantity(),
                hold.getExpiresAt(),
                remaining,
                now);
    }
}
