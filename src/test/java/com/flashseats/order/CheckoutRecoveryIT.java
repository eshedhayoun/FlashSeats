package com.flashseats.order;

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
 * A checkout that fails without charging must leave the buyer able to try again (ADR-034).
 */
@DisplayName("A checkout that never charged can always be retried")
class CheckoutRecoveryIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;
    private String admissionToken;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Recovery Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("A gateway outage keeps the seats AND the ability to pay for them")
    void gatewayOutageIsRetryable() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        var outage = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_error"));

        assertThat(outage.status()).isEqualTo(503);
        assertThat(outage.errorCode()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(outage.json().get("retryable").asBoolean()).isTrue();
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var retry = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(2);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A gateway outage costs the buyer none of their three card attempts")
    void gatewayOutageDoesNotConsumeAnAttempt() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        buyer.post("/orders/checkout", checkout(holdToken, "pm_card_error"));

        var declined = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_declined"));

        assertThat(declined.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(declined.json().get("attemptsRemaining").asInt()).isEqualTo(2);
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(1);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("A charge genuinely in flight is still refused, not resumed")
    void concurrentChargeIsStillRejected() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);

        var immediate = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(immediate.status()).isEqualTo(409);
        assertThat(immediate.errorCode()).isEqualTo("DUPLICATE_PAYMENT");
        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo("TK-STRANDED");
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("PENDING");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.countPaymentTransactions()).isZero();
    }

    @Test
    @DisplayName("An order stranded by a crash is resumable once no charge can still be running")
    void strandedPendingOrderResumes() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);
        fixture.ageOrder(holdToken, Duration.ofSeconds(120));

        String originalOrderNumber = fixture.orderNumberFor(holdToken);

        var retry = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(retry.text("orderNumber")).isEqualTo(originalOrderNumber);
        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Checkout refuses to charge when the hold has too little time left")
    void insufficientTimeRemainingDoesNotCharge() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.ageHold(
                holdToken,
                Duration.ofSeconds(390),
                Duration.ofSeconds(30));

        var refused = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa"));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("INSUFFICIENT_TIME_REMAINING");
        assertThat(refused.json().get("retryable").asBoolean()).isFalse();
        assertThat(refused.text("expiresAt")).isNotBlank();

        assertThat(fixture.countPaymentTransactions()).isZero();
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isZero();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Checkout remains allowed briefly after the sale closes")
    void checkoutWithinSaleGraceWindowStillSucceeds() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.closeSale(eventId);

        var checkout = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa"));

        assertThat(checkout.status()).isEqualTo(201);
        assertThat(checkout.text("status")).isEqualTo("CONFIRMED");

        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));
    }

    @Test
    @DisplayName("A recovered checkout keeps the original order number")
    void recoveredCheckoutKeepsSameOrderNumber() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);
        String originalOrderNumber = fixture.orderNumberFor(holdToken);
        fixture.ageOrder(holdToken, Duration.ofSeconds(120));

        var retry = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(retry.text("orderNumber")).isEqualTo(originalOrderNumber);

        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Three declined cards keep the same order and exhaust attempts without consuming the hold")
    void threeDeclinesExhaustAttemptsWithoutLosingTheHold() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        var first = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_declined", "decline-1-" + holdToken));

        assertThat(first.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(first.json().get("attemptsRemaining").asInt()).isEqualTo(2);

        String originalOrderNumber = fixture.orderNumberFor(holdToken);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var second = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_declined", "decline-2-" + holdToken));

        assertThat(second.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(second.json().get("attemptsRemaining").asInt()).isEqualTo(1);

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(2);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(2);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var third = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_declined", "decline-3-" + holdToken));

        assertThat(third.errorCode()).isEqualTo("PAYMENT_ATTEMPTS_EXHAUSTED");
        assertThat(third.json().get("attemptsRemaining").asInt()).isEqualTo(0);
        assertThat(third.json().get("retryable").asBoolean()).isFalse();

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(3);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(3);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var fourth = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa", "decline-4-" + holdToken));

        assertThat(fourth.errorCode()).isEqualTo("PAYMENT_ATTEMPTS_EXHAUSTED");
        assertThat(fourth.json().get("attemptsRemaining").asInt()).isEqualTo(0);

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(3);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(3);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        admissionToken = buyer
                .post(
                        "/queue/admit",
                        Map.of("eventId", eventId),
                        Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");

        return buyer;
    }

    private String reserve(BuyerSession buyer, int quantity) {
        return buyer.post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken))
                .text("holdToken");
    }

    private Map<String, Object> checkout(String holdToken, String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "recovery-" + holdToken);
    }

    private Map<String, Object> checkout(
            String holdToken, String paymentMethodId, String idempotencyKey) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", idempotencyKey);
    }
}
