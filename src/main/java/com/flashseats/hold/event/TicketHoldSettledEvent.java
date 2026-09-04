package com.flashseats.hold.event;

import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import java.time.Instant;

/**
 * A hold reached a terminal state, and <em>this</em> caller is the one that won the settle-once
 * claim. Published at most once per hold, however many callers raced for it.
 *
 * <p>Monitoring only. Stock restoration has already happened by the time this fires — it is not a
 * trigger for it.
 */
public record TicketHoldSettledEvent(
        String holdToken,
        long eventId,
        long tierId,
        int quantity,
        HoldStatus status,
        SettleReason reason,
        boolean stockRestored,
        Instant at) {}
