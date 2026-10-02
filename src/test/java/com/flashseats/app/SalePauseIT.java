package com.flashseats.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A pause halts a sale without ending it (ADR-066). Gate by gate: the line keeps forming and nobody is
 * let out of it, nothing new is reserved, a buyer already holding seats can still pay, and resuming
 * puts everyone back where they were. It used to read as {@code CLOSED}, and every buyer was told the
 * sale was over.
 */
@DisplayName("A paused sale is paused, not over")
class SalePauseIT extends IntegrationTest {

    private static final int CAPACITY = 20;
    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final Map<String, String> OPERATOR = BuyerSession.basicAuth("admin", "admin");

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Pause Fest");
        tierId = fixture.tier(eventId, "Floor", 4_000, CAPACITY);
    }

    @Test
    @DisplayName("It stays listed and reads PAUSED, and reads OPEN again once resumed")
    void aPausedSaleIsListedAsPaused() {
        pause();

        BuyerSession visitor = new BuyerSession(port);
        assertThat(visitor.get("/events/" + eventId).text("windowStatus")).isEqualTo("PAUSED");
        var listed = visitor.get("/events").json();
        assertThat(listed.isArray()).isTrue();
        assertThat(listed.get(0).get("eventId").asLong()).isEqualTo(eventId);
        assertThat(listed.get(0).get("windowStatus").asString()).isEqualTo("PAUSED");

        resume();
        assertThat(visitor.get("/events/" + eventId).text("windowStatus")).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("The line keeps forming in arrival order while paused, nobody leaves it, and resuming lets them in")
    void theLineHoldsItsOrderAndMovesOnResume() throws InterruptedException {
        pause();

        BuyerSession first = new BuyerSession(port);
        BuyerSession second = new BuyerSession(port);
        first.get("/events/" + eventId);
        second.get("/events/" + eventId);
        assertThat(first.post("/queue/join", Map.of("eventId", eventId)).status()).isEqualTo(202);
        assertThat(second.post("/queue/join", Map.of("eventId", eventId)).status()).isEqualTo(202);

        // Several promotion ticks (200 ms each under the test profile): nobody may be let out.
        Thread.sleep(1_000);

        var firstStatus = first.get("/queue/status?eventId=" + eventId);
        var secondStatus = second.get("/queue/status?eventId=" + eventId);
        assertThat(firstStatus.text("phase")).isEqualTo("WAITING");
        assertThat(firstStatus.number("position")).isEqualTo(1);
        assertThat(firstStatus.json().get("paused").asBoolean()).isTrue();
        assertThat(firstStatus.text("passToken")).isNull();
        assertThat(secondStatus.number("position")).isEqualTo(2);

        resume();

        await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(first.get("/queue/status?eventId=" + eventId).text("passToken")).isNotNull();
            assertThat(second.get("/queue/status?eventId=" + eventId).text("passToken")).isNotNull();
        });
        assertThat(first.get("/queue/status?eventId=" + eventId).json().get("paused").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("No seats are reserved while paused, and the refusal says paused and retryable, not closed")
    void holdsAreRefusedWhilePaused() {
        Admitted buyer = admittedBuyer();
        pause();

        var refused = buyer.reserve();

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("SALE_PAUSED");
        assertThat(refused.json().get("retryable").asBoolean()).isTrue();
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);

        resume();
        assertThat(buyer.reserve().status()).isEqualTo(201);
    }

    @Test
    @DisplayName("A buyer already holding seats can still pay while paused")
    void checkoutOfAnExistingHoldCompletesWhilePaused() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve().text("holdToken");
        pause();

        var purchase = buyer.session().post(
                "/orders/checkout",
                Map.of(
                        "holdToken", holdToken,
                        "userEmail", "buyer@example.com",
                        "paymentMethodId", "pm_card_visa",
                        "idempotencyKey", "pause-" + holdToken));

        assertThat(purchase.status()).isEqualTo(201);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("An event that is not live cannot be paused, and says so with an operator's code")
    void aCancelledEventIsNotPausable() {
        fixture.setEventStatus(eventId, "CANCELLED");

        var refused = new BuyerSession(port).post("/admin/events/" + eventId + "/pause", null, OPERATOR);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("EVENT_NOT_PAUSABLE");
    }

    // ----------------------------------------------------------------- helpers

    private record Admitted(BuyerSession session, String admissionToken, long eventId, long tierId) {

        BuyerSession.Response reserve() {
            return session.post(
                    "/holds",
                    Map.of("eventId", eventId, "tierId", tierId, "quantity", 1),
                    Map.of("X-Admission-Token", admissionToken));
        }
    }

    private Admitted admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));
        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"), token -> token != null);
        String admissionToken = buyer
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");
        return new Admitted(buyer, admissionToken, eventId, tierId);
    }

    private void pause() {
        var paused = new BuyerSession(port).post("/admin/events/" + eventId + "/pause", null, OPERATOR);
        assertThat(paused.status()).isEqualTo(200);
    }

    private void resume() {
        var resumed = new BuyerSession(port).post("/admin/events/" + eventId + "/resume", null, OPERATOR);
        assertThat(resumed.status()).isEqualTo(200);
    }
}
