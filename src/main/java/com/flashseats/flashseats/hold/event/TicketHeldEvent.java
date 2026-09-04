package com.flashseats.flashseats.hold.event;

import java.time.Instant;

public record TicketHeldEvent(
        String holdToken,
        String userSessionId,
        Long eventId,
        Long tierId,
        Integer quantity,
        Instant expiresAt,
        Instant timestamp
) {
}
