package com.flashseats.hold.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims abandoned reservations on a fixed cadence.
 *
 * <p><strong>This is the correctness guarantee for expiry</strong>, not an optimisation. Later
 * phases add a Redis keyspace listener that reclaims a hold within milliseconds instead of seconds,
 * but keyspace notifications are at-most-once pub/sub — one dropped connection and that hold's seats
 * would never come back. The sweeper cannot miss.
 *
 * <p>Runs on every replica. That is safe, and needs no coordination: each row is taken by the
 * settle-once claim, so concurrent sweepers simply lose the race and do nothing (global standards
 * §7 — a scheduled job must be idempotent by claim or singleton by lock; this one is the former).
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a slow pass under a large expiry burst must not
 * stack up behind itself.
 */
@Component
public class HoldReconciliationSweeper {

    private final HoldService holds;

    public HoldReconciliationSweeper(HoldService holds) {
        this.holds = holds;
    }

    @Scheduled(
            fixedDelayString = "${flashseats.hold.sweeper-interval-ms}",
            initialDelayString = "${flashseats.hold.sweeper-interval-ms}")
    public void sweep() {
        holds.sweepExpired();
    }
}
