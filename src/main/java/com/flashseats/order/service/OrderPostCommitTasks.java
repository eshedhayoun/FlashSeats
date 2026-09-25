package com.flashseats.order.service;

import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.queue.facade.QueueFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Side effects kept out of the order transaction, all <strong>best-effort and safe to lose</strong>
 * (ADR-023): the hold is already {@code CONSUMED}, and the admission lapses on its own. Failures are
 * logged and swallowed; the transaction has already committed.
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

    /**
     * <strong>One {@code try} each, not one around both.</strong> They shared a block while
     * {@code discardTimer} was a no-op that could not fail. It is a Redis {@code DEL} now, so a
     * single block would let an unreachable Redis skip {@code revokeAdmission} — quietly leaving a
     * buyer who has finished their purchase holding an admission that denies someone else theirs.
     * Two independent best-effort cleanups must fail independently.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        try {
            holds.discardTimer(event.holdToken());
        } catch (RuntimeException cleanupFailed) {
            log.warn("Could not discard the hold timer for order {}", event.orderNumber(), cleanupFailed);
        }

        try {
            // The buyer has what they came for. Keeping their place in the sale would deny it to
            // someone still waiting.
            queue.revokeAdmission(event.userSessionId(), event.eventId());
        } catch (RuntimeException cleanupFailed) {
            log.warn("Could not revoke admission for order {}", event.orderNumber(), cleanupFailed);
        }
    }
}
