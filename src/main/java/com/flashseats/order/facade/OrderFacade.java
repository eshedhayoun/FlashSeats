package com.flashseats.order.facade;

import java.util.Optional;

/**
 * The only legal way into {@code order} from another module. Read-only.
 *
 * <p>Its one caller is {@code saleflow}, which needs to tell a reloading client that a payment is
 * still in flight. Nothing outside this module may start, confirm or alter a purchase — there is
 * exactly one checkout entry point and it is an HTTP endpoint, not a facade method.
 */
public interface OrderFacade {

    Optional<OrderSummary> getOrderSummary(String orderNumber);

    /** This session's payment in progress for an event, if any. */
    Optional<OrderSummary> findPendingOrder(String userSessionId, long eventId);
}
