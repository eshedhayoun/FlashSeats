package com.flashseats.order.controller;

import com.flashseats.order.dto.AdminOrderResponse;
import com.flashseats.order.service.NotificationResendService;
import com.flashseats.order.service.OrderQueryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two things support needs: see an order, and send its ticket again.
 *
 * <p>Both are served from {@code order} because that is where the state is (ADR-043) — the order
 * itself, and the {@code outbox_events} payload a resend replays. The resend's <em>path</em> names
 * notifications, because that is what the operator is thinking about; the same split
 * {@code AdminStockController} makes, for the same reason.
 *
 * <p>Guarded by {@code ROLE_ADMIN} in {@link com.flashseats.app.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrderController {

    private final OrderQueryService orders;
    private final NotificationResendService resends;

    public AdminOrderController(OrderQueryService orders, NotificationResendService resends) {
        this.orders = orders;
        this.resends = resends;
    }

    /**
     * "Where is my ticket?" — the order, its payment history, and the hold it came from.
     *
     * <p>Returns {@link AdminOrderResponse} rather than the buyer's receipt, because the receipt
     * carries a token that authorises reading the order from anywhere for ninety days and an
     * operator has no need of one.
     */
    @GetMapping("/orders/{orderNumber}")
    public AdminOrderResponse order(@PathVariable String orderNumber) {
        return orders.readForOperator(orderNumber);
    }

    /**
     * Queues the order's ticket email again: the replay trigger for ADR-038's re-claimable dead
     * letters. Safe to press when unsure: a {@code SENT} notification is never re-claimed, so the
     * answer is "queued", not "delivered".
     */
    @PostMapping("/notifications/resend/{orderNumber}")
    public Map<String, Object> resend(@PathVariable String orderNumber) {
        return Map.of(
                "orderNumber", orderNumber,
                "outboxEventId", resends.resendTicket(orderNumber),
                "queued", true);
    }
}
