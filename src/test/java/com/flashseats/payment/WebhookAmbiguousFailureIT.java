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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A settlement that fails for an unknown reason must not be treated as "the seats are gone".
 */
@DisplayName("An ambiguous settlement failure is retried, never refunded")
class WebhookAmbiguousFailureIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final String WEBHOOK = "/payments/webhook";

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private JdbcTemplate jdbc;

    private long eventId;
    private long tierId;
    private String admissionToken;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Ambiguity Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("It answers non-2xx, releases the claim, and leaves the money where it is")
    void ambiguousFailureReleasesTheClaim() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        fixture.strandPendingOrder(holdToken, 15_000);
        String transactionReference =
                seedSettledPayment(holdToken, "pi_ambiguous", 15_000);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(
                deliveryId, "pi_ambiguous", holdToken, 15_000);

        fixture.withTierRemoved(tierId, () -> {
            var refused = post(body, StripeWebhooks.signature(body));

            assertThat(refused.ok()).isFalse();

            assertThat(fixture.orderStatus(holdToken)).isEqualTo("PENDING");
            assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
            assertThat(paymentStatus(transactionReference)).isEqualTo("SUCCEEDED");
            assertThat(refundedAmount(transactionReference)).isZero();
            assertThat(
                    fixture.countOutbox("PENDING")
                            + fixture.countOutbox("PROCESSED"))
                    .isZero();

            assertThat(fixture.countWebhookEvents(deliveryId)).isZero();
        });

        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("The redelivery after an ambiguous failure settles normally")
    void theRetryThenSucceeds() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        fixture.strandPendingOrder(holdToken, 15_000);
        String transactionReference =
                seedSettledPayment(holdToken, "pi_recovered", 15_000);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(
                deliveryId, "pi_recovered", holdToken, 15_000);

        fixture.withTierRemoved(
                tierId,
                () -> assertThat(
                        post(body, StripeWebhooks.signature(body)).ok())
                        .isFalse());

        assertThat(
                post(body, StripeWebhooks.signature(body)).status())
                .isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.countWebhookEvents(deliveryId)).isEqualTo(1);
        assertThat(fixture.webhookProcessed(deliveryId)).isTrue();
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(paymentStatus(transactionReference)).isEqualTo("SUCCEEDED");
        assertThat(refundedAmount(transactionReference)).isZero();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        await().atMost(PATIENCE)
                .untilAsserted(() ->
                        assertThat(
                                fixture.countOutbox(
                                        "ORDER_CONFIRMED",
                                        "PROCESSED"))
                                .isEqualTo(1));

        assertThat(
                fixture.countOutbox("ORDER_REFUNDED", "PROCESSED"))
                .isZero();
    }

    private BuyerSession.Response post(
            String rawBody,
            String signature) {
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
                        () -> buyer.get(
                                "/queue/status?eventId=" + eventId)
                                .text("passToken"),
                        token -> token != null);

        admissionToken = buyer
                .post(
                        "/queue/admit",
                        Map.of("eventId", eventId),
                        Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");

        return buyer;
    }

    private String reserve(
            BuyerSession buyer,
            int quantity) {
        return buyer.post(
                        "/holds",
                        Map.of(
                                "eventId", eventId,
                                "tierId", tierId,
                                "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken))
                .text("holdToken");
    }

    private String seedSettledPayment(
            String holdToken,
            String gatewayReference,
            long amountCents) {

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
}
