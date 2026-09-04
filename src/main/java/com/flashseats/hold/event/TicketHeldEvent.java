package com.flashseats.hold.event;

import java.time.Instant;

/** Seats were reserved. Monitoring only — nothing in the flow depends on this being delivered. */
public record TicketHeldEvent(
        String holdToken, String userSessionId, long eventId, long tierId, int quantity, Instant at) {}
