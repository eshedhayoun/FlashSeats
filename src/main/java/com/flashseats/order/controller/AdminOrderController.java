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
 * <p>Guarded by {@code ROLE_ADMIN} in {@link com.flashseats.flashseats.config.SecurityConfig}.
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
     * Queues the order's ticket email again.
     *
     * <p>This is the trigger ADR-038 built for and never had. A dead-lettered notification is
     * re-claimable precisely so a replay sends; without something to start one, a deterministic
     * render failure — a font that cannot draw a Hebrew event title, say — cost a <strong>paid</strong>
     * buyer their ticket permanently.
     *
     * <p><strong>Safe to press when unsure.</strong> A notification that already succeeded is
     * {@code SENT}, not {@code DLQ}, so the re-claim matches nothing and the consumer acknowledges
     * the replay without sending. The answer is therefore "queued", not "delivered" — whether it
     * delivers depends on whether it needed to.
     */
    @PostMapping("/notifications/resend/{orderNumber}")
    public Map<String, Object> resend(@PathVariable String orderNumber) {
        return Map.of(
                "orderNumber", orderNumber,
                "outboxEventId", resends.resendTicket(orderNumber),
                "queued", true);
    }
}
