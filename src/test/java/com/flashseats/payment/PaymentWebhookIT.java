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
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The path that exists because the buyer's connection can be cut.
 *
 * <p>The charge settled and the response never arrived — a dropped connection, a killed replica, a
 * closed laptop. The money moved and nothing in this system knows it. Every assertion here is about
 * a failure that is invisible from the happy path: it only ever happens to a buyer who is no longer
 * watching.
 */
@DisplayName("A settled charge reaches its order even when the buyer never saw the response")
class PaymentWebhookIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final String WEBHOOK = "/payments/webhook";

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;
    private String admissionToken;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Webhook Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    // --------------------------------------------------------------- the gate

    @Test
    @DisplayName("An unsigned or wrongly-signed delivery is refused before its contents mean anything")
    void signatureIsTheOnlyGate() {
        String delivery = eventId();
        String body = StripeWebhooks.settledBody(delivery, "pi_forged", "hld_whatever", 15_000);

        // Signed with a secret this deployment does not have. This is the whole attack: the endpoint
        // is unauthenticated by necessity — the provider cannot hold a session — so anyone who can
        // reach the port and guess a hold token could otherwise confirm an order nobody paid for.
        var forged = post(body, StripeWebhooks.signature(body, "whsec_attacker"));
        assertThat(forged.status()).isEqualTo(400);
        assertThat(forged.errorCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");

        var garbled = post(body, "t=1,v1=notasignature");
        assertThat(garbled.status()).isEqualTo(400);
        assertThat(garbled.errorCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");

        // Nothing was claimed, so a genuine redelivery of this event is still free to be handled.
        assertThat(fixture.countWebhookEvents(delivery)).isZero();
    }

    @Test
    @DisplayName("A missing signature header is a 400 with a code, not a bare 500")
    void missingSignatureHeaderIsNamed() {
        String body = StripeWebhooks.settledBody(eventId(), "pi_x", "hld_x", 1_000);

        var response = new BuyerSession(port).post(WEBHOOK, null, Map.of());

        // Spring rejects the missing header before any handler runs, and the backstop advice has to
        // name it — otherwise it answers 500 INTERNAL_ERROR with no `code` at all (ADR-041).
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body).isNotEmpty();
    }

    @Test
    @DisplayName("An event type we do not handle is acknowledged, never asked for again")
    void unhandledTypesAreAcknowledged() {
        String delivery = eventId();
        String body = StripeWebhooks.unhandledBody(delivery);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        // A non-2xx would ask the provider to redeliver something we will go on ignoring for ever.
        assertThat(fixture.countWebhookEvents(delivery)).isZero();
    }

    // ----------------------------------------------------------- the settlement

    @Test
    @DisplayName("A charge whose response was lost still confirms the order and queues the ticket")
    void settlesAnOrderStrandedMidCharge() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        // Exactly the state a killed replica leaves: the order row committed as PENDING, the money
        // already gone. Nothing in this system can resolve it on its own.
        fixture.strandPendingOrder(holdToken);

        String body = StripeWebhooks.settledBody(eventId(), "pi_settled", holdToken, 15_000);
        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        // The ticket has to follow. A confirmed order whose fulfilment was never queued is not a
        // state this system is allowed to reach, on this path any more than on the synchronous one.
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));
    }

    @Test
    @DisplayName("The same delivery twice settles once — three replicas see the same claim")
    void replaysAreClaimedOnce() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);
        fixture.strandPendingOrder(holdToken);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(deliveryId, "pi_replayed", holdToken, 7_500);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);
        // Redelivered: same event id, freshly signed, exactly as the provider retries.
        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        assertThat(fixture.countWebhookEvents(deliveryId)).isEqualTo(1);
        assertThat(fixture.webhookProcessed(deliveryId)).isTrue();
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        // The second settlement would have consumed an already-consumed hold and queued a second
        // ticket for one purchase.
        assertThat(fixture.countOutbox("PENDING") + fixture.countOutbox("PROCESSED")).isEqualTo(1);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A delivery for a hold that is already gone is refunded, not confirmed (ADR-012)")
    void refundsWhenTheSeatsAreGone() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);
        fixture.strandPendingOrder(holdToken);

        // The hold expired during exactly the disconnect that made this webhook necessary, and the
        // sweeper has already put those seats back on sale. Someone else may own them by now.
        fixture.expireHold(holdToken);
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(fixture.holdStatus(holdToken)).isEqualTo("EXPIRED"));

        String body = StripeWebhooks.settledBody(eventId(), "pi_too_late", holdToken, 15_000);
        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        // Confirming here would charge one customer for inventory another already holds.
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("REFUNDED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        // And the buyer is told, rather than finding out from their bank statement.
        await().atMost(PATIENCE)
        .untilAsserted(() ->
                assertThat(fixture.countOutbox("ORDER_REFUNDED", "PROCESSED")).isEqualTo(1));

        assertThat(fixture.countOutbox("ORDER_CONFIRMED", "PROCESSED")).isZero();
    }

    @Test
    @DisplayName("A delivery that arrives after the buyer already succeeded changes nothing")
    void aLateDeliveryAgainstACompletedPurchaseIsInert() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        var purchase = buyer.post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(purchase.status()).isEqualTo(201);

        String gatewayReference = fixture.gatewayReferenceFor(holdToken);
        String body = StripeWebhooks.settledBody(eventId(), gatewayReference, holdToken, 7_500);

        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        // The synchronous path already consumed the hold. Re-settling would try to consume it again.
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.countOrders()).isEqualTo(1);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A verified webhook increments the received metric by event type")
    void recordsVerifiedWebhookByEventType() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);
        fixture.strandPendingOrder(holdToken);

        String delivery = eventId();
        String body = StripeWebhooks.settledBody(
                delivery,
                "pi_metric_test",
                holdToken,
                7_500);

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
    // ----------------------------------------------------------------- helpers

    private BuyerSession.Response post(String rawBody, String signature) {
        return new BuyerSession(port)
                .postRaw(WEBHOOK, rawBody, Map.of("Stripe-Signature", signature));
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
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
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
                "idempotencyKey", "webhook-" + holdToken);
    }
}
