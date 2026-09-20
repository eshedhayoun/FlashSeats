package com.flashseats.order;

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
 * A checkout that fails without charging must leave the buyer able to try again (ADR-034).
 *
 * <p>This is the rule the first pass got wrong, and it was invisible from every other angle: the
 * happy path worked, the decline path worked, and the failure only appeared on the one branch no
 * test exercised. The order row is committed as {@code PENDING} before the charge, so any exit that
 * left it there stranded the buyer holding live seats behind a {@code 409} telling them a charge
 * they never made was still running.
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
        // The response promises "your seats are still held — please retry". Both halves have to be
        // true, and it was the second one that was a lie.
        assertThat(outage.json().get("retryable").asBoolean()).isTrue();
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var retry = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A gateway outage costs the buyer none of their three card attempts")
    void gatewayOutageDoesNotConsumeAnAttempt() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        buyer.post("/orders/checkout", checkout(holdToken, "pm_card_error"));

        // The outage was ours, not theirs. A declined card is what spends an attempt, so the first
        // decline after an outage must still report two remaining.
        var declined = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_declined"));

        assertThat(declined.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(declined.json().get("attemptsRemaining").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A charge genuinely in flight is still refused, not resumed")
    void concurrentChargeIsStillRejected() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        // Strand an order in PENDING, then retry immediately. Within the staleness window the row
        // is indistinguishable from a live charge, and 409 is the correct answer — resuming here is
        // exactly the double-submit the guard exists to stop (global standards §3, rule 4).
        fixture.strandPendingOrder(holdToken);

        var immediate = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(immediate.status()).isEqualTo(409);
        assertThat(immediate.errorCode()).isEqualTo("DUPLICATE_PAYMENT");
    }

    @Test
    @DisplayName("An order stranded by a crash is resumable once no charge can still be running")
    void strandedPendingOrderResumes() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        // A process killed between committing the order and charging leaves exactly this row. No
        // exception handler ever ran, so only its age can tell us the charge is not still in
        // flight.
        fixture.strandPendingOrder(holdToken);
        fixture.ageOrder(holdToken, Duration.ofSeconds(120));

        var retry = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("status")).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
    }
    @Test
    @DisplayName("Checkout refuses to charge when the hold has too little time left")
    void insufficientTimeRemainingDoesNotCharge() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        // The hold is still active, but it was created long enough ago that its absolute
        // 420-second lifetime leaves only 30 seconds. Order requires at least 45 seconds.
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

        // The most important assertion: Order must refuse BEFORE it contacts payment.
        assertThat(fixture.countPaymentTransactions()).isZero();

        // The buyer still owns the seats.
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        // An order row exists, but no payment attempt was consumed.
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isZero();

        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Checkout remains allowed briefly after the sale closes")
    void checkoutWithinSaleGraceWindowStillSucceeds() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        // The buyer obtained the hold while sales were open. Closing the sale now must not
        // invalidate the checkout already in progress.
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
    void recoveredCheckoutKeepsSameOrderNumber(){
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken);
        String originalOrderNumber = fixture.orderNumberFor(holdToken);
        fixture.ageOrder(holdToken, Duration.ofSeconds(120));

        var retry = buyer.post("/orders/checkout",checkout(holdToken, "pm_card_visa"));
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

        var first = buyer.post("/orders/checkout",checkout(holdToken, "pm_card_declined", "decline-1-" + holdToken));

        assertThat(first.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(first.json().get("attemptsRemaining").asInt()).isEqualTo(2);

        String originalOrderNumber = fixture.orderNumberFor(holdToken);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var second = buyer.post("/orders/checkout",checkout(holdToken, "pm_card_declined", "decline-2-" + holdToken));

        assertThat(second.errorCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(second.json().get("attemptsRemaining").asInt()).isEqualTo(1);

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(2);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(2);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        var third = buyer.post( "/orders/checkout",checkout(holdToken, "pm_card_declined", "decline-3-" + holdToken));

        assertThat(third.errorCode()).isEqualTo("PAYMENT_ATTEMPTS_EXHAUSTED");
        assertThat(third.json().get("attemptsRemaining").asInt()).isEqualTo(0);
        assertThat(third.json().get("retryable").asBoolean()).isFalse();

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(3);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(3);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");

        // A fourth checkout must be rejected before another gateway attempt starts.
        var fourth = buyer.post("/orders/checkout",checkout(holdToken, "pm_card_visa", "decline-4-" + holdToken));

        assertThat(fourth.errorCode()).isEqualTo("PAYMENT_ATTEMPTS_EXHAUSTED");
        assertThat(fourth.json().get("attemptsRemaining").asInt()).isEqualTo(0);

        assertThat(fixture.orderNumberFor(holdToken)).isEqualTo(originalOrderNumber);
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(3);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(3);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    // ----------------------------------------------------------------- helpers

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);
        admissionToken = buyer
                .post("/queue/admit", java.util.Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
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
    //overloaded method to allow passing in a custom idempotency key for testing purposes
    private Map<String, Object> checkout(
        String holdToken, String paymentMethodId, String idempotencyKey) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", idempotencyKey);
    }
}
