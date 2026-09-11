package com.flashseats.flashseats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The journey a person actually walks, over real HTTP, end to end.
 *
 * <p>Landing → queue → promotion → admission → hold → checkout → receipt. Every step goes through
 * the public API with a real cookie, so anything that only works when a test reaches past the
 * boundary will fail here.
 */
@DisplayName("A buyer can walk from the event page to a confirmed order")
class UserJourneyIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Aurora Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("The full happy path, and the invariant still holds at the end")
    void buyerReachesAConfirmedOrder() {
        BuyerSession buyer = new BuyerSession(port);

        // 1. Landing. This request also mints the buyer's fsid cookie.
        var landing = buyer.get("/events/" + eventId);
        assertThat(landing.ok()).isTrue();
        assertThat(landing.text("windowStatus")).isEqualTo("OPEN");
        assertThat(landing.text("serverTime")).isNotNull();
        // Availability is a bucket, never a count — an exact number invites panic-buying.
        assertThat(landing.json().get("tiers").get(0).get("availability").asText())
                .isIn("PLENTY", "LIMITED", "SOLD_OUT");

        // 2. Join the queue.
        var join = buyer.post("/queue/join", Map.of("eventId", eventId));
        assertThat(join.status()).isEqualTo(202);

        // 3. Wait to be promoted by the real worker, and collect the pass the way a client whose
        //    stream dropped would: from the polling endpoint.
        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        // 4. Exchange the pass for a browse session.
        var admit = buyer.post(
                "/queue/admit", java.util.Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken));
        assertThat(admit.ok()).isTrue();
        String admissionToken = admit.text("admissionToken");
        assertThat(admissionToken).isNotNull();

        // The pass is single-use: spending it again must fail.
        var replay = buyer.post(
                "/queue/admit", java.util.Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken));
        assertThat(replay.errorCode()).isEqualTo("QUEUE_PASS_INVALID");

        // 5. Reserve seats.
        var hold = buyer.post(
                "/holds",
                Map.of("eventId", eventId, "tierId", tierId, "quantity", 2),
                Map.of("X-Admission-Token", admissionToken));
        assertThat(hold.status()).isEqualTo(201);
        String holdToken = hold.text("holdToken");
        assertThat(fixture.remaining(tierId)).isEqualTo(18);

        // 6. Pay.
        var receipt = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(receipt.status()).isEqualTo(201);
        assertThat(receipt.text("status")).isEqualTo("CONFIRMED");
        String orderNumber = receipt.text("orderNumber");
        String receiptToken = receipt.text("receiptToken");

        // 7. The receipt is readable with the capability token alone — that is what makes the link
        //    in a confirmation email work on a different device weeks later.
        var byToken = new BuyerSession(port)
                .get("/orders/" + orderNumber + "?receiptToken=" + receiptToken);
        assertThat(byToken.ok()).isTrue();
        assertThat(byToken.text("orderNumber")).isEqualTo(orderNumber);

        // ...and unreadable without it.
        var stranger = new BuyerSession(port).get("/orders/" + orderNumber);
        assertThat(stranger.status()).isEqualTo(404);

        // 8. Reloading the page must still find the purchase. Rehydration reported only payments in
        //    flight, so a confirmed order vanished the instant it succeeded and the buyer was shown
        //    the landing page — invited to queue for seats they already owned (ADR-037).
        var afterReload = buyer.get("/sale/" + eventId + "/state");
        assertThat(afterReload.json().get("order").get("orderNumber").asText()).isEqualTo(orderNumber);
        assertThat(afterReload.json().get("order").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(afterReload.json().get("hold").isNull()).isTrue();

        // 9. Fulfilment was queued inside the order transaction, and the relay drains it.
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));

        // 10. The hold became a sale, and the books balance.
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A declined card keeps the buyer's seats so they can try another")
    void declineRetainsTheHold() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        var declined = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_declined"));

        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(declined.json().get("retryable").asBoolean()).isTrue();
        assertThat(declined.json().get("attemptsRemaining").asInt()).isEqualTo(2);

        // The promise the UI makes — "your seats are still held" — has to be true.
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.remaining(tierId)).isEqualTo(18);

        // And the retry succeeds on the same order number.
        var retry = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(retry.ok()).isTrue();
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("A second checkout for the same hold never produces a second order or charge")
    void doubleSubmitProducesOneOrder() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        var first = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        var second = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(first.status()).isEqualTo(201);
        // A completed operation replays as its original result, not as an error.
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.text("orderNumber")).isEqualTo(first.text("orderNumber"));

        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reserving without an admission session is refused")
    void holdRequiresAdmission() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);

        var refused = buyer.post(
                "/holds", Map.of("eventId", eventId, "tierId", tierId, "quantity", 1));

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.errorCode()).isEqualTo("ADMISSION_REQUIRED");
        assertThat(fixture.remaining(tierId)).isEqualTo(20);
    }

    @Test
    @DisplayName("Releasing seats returns them and leaves the buyer's place in the sale intact")
    void releaseKeepsAdmission() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 3);
        assertThat(fixture.remaining(tierId)).isEqualTo(17);

        assertThat(buyer.delete("/holds/" + holdToken).status()).isEqualTo(204);
        assertThat(fixture.remaining(tierId)).isEqualTo(20);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("RELEASED");

        // Still admitted: picking a different tier must not cost them their place (ADR-020).
        var state = buyer.get("/sale/" + eventId + "/state");
        assertThat(state.json().get("queue").get("state").asText()).isEqualTo("ADMITTED");
        assertThat(state.json().get("hold").isNull()).isTrue();
    }

    @Test
    @DisplayName("Rehydration reports the stage the buyer is actually at")
    void saleStateTracksTheJourney() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);

        assertThat(buyer.get("/sale/" + eventId + "/state").json().get("queue").get("state").asText())
                .isEqualTo("NOT_JOINED");

        buyer.post("/queue/join", Map.of("eventId", eventId));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(
                        buyer.get("/sale/" + eventId + "/state").json().get("queue").get("state").asText())
                .isIn("WAITING", "PROMOTED"));

        String admissionToken = admit(buyer);
        reserveWith(buyer, admissionToken, 1);

        var state = buyer.get("/sale/" + eventId + "/state");
        assertThat(state.json().get("queue").get("state").asText()).isEqualTo("ADMITTED");
        assertThat(state.json().get("hold").get("quantity").asInt()).isEqualTo(1);
        assertThat(state.text("serverTime")).isNotNull();
        assertThat(state.json().get("partial")).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));
        admissionToken = admit(buyer);
        return buyer;
    }

    private String admissionToken;

    private String admit(BuyerSession buyer) {
        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);
        return buyer
                .post("/queue/admit", java.util.Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");
    }

    private String reserve(BuyerSession buyer, int quantity) {
        return reserveWith(buyer, admissionToken, quantity);
    }

    private String reserveWith(BuyerSession buyer, String admission, int quantity) {
        return buyer.post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", quantity),
                        Map.of("X-Admission-Token", admission))
                .text("holdToken");
    }

    private Map<String, Object> checkout(String holdToken, String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "test-" + holdToken);
    }
}
