package com.flashseats.hold.service;

import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import java.time.Instant;

/**
 * A hold reached a terminal state, and <em>this</em> caller won the settle-once claim, so it is
 * published at most once per hold. It is the <strong>trigger</strong> for restoring stock:
 * {@link HoldPostCommitTasks} increments Redis only after the claim commits (ADR-046).
 */
public record TicketHoldSettledEvent(
        String holdToken,
        long eventId,
        long tierId,
        int quantity,
        HoldStatus status,
        SettleReason reason,
        /** True for the one caller that took the hold out of {@code ACTIVE}; it owes the seats. */
        boolean claimWon,
        Instant at) {}
