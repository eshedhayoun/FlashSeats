package com.flashseats.flashseats.hold.event;

import java.time.Instant;

public record TicketHoldExpiredEvent(
        String holdToken,
        Long eventId,
        Long tierId,
        Integer quantityRestored,
        Instant timestamp
) {
}
