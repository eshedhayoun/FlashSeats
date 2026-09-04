package com.flashseats.order.service;

import com.flashseats.order.repository.OrderRepository;
import org.springframework.stereotype.Component;

/**
 * Mints human-facing order numbers such as {@code TK-00001}.
 *
 * <p>Backed by a database sequence, not an in-memory counter: three replicas each holding their own
 * counter would issue the same number to different buyers, and {@code UNIQUE(order_number)} would
 * turn that into a failed checkout for whoever came second.
 *
 * <p>Short and readable because a buyer reads it aloud to support. It is <strong>not</strong> a
 * secret — reading an order requires a matching session cookie or a signed receipt token (ADR-010).
 */
@Component
public class OrderNumbers {

    private static final String PREFIX = "TK-";

    private final OrderRepository orders;

    public OrderNumbers(OrderRepository orders) {
        this.orders = orders;
    }

    public String next() {
        return PREFIX + "%05d".formatted(orders.nextOrderNumberValue());
    }
}
