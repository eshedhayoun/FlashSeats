package com.flashseats.order.facade;

import java.util.Optional;

/**
 * The only legal way into {@code order} from another module. Read-only.
 *
 * <p>Its one caller is {@code saleflow}, which needs to tell a reloading client where this buyer
 * stands. Nothing outside this module may start, confirm or alter a purchase — there is exactly one
 * checkout entry point and it is an HTTP endpoint, not a facade method.
 */
public interface OrderFacade {

    Optional<OrderSummary> getOrderSummary(String orderNumber);

    /**
     * This session's most recent order for an event, <strong>whatever its status</strong>.
     *
     * <p>It returned only payments in flight, and that was the defect (ADR-037): a completed
     * purchase vanished from rehydration the instant it succeeded, so reloading a receipt sent the
     * buyer back to the landing page. The caller decides what a given status means for the screen
     * it draws; this method's job is to report the fact.
     */
    Optional<OrderSummary> findLatestOrder(String userSessionId, long eventId);
}
