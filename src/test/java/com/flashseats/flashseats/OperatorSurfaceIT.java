package com.flashseats.flashseats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.NotificationLogService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The operator surface (ADR-043): an operator who can see what failed, send it again, and stop a
 * sale.
 *
 * <p>This is not polish. Two decisions already in the system assume this surface exists: ADR-029
 * sends deterministic failures straight to the dead-letter queue with no retries, which is only
 * correct if someone can replay them, and ADR-038 went to real trouble making a dead-lettered claim
 * re-claimable <em>so that</em> a replay would send. Neither had a trigger.
 */
@DisplayName("An operator can see what failed, resend it, and stop a sale")
class OperatorSurfaceIT extends IntegrationTest {

    private static final int CAPACITY = 10;
    private static final Map<String, String> OPERATOR = BuyerSession.basicAuth("admin", "admin");

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private NotificationLogService notifications;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Operator Test");
        tierId = fixture.tier(eventId, "Floor", 3_000, CAPACITY);
    }

    // ------------------------------------------------------------------- auth

    @Test
    @DisplayName("Without credentials the surface refuses with a code and says how to authenticate")
    void unauthenticatedCallsAreRefusedProperly() {
        var response = new BuyerSession(port).get("/admin/notifications/dlq");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
    }

    @Test
    @DisplayName("With the wrong password, likewise — not a stack trace")
    void wrongCredentialsAreRefusedProperly() {
        var response = new BuyerSession(port)
                .get("/admin/notifications/dlq", BuyerSession.basicAuth("admin", "not-the-password"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
    }

    // -------------------------------------------------------------------- dlq

    @Test
    @DisplayName("The DLQ lists what failed, with the reason it failed")
    void deadLettersAreVisibleWithTheirReason() {
        notifications.claim("TK-00099", NotificationKind.TICKET_DELIVERY, "buyer@example.com");
        notifications.markDeadLettered(
                "TK-00099", NotificationKind.TICKET_DELIVERY, "Font cannot draw 'פסטיבל'");

        var response = new BuyerSession(port).get("/admin/notifications/dlq", OPERATOR);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("total").asInt()).isEqualTo(1);

        var entry = response.json().get("entries").get(0);
        assertThat(entry.get("orderNumber").asText()).isEqualTo("TK-00099");
        assertThat(entry.get("recipientEmail").asText()).isEqualTo("buyer@example.com");
        // The reason is the whole point: ADR-029 does not retry a deterministic failure, so this
        // stored string is the only account of what went wrong.
        assertThat(entry.get("failureReason").asText()).contains("Font cannot draw");
    }

    @Test
    @DisplayName("A delivery that SUCCEEDED never appears in the DLQ")
    void sentNotificationsAreNotDeadLetters() {
        notifications.claim("TK-00100", NotificationKind.TICKET_DELIVERY, "buyer@example.com");
        notifications.markSent("TK-00100", NotificationKind.TICKET_DELIVERY);

        var response = new BuyerSession(port).get("/admin/notifications/dlq", OPERATOR);

        assertThat(response.json().get("total").asInt()).isZero();
    }

    // ----------------------------------------------------------------- resend

    @Test
    @DisplayName("Resending a real order queues a fresh outbox row from the stored payload")
    void resendQueuesTheOriginalMessageAgain() {
        String orderNumber = buyATicket();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));

        var response = new BuyerSession(port)
                .post("/admin/notifications/resend/" + orderNumber, null, OPERATOR);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("queued").asBoolean()).isTrue();
        // A NEW row, replayed through the ordinary relay — not a rewrite of the original, which is
        // the record that a first attempt happened.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(2));
    }

    @Test
    @DisplayName("Resending an order that never existed is 404, not a queued message to nowhere")
    void resendingAnUnknownOrderIsRefused() {
        var response = new BuyerSession(port)
                .post("/admin/notifications/resend/TK-99999", null, OPERATOR);

        assertThat(response.status()).isEqualTo(410);
        assertThat(response.errorCode()).isEqualTo("NOTIFICATION_PAYLOAD_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ order

    @Test
    @DisplayName("An operator can read an order — but never its receipt token")
    void theOperatorViewWithholdsTheBearerCapability() {
        String orderNumber = buyATicket();

        var response = new BuyerSession(port).get("/admin/orders/" + orderNumber, OPERATOR);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(response.json().get("holdToken").asText()).isNotBlank();
        // receiptToken authorises reading this order from any device for 90 days. An operator has no
        // need of one, and it would land in terminal history and any log that records bodies.
        assertThat(response.json().has("receiptToken")).isFalse();
    }

    // ------------------------------------------------------------------ pause

    @Test
    @DisplayName("Pausing a sale closes every gate, and resuming reopens them with the queue intact")
    void pausingClosesTheSaleAndResumingRestoresIt() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);

        var paused = new BuyerSession(port).post("/admin/events/" + eventId + "/pause", null, OPERATOR);
        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.json().get("status").asText()).isEqualTo("PAUSED");

        // SaleWindows reads anything but PUBLISHED as CLOSED, so the gates shut with no new code.
        assertThat(buyer.get("/events/" + eventId).text("windowStatus")).isEqualTo("CLOSED");
        assertThat(buyer.post("/queue/join", Map.of("eventId", eventId)).status()).isNotEqualTo(202);

        var resumed = new BuyerSession(port).post("/admin/events/" + eventId + "/resume", null, OPERATOR);
        assertThat(resumed.json().get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(buyer.get("/events/" + eventId).text("windowStatus")).isEqualTo("OPEN");

        // Nothing was destroyed on the way through: the stock counter is exactly where it was.
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("Pausing is idempotent, so a second click is not an error")
    void pausingTwiceIsFine() {
        new BuyerSession(port).post("/admin/events/" + eventId + "/pause", null, OPERATOR);
        var again = new BuyerSession(port).post("/admin/events/" + eventId + "/pause", null, OPERATOR);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("status").asText()).isEqualTo("PAUSED");
    }

    // ---------------------------------------------------------------- helpers

    private String buyATicket() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(Duration.ofSeconds(15))
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        String admission = buyer
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");

        String holdToken = buyer
                .post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", 1),
                        Map.of("X-Admission-Token", admission))
                .text("holdToken");

        return buyer
                .post(
                        "/orders/checkout",
                        Map.of(
                                "holdToken", holdToken,
                                "userEmail", "buyer@example.com",
                                "paymentMethodId", "pm_card_visa",
                                "idempotencyKey", "op-" + holdToken))
                .text("orderNumber");
    }
}
