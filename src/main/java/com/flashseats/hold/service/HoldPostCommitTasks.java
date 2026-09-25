package com.flashseats.hold.service;

import com.flashseats.catalog.facade.CatalogFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Returns a settled hold's seats to the live counter once the claim that won them has committed.
 * Inline, a rollback would undo the claim but not the Redis increment, which is an oversell. After
 * commit, the worst case is invisible seats that drift reports and a rebuild repairs (ADR-046).
 */
@Slf4j
@Component
class HoldPostCommitTasks {

    private final CatalogFacade catalog;
    private final HoldTimers timers;

    HoldPostCommitTasks(CatalogFacade catalog, HoldTimers timers) {
        this.catalog = catalog;
        this.timers = timers;
    }

    /**
     * Arms the expiry timer once the hold row it describes actually exists.
     *
     * <p>After the commit for the same reason the restore below is: Redis does not roll back, so a
     * timer armed inline would outlive a hold whose row never committed and fire against nothing.
     * The direction of failure is the safe one either way — an unarmed timer costs seconds, and the
     * sweeper is what guarantees the seats come back at all.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTicketHeld(TicketHeldEvent event) {
        timers.arm(event.holdToken(), event.expiresAt());
    }

    /**
     * {@code fallbackExecution} so a settle outside a transaction still restores rather than vanishing.
     * Must never throw: after-commit callbacks run in a loop with no {@code try/catch}, and one failure
     * would abandon every restore queued behind it in a sweep batch. Synchronous, so a release is
     * visible to the next read.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onHoldSettled(TicketHoldSettledEvent event) {
        if (!event.claimWon()) {
            return;
        }
        // The hold is over, so its timer has nothing left to accelerate. Disarmed first and on its
        // own, because HoldTimers swallows its failures and the restore below must not be reached
        // through anything that could throw past it.
        timers.disarm(event.holdToken());
        try {
            catalog.restore(event.eventId(), event.tierId(), event.quantity());
        } catch (RuntimeException restoreFailed) {
            log.error(
                    "Could not return {} seats from hold {} to tier {}; they stay invisible until a"
                            + " rebuild runs",
                    event.quantity(),
                    event.holdToken(),
                    event.tierId(),
                    restoreFailed);
        }
    }
}
