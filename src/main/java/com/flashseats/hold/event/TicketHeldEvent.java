package com.flashseats.hold.event;

import java.time.Instant;

/**
 * Seats were reserved.
 *
 * <p>{@code expiresAt} is why this is no longer monitoring-only: it is what
 * {@code HoldPostCommitTasks} arms the {@code hold:{token}} timer against. It is carried on the
 * event rather than recomputed from {@code flashseats.hold.ttl-seconds} because the two can
 * legitimately differ — {@code grantGrace} moves a hold's expiry — and a timer derived from the
 * property would fire against the wrong instant the moment it did.
 *
 * <p>Losing this event costs latency and nothing else: no timer is armed, and the sweeper reclaims
 * the hold on its next pass exactly as it always has.
 */
public record TicketHeldEvent(
        String holdToken,
        String userSessionId,
        long eventId,
        long tierId,
        int quantity,
        Instant expiresAt,
        Instant at) {}
