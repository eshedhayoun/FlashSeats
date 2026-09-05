package com.flashseats.order.service;

import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.order.event.OrderConfirmedEvent;
import com.flashseats.queue.facade.QueueFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Side effects that must not be inside the order transaction.
 *
 * <p>Everything here is <strong>best-effort and safe to lose</strong>, which is the condition for
 * living in {@code AFTER_COMMIT} at all (ADR-023). If none of it runs, the system is still correct:
 * the hold is already {@code CONSUMED}, so its timer expiring changes nothing, and the buyer's
 * admission lapses on its own.
 *
 * <p>Failures are logged and swallowed deliberately. Throwing here would achieve nothing — the
 * transaction has already committed and the buyer already has their tickets.
 */
@Slf4j
@Component
public class OrderPostCommitTasks {

    private final HoldFacade holds;
    private final QueueFacade queue;

    public OrderPostCommitTasks(HoldFacade holds, QueueFacade queue) {
        this.holds = holds;
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        try {
            holds.discardTimer(event.holdToken());
            // The buyer has what they came for. Keeping their place in the sale would deny it to
            // someone still waiting.
            queue.revokeAdmission(event.userSessionId(), event.eventId());
        } catch (RuntimeException cleanupFailed) {
            log.warn("Post-commit cleanup failed for order {}", event.orderNumber(), cleanupFailed);
        }
    }
}
