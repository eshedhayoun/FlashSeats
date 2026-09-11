package com.flashseats.order.facade;

import com.flashseats.order.model.Order;
import com.flashseats.order.service.OrderQueryService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link OrderQueryService}. Package-private. */
@Component
class OrderFacadeImpl implements OrderFacade {

    private final OrderQueryService orders;

    OrderFacadeImpl(OrderQueryService orders) {
        this.orders = orders;
    }

    @Override
    public Optional<OrderSummary> getOrderSummary(String orderNumber) {
        return orders.findByOrderNumber(orderNumber).map(OrderFacadeImpl::toSummary);
    }

    @Override
    public Optional<OrderSummary> findLatestOrder(String userSessionId, long eventId) {
        return orders.findLatest(userSessionId, eventId).map(OrderFacadeImpl::toSummary);
    }

    /** The status crosses as a string: {@code OrderStatus} is this module's business, not a shared type. */
    private static OrderSummary toSummary(Order order) {
        return new OrderSummary(
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getEventId(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
