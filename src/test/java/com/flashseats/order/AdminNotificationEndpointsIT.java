package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.IntegrationTest;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.order.repository.OutboxEventRepository;
import com.flashseats.order.service.NotificationResendService;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Admin surface for troubleshooting stuck notifications.
 *
 * <p>Two operations: list dead-letter queue, and replay a message. Both are guarded by ROLE_ADMIN,
 * and replay is safe to press when unsure — a message that already sent is SENT, not DLQ, so the
 * re-claim fails and the consumer acknowledges quietly.
 */
@Slf4j
@DisplayName("Admin notification endpoints: DLQ and resend")
class AdminNotificationEndpointsIT extends IntegrationTest {

    @Autowired
    private NotificationLogRepository notificationLogs;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private NotificationResendService resends;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setup() {
        notificationLogs.deleteAll();
        outbox.deleteAll();
        orders.deleteAll();
    }

    @Test
    @DisplayName("List dead-lettered notifications via service")
    void listDeadLetters() {
        // Given: three dead-lettered notifications
        createDLQEntry("TK-11111", NotificationKind.TICKET_DELIVERY, "buyer1@example.com");
        createDLQEntry("TK-22222", NotificationKind.TICKET_DELIVERY, "buyer2@example.com");
        createDLQEntry("TK-33333", NotificationKind.REFUND_NOTICE, "buyer3@example.com");

        // When: we query the repository
        long count = notificationLogs.countByStatus(NotificationStatus.DLQ);
        var entries = notificationLogs.findByStatusOrderByUpdatedAtDesc(
                NotificationStatus.DLQ, org.springframework.data.domain.PageRequest.of(0, 50));

        // Then: all three appear
        assertThat(count).isEqualTo(3);
        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("DLQ total count excludes non-DLQ entries")
    void dlqCountIsAccurate() {
        // Given: two DLQ entries and one SENT entry (should not be counted)
        createDLQEntry("TK-11111", NotificationKind.TICKET_DELIVERY, "buyer1@example.com");
        createDLQEntry("TK-22222", NotificationKind.TICKET_DELIVERY, "buyer2@example.com");
        createSentEntry("TK-33333", NotificationKind.TICKET_DELIVERY, "buyer3@example.com");

        // When: we count DLQ
        long count = notificationLogs.countByStatus(NotificationStatus.DLQ);

        // Then: total is 2 (only DLQ, not SENT)
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Resend creates new outbox event")
    void resendQueuesNewOutboxEvent() {
        // Given: an order with a failed ticket delivery
        String orderNumber = "TK-44444";
        createOrder(orderNumber);
        createDLQEntry(orderNumber, NotificationKind.TICKET_DELIVERY, "buyer@example.com");
        createOutboxEvent(orderNumber);

        // When: we resend
        String eventId = resends.resendTicket(orderNumber);

        // Then: returns new outbox event ID and a new row exists in PENDING state
        assertThat(eventId).isNotNull();

        var event = outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(
                orderNumber, "ORDER_CONFIRMED");
        assertThat(event).isPresent();
        assertThat(event.get().getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Resend is safe when message already succeeded")
    void resendIsSafeWhenUnsure() {
        // Given: an order with a SENT notification (successful delivery)
        String orderNumber = "TK-55555";
        createOrder(orderNumber);
        createSentEntry(orderNumber, NotificationKind.TICKET_DELIVERY, "buyer@example.com");
        createOutboxEvent(orderNumber);

        // When: we resend (operator unsure if it worked)
        String eventId = resends.resendTicket(orderNumber);

        // Then: returns success
        assertThat(eventId).isNotNull();

        // The consumer will receive the message again, find SENT (not DLQ), and ack silently
        // (proven by NotificationListenerIT and NotificationClaimIT#sentIsTerminal)
    }

    @Test
    @DisplayName("Resend creates new event, does not alter original")
    void resendDoesNotAlterOriginal() {
        // Given: an order with an original PENDING outbox event
        String orderNumber = "TK-77777";
        createOrder(orderNumber);
        var original = createOutboxEvent(orderNumber);
        Instant originalCreatedAt = original.getCreatedAt();

        // When: we resend
        resends.resendTicket(orderNumber);

        // Then: original event is unchanged
        var reloaded = outbox.findById(original.getId()).get();
        assertThat(reloaded.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(reloaded.getStatus().name()).isEqualTo("PENDING");

        // And: a new event exists with different ID and same payload
        var replayed = outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(
                orderNumber, "ORDER_CONFIRMED");
        assertThat(replayed).isPresent();
        assertThat(replayed.get().getId()).isNotEqualTo(original.getId());
        // Payload content is the same, but field order may differ in JSON
        assertThat(replayed.get().getPayload()).contains("orderNumber").contains("userEmail");
    }

    // ======== Helpers ========

    private void createDLQEntry(String orderNumber, NotificationKind kind, String email) {
        var log = new NotificationLog(orderNumber, kind, email);
        log.setStatus(NotificationStatus.DLQ);
        log.setFailureReason("SMTP server unavailable");
        notificationLogs.save(log);
    }

    private void createSentEntry(String orderNumber, NotificationKind kind, String email) {
        var log = new NotificationLog(orderNumber, kind, email);
        log.setStatus(NotificationStatus.SENT);
        log.setSentAt(clock.instant());
        notificationLogs.save(log);
    }

    private void createOrder(String orderNumber) {
        var order = new Order();
        order.setOrderNumber(orderNumber);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setHoldToken("hld_" + orderNumber);
        order.setUserSessionId("sid_test_" + orderNumber);
        order.setUserEmail("buyer@example.com");
        order.setReceiptToken("rcp_" + orderNumber);
        order.setEventId(1L);
        order.setTotalAmountCents(15000L);
        order.setCurrency("USD");
        order.setPaymentAttempts(1);
        orders.save(order);
    }

    private OutboxEvent createOutboxEvent(String orderNumber) {
        var event = new OutboxEvent(
                "Order",
                orderNumber,
                "ORDER_CONFIRMED",
                "{\"orderNumber\":\"" + orderNumber + "\",\"userEmail\":\"buyer@example.com\"}");
        return outbox.save(event);
    }
}
