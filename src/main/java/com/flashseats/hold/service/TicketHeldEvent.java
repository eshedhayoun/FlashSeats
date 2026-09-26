package com.flashseats.hold.service;

import java.time.Instant;

/**
 * Seats were reserved. {@code expiresAt} is what {@code HoldPostCommitTasks} arms the
 * {@code hold:{token}} timer against. It is carried rather than recomputed, because
 * {@code grantGrace} can move it. Losing this event costs only latency: the sweeper still reclaims.
 */
public record TicketHeldEvent(
        String holdToken,
        String userSessionId,
        long eventId,
        long tierId,
        int quantity,
        Instant expiresAt,
        Instant at) {}
