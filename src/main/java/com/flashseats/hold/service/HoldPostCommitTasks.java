package com.flashseats.hold.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.hold.event.TicketHoldSettledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Returns a settled hold's seats to the live counter, once the claim that won them has committed.
 *
 * <p><strong>Why it is not done inline.</strong> The claim is a SQL {@code UPDATE} and the counter
 * is in Redis. A rollback undoes the first and not the second, so an inline increment would put
 * seats back on sale while the hold that owns them returns to {@code ACTIVE} — an oversell, and the
 * one outcome this design never accepts. Waiting for the commit inverts the risk: if this never
 * runs, the seats are merely invisible, which {@code flashseats.stock.drift} reports and a rebuild
 * repairs.
 */
@Slf4j
@Component
class HoldPostCommitTasks {

    private final CatalogFacade catalog;

    HoldPostCommitTasks(CatalogFacade catalog) {
        this.catalog = catalog;
    }

    /**
     * {@code fallbackExecution} is not decoration: without it, a settle that ever runs outside a
     * transaction would publish this event into nothing and lose its restore silently — no error, no
     * log, just seats that stop existing.
     *
     * <p>This method must never throw. Spring invokes after-commit synchronizations in a loop with
     * no {@code try/catch} of its own, so one failed increment would abandon every event still
     * queued behind it — and {@code sweepExpired} can queue a full batch of them from a single
     * transaction.
     *
     * <p>It must also stay synchronous. A caller that releases a hold and immediately reads the
     * counter — which is exactly what the journey tests do — would otherwise race the restore.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onHoldSettled(TicketHoldSettledEvent event) {
        if (!event.claimWon()) {
            return;
        }
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
