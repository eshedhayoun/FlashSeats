package com.flashseats.hold.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims abandoned reservations on a fixed cadence. <strong>This is the correctness guarantee
 * for expiry</strong>; {@link HoldExpiryListener} only makes it faster. It runs on every replica
 * safely, because each row is taken by the settle-once claim (global standards §7). It uses
 * {@code fixedDelay}, so a slow pass never stacks behind itself.
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
