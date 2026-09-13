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

/**
 * A settlement that fails for an unknown reason must not be treated as "the seats are gone".
 *
 * <p>The first version of this path caught {@code RuntimeException} around the whole settlement, so
 * a pool timeout, an unreadable inventory counter or any commit blip refunded a buyer whose seats
 * were perfectly fine — and then answered the provider {@code 200}, so nothing ever retried and the
 * mistake was permanent.
 *
 * <p>ADR-046 already drew this line for inventory: a <em>definite</em> rollback is safe to
 * compensate, an <em>ambiguous</em> failure is not. This is the same line, reaching money.
 *
 * <p>The failure is staged by removing the tier row for the duration of the delivery. Nothing
 * references {@code ticket_tiers} from {@code ticket_holds}, so the hold stays live while the
 * catalog can no longer describe what it holds — a failure that is emphatically <em>not</em> "the
 * seats are gone", which is the only kind that may move money.
 */
@DisplayName("An ambiguous settlement failure is retried, never refunded")
class WebhookAmbiguousFailureIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final String WEBHOOK = "/payments/webhook";

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
        eventId = fixture.openEvent("Ambiguity Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("It answers non-2xx, releases the claim, and leaves the money where it is")
    void ambiguousFailureReleasesTheClaim() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);
        fixture.strandPendingOrder(holdToken);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(deliveryId, "pi_ambiguous", holdToken, 15_000);

        fixture.withTierRemoved(tierId, () -> {
            var refused = post(body, StripeWebhooks.signature(body));

            // ANY non-2xx earns a redelivery — the provider does not distinguish, and neither does
            // this contract. What matters is that it is not a 200, because a 200 would end the
            // story on a guess. (The exact code is whatever the propagating exception carries.)
            assertThat(refused.ok()).isFalse();

            // NOT refunded. The buyer's seats are fine, and their money stays where it is until
            // something actually establishes otherwise.
            assertThat(fixture.orderStatus(holdToken)).isEqualTo("PENDING");
            assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
            assertThat(fixture.countOutbox("PENDING") + fixture.countOutbox("PROCESSED")).isZero();

            // And the claim is gone, so the provider's next attempt is not dismissed as a duplicate.
            assertThat(fixture.countWebhookEvents(deliveryId)).isZero();
        });

        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("The redelivery after an ambiguous failure settles normally")
    void theRetryThenSucceeds() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 2);
        fixture.strandPendingOrder(holdToken);

        String deliveryId = eventId();
        String body = StripeWebhooks.settledBody(deliveryId, "pi_recovered", holdToken, 15_000);

        fixture.withTierRemoved(tierId, () ->
                assertThat(post(body, StripeWebhooks.signature(body)).ok()).isFalse());

        // The provider retries, and by now the trouble has passed. This is the whole reason for
        // releasing the claim — one that outlived its failed work would make this delivery a no-op
        // and strand a paid buyer for good (ADR-038).
        assertThat(post(body, StripeWebhooks.signature(body)).status()).isEqualTo(200);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.countWebhookEvents(deliveryId)).isEqualTo(1);
        assertThat(fixture.webhookProcessed(deliveryId)).isTrue();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(fixture.countOutbox("PROCESSED")).isEqualTo(1));
    }

    // ----------------------------------------------------------------- helpers

    private BuyerSession.Response post(String rawBody, String signature) {
        return new BuyerSession(port).postRaw(WEBHOOK, rawBody, Map.of("Stripe-Signature", signature));
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
}
