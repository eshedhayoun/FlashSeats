package com.flashseats.flashseats.hold.event;

import java.time.Instant;

public record TicketHoldReleasedEvent(
        String holdToken,
        Long eventId,
        Long tierId,
        Integer quantityRestored,
        String reason,
        Instant timestamp
) {
}
