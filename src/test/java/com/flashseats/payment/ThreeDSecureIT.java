package com.flashseats.payment;

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
 * A bank challenge is a pause in one checkout, not a second checkout.
 *
 * <p>The retry mechanism is the ordinary one: re-POST the same {@code /orders/checkout} body. There
 * is no resume endpoint and there must not be one — a second retry path would have its own
 * idempotency story, and this system's whole guarantee is that there is only one (FE_SPEC §2).
 *
 * <p>The two things that go wrong here are both silent. Charging again on the resume bills the buyer
 * twice for one authentication; spending one of their three card attempts on a challenge their bank
 * asked for punishes them for their issuer's policy.
 */
@DisplayName("3-D Secure pauses a checkout and the same request finishes it")
class ThreeDSecureIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    /** The provider's own name for the test card that always demands a challenge. */
    private static final String CHALLENGE_CARD = "pm_card_authenticationRequired";

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
        eventId = fixture.openEvent("Challenge Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("The challenge is a 402 with a clientSecret, and it costs no attempt and no seats")
    void challengeKeepsTheSeatsAndTheAttempts() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        var challenge = buyer.post("/orders/checkout", checkout(holdToken, CHALLENGE_CARD));

        assertThat(challenge.status()).isEqualTo(402);
        assertThat(challenge.errorCode()).isEqualTo("PAYMENT_ACTION_REQUIRED");
        // What the browser hands to stripe.handleNextAction. Without it the client cannot run the
        // challenge at all, and the code is decoration.
        assertThat(challenge.text("clientSecret")).isNotBlank();
        assertThat(challenge.json().get("retryable").asBoolean()).isTrue();
        assertThat(challenge.text("expiresAt")).isNotBlank();

        // The seats are still theirs, and the charge is recorded as in flight rather than failed.
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.paymentStatusFor(holdToken)).isEqualTo("PROCESSING");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();

        // A bank challenge is not one of the buyer's three cards. If it consumed an attempt, two
        // challenged payments would leave them one try from being locked out of seats they hold.
        assertThat(fixture.paymentAttemptsFor(holdToken)).isZero();

        // The order is left resumable rather than in flight, which is what lets the re-POST after
        // the challenge continue on the same order number instead of meeting a 409 (ADR-034).
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("While a challenge is outstanding, every resubmission re-offers that same challenge")
    void anOutstandingChallengeIsNotBypassedByAnotherCard() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        var challenge = buyer.post("/orders/checkout", checkout(holdToken, CHALLENGE_CARD));
        assertThat(challenge.status()).isEqualTo(402);
        String intent = fixture.gatewayReferenceFor(holdToken);

        // A different card in the body changes nothing while the first intent is still awaiting
        // authentication: the resume re-reads THAT intent, because the alternative — starting a
        // second charge — is how a buyer ends up authenticating one payment and being billed for
        // two. The bound on this is the hold itself, which expires within minutes.
        buyer.post("/orders/checkout", checkout(holdToken, "pm_card_declined"));

        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.gatewayReferenceFor(holdToken)).isEqualTo(intent);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Re-POSTing the same body after the challenge completes the SAME charge")
    void resubmissionCompletesOneCharge() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);

        var challenge = buyer.post("/orders/checkout", checkout(holdToken, CHALLENGE_CARD));
        assertThat(challenge.status()).isEqualTo(402);
        String intentBeforeChallenge = fixture.gatewayReferenceFor(holdToken);
        assertThat(intentBeforeChallenge).isNotBlank();

        // The buyer completed the challenge in their browser and the client re-POSTs, unchanged —
        // same hold, same card, same idempotency key, exactly as FE_SPEC prescribes.
        var completed = buyer.post("/orders/checkout", checkout(holdToken, CHALLENGE_CARD));

        assertThat(completed.status()).isEqualTo(201);
        assertThat(completed.text("status")).isEqualTo("CONFIRMED");

        // ONE charge, not two. The resume has to retrieve the existing intent: the client reuses one
        // idempotency key for the life of the hold, so charging again would replay the provider's
        // cached "requires_action" for ever — and varying the key would open a second intent and
        // bill twice for one authentication.
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(fixture.gatewayReferenceFor(holdToken)).isEqualTo(intentBeforeChallenge);

        assertThat(fixture.countOrders()).isEqualTo(1);
        // Exactly one attempt against the buyer's three, for one purchase. The challenge itself
        // spent none, and the resume spent the one the confirmation earned.
        assertThat(fixture.paymentAttemptsFor(holdToken)).isEqualTo(1);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));
    }

    // ----------------------------------------------------------------- helpers

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

    /** One idempotency key per hold, reused on every retry — the client contract (FE_SPEC §1). */
    private Map<String, Object> checkout(String holdToken, String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "3ds-" + holdToken);
    }
}
