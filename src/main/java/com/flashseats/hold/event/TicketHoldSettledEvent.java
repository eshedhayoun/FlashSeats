package com.flashseats.hold.event;

import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import java.time.Instant;

/**
 * A hold reached a terminal state, and <em>this</em> caller is the one that won the settle-once
 * claim. Published at most once per hold, however many callers raced for it.
 *
 * <p><strong>This is the trigger for stock restoration, not a record of it.</strong> It used to be
 * the other way round — the seats went back inline and this said so afterwards — but the counter
 * moved to Redis, which cannot roll back with the claim that justified it. The increment now waits
 * for the commit, in {@link com.flashseats.hold.service.HoldPostCommitTasks}, and this event is how
 * it learns there is something to return.
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
