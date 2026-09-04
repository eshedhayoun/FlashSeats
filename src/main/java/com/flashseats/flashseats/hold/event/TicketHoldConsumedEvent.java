package com.flashseats.flashseats.hold.event;

import java.time.Instant;

public record TicketHoldConsumedEvent(
        String holdToken,
        Long eventId,
        Long tierId,
        Integer quantity,
        Instant timestamp
) {
}
