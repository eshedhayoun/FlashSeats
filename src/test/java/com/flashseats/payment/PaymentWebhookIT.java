package com.flashseats.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.flashseats.support.StripeWebhooks;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The provider webhook is the recovery path for a settled charge whose HTTP response was lost.
 */
@DisplayName("A settled charge reaches its order even when the buyer never saw the response")
class PaymentWebhookIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final String WEBHOOK = "/payments/webhook";

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private long eventId;
    private long tierId;
    private String admissionToken;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Webhook Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("An unsigned or wrongly-signed delivery is refused")
    void signatureIsTheOnlyGate() {
        String delivery = eventId();
        String body = StripeWebhooks.settledBody(
                delivery, "pi_forged", "hld_whatever", 15_000);

        var forged = post(body, StripeWebhooks.signature(body, "whsec_attacker"));
        assertThat(forged.status()).isEqualTo(400);
        assertThat(forged.errorCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");

        var garbled = post(body, "t=1,v1=notasignature");
        assertThat(garbled.status()).isEqualTo(400);
        assertThat(garbled.errorCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");

        assertThat(fixture.countWebhookEvents(delivery)).isZero();
    }

    @Test
    @DisplayName("A missing signature header is a 400 with a code, not a bare 500")
    void missingSignatureHeaderIsNamed() {
        String body = StripeWebhooks.settledBody(
                eventId(), "pi_x", "hld_x", 1_000);

        var response = new BuyerSession(port).post(WEBHOOK, null, Map.of());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body).isNotEmpty();
    }

    @Test
    @DisplayName("An event type we do not handle is acknowledged")
    void unhandledTypesAreAcknowledged() {
        String delivery = eventId();
        String body = StripeWebhooks.unhandledBody(delivery);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);
        assertThat(fixture.countWebhookEvents(delivery)).isZero();
    }

    @Test
    @DisplayName("A charge whose response was lost still confirms the order and queues the ticket")
    void settlesAnOrderStrandedMidCharge() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        fixture.strandPendingOrder(holdToken, 15_000);
        String transactionReference =
                seedSettledPayment(holdToken, "pi_settled", 15_000);

        String body = StripeWebhooks.settledBody(
                eventId(), "pi_settled", holdToken, 15_000);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(paymentStatus(transactionReference)).isEqualTo("SUCCEEDED");
        assertThat(paymentReferenceOnOrder(holdToken)).isEqualTo(transactionReference);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(
                                fixture.countOutbox("ORDER_CONFIRMED", "PROCESSED"))
                                .isEqualTo(1));
    }

    @Test
    @DisplayName("The same delivery twice settles once")
    void replaysAreClaimedOnce() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);
        String transactionReference =
                seedSettledPayment(holdToken, "pi_replayed", 7_500);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(
                deliveryId, "pi_replayed", holdToken, 7_500);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);
        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        assertThat(fixture.countWebhookEvents(deliveryId)).isEqualTo(1);
        assertThat(fixture.webhookProcessed(deliveryId)).isTrue();
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(paymentStatus(transactionReference)).isEqualTo("SUCCEEDED");

        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(
                                fixture.countOutbox("ORDER_CONFIRMED", "PROCESSED"))
                                .isEqualTo(1));

        assertThat(
                fixture.countOutbox("ORDER_CONFIRMED", "PROCESSED"))
                .isEqualTo(1);
        assertThat(
                fixture.countOutbox("ORDER_REFUNDED", "PROCESSED"))
                .isZero();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A delivery for a hold that is already gone is refunded, not confirmed")
    void refundsWhenTheSeatsAreGone() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        fixture.strandPendingOrder(holdToken, 15_000);
        String transactionReference =
                seedSettledPayment(holdToken, "pi_too_late", 15_000);

        fixture.expireHold(holdToken);
        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(fixture.holdStatus(holdToken))
                                .isEqualTo("EXPIRED"));

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(
                deliveryId, "pi_too_late", holdToken, 15_000);

        assertThat(post(body, StripeWebhooks.signature(body)).status())
                .isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("REFUNDED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("EXPIRED");
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(paymentStatus(transactionReference)).isEqualTo("REFUNDED");
        assertThat(refundedAmount(transactionReference)).isEqualTo(15_000L);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(
                                fixture.countOutbox("ORDER_REFUNDED", "PROCESSED"))
                                .isEqualTo(1));

        assertThat(
                fixture.countOutbox("ORDER_CONFIRMED", "PROCESSED"))
                .isZero();

        // Replay of the same provider event must not issue a second refund.
        assertThat(post(body, StripeWebhooks.signature(body)).status())
                .isEqualTo(200);

        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(refundedAmount(transactionReference)).isEqualTo(15_000L);
        assertThat(
                fixture.countOutbox("ORDER_REFUNDED", "PROCESSED"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A delivery for a completed purchase changes nothing")
    void aLateDeliveryAgainstACompletedPurchaseIsInert() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        var purchase = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa"));
        assertThat(purchase.status()).isEqualTo(201);

        String gatewayReference = fixture.gatewayReferenceFor(holdToken);
        String body = StripeWebhooks.settledBody(
                eventId(), gatewayReference, holdToken, 7_500);

        assertThat(post(body, StripeWebhooks.signature(body)).status())
                .isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A verified webhook increments the received metric by event type")
    void recordsVerifiedWebhookByEventType() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);
        seedSettledPayment(holdToken, "pi_metric_test", 7_500);

        String delivery = eventId();
        String body = StripeWebhooks.settledBody(
                delivery, "pi_metric_test", holdToken, 7_500);

        var existingCounter = meters.find("flashseats.payment.webhook.received")
                .tag("type", "payment_intent.succeeded")
                .counter();

        double before = existingCounter == null
                ? 0.0
                : existingCounter.count();

        assertThat(post(body, StripeWebhooks.signature(body)).status())
                .isEqualTo(200);

        var counter = meters.find("flashseats.payment.webhook.received")
                .tag("type", "payment_intent.succeeded")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count() - before).isEqualTo(1.0);
    }

    private BuyerSession.Response post(String rawBody, String signature) {
        return new BuyerSession(port)
                .postRaw(
                        WEBHOOK,
                        rawBody,
                        Map.of("Stripe-Signature", signature));
    }

    private static String eventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
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
                        Map.of(
                                "eventId", eventId,
                                "tierId", tierId,
                                "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken))
                .text("holdToken");
    }

    private Map<String, Object> checkout(
            String holdToken, String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "webhook-" + holdToken);
    }

    /**
     * Seeds the durable payment ledger so webhook tests represent money that really settled.
     *
     * <p>This keeps the test fixture self-contained; no fifth file change is needed.
     */
    private String seedSettledPayment(
            String holdToken, String gatewayReference, long amountCents) {

        String transactionReference =
                "pt_test_" + UUID.randomUUID().toString().replace("-", "");

        int inserted = jdbc.update(
                """
                INSERT INTO payment_transactions (
                    transaction_reference,
                    order_number,
                    hold_token,
                    user_session_id,
                    stripe_payment_intent_id,
                    client_idempotency_key,
                    amount_cents,
                    currency,
                    status,
                    attempt_number,
                    refunded_amount_cents,
                    created_at,
                    updated_at
                )
                SELECT
                    ?,
                    o.order_number,
                    o.hold_token,
                    o.user_session_id,
                    ?,
                    ?,
                    ?,
                    'USD',
                    'SUCCEEDED',
                    1,
                    0,
                    now(),
                    now()
                FROM orders o
                WHERE o.hold_token = ?
                """,
                transactionReference,
                gatewayReference,
                "webhook-" + holdToken,
                amountCents,
                holdToken);

        assertThat(inserted).isEqualTo(1);
        return transactionReference;
    }

    private String paymentStatus(String transactionReference) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_transactions WHERE transaction_reference = ?",
                String.class,
                transactionReference);
    }

    private long refundedAmount(String transactionReference) {
        Long value = jdbc.queryForObject(
                """
                SELECT refunded_amount_cents
                FROM payment_transactions
                WHERE transaction_reference = ?
                """,
                Long.class,
                transactionReference);
        return value == null ? 0L : value;
    }

    private String paymentReferenceOnOrder(String holdToken) {
        return jdbc.queryForObject(
                "SELECT payment_transaction_ref FROM orders WHERE hold_token = ?",
                String.class,
                holdToken);
    }
}
