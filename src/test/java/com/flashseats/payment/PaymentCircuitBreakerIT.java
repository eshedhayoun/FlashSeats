package com.flashseats.payment;

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
import org.springframework.test.annotation.DirtiesContext;

/**
 * Verifies the complete checkout behaviour when the payment provider is unavailable.
 *
 * <p>The lower-level {@code CircuitBreakingGatewayTest} proves that the Resilience4j decorator opens
 * correctly. This integration test proves the behaviour visible to the buyer: repeated provider
 * failures eventually open the breaker, checkout returns 503, the hold remains ACTIVE, and no
 * payment attempt is consumed.
 *
 * <p>The test dirties the Spring context because the circuit breaker is a singleton bean. Without
 * that, opening it here could affect later integration tests running against the same application
 * context.
 */
@DisplayName("The payment circuit breaker protects checkout during a provider outage")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCircuitBreakerIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    /**
     * Production configuration:
     *
     * - sliding window: 20 calls
     * - minimum calls: 20
     * - failure threshold: 50%
     *
     * Twenty definite transport failures therefore open the breaker.
     */
    private static final int CALLS_TO_OPEN_CIRCUIT = 20;

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

        eventId = fixture.openEvent("Circuit Breaker Fest");

        /*
         * Every failed payment keeps its hold ACTIVE.
         * We therefore need one ticket for each failure plus one more ticket
         * for the final request that proves the circuit is open.
         */
        tierId = fixture.tier(
                eventId,
                "VIP",
                7_500,
                CALLS_TO_OPEN_CIRCUIT + 1);
    }

    @Test
    @DisplayName("Repeated provider failures open the circuit and retain the buyer's hold")
    void providerOutageOpensCircuitAndKeepsHold() {
        /*
         * Build twenty separate real buyer journeys. Each buyer gets:
         *
         * event page -> queue join -> promotion -> admission -> hold
         *
         * Then checkout uses pm_card_error, which the default StubPaymentGateway
         * turns into GatewayTransportException. CircuitBreakingGateway retries
         * the transport failure and eventually records the logical call as a
         * circuit-breaker failure.
         */
        for (int i = 0; i < CALLS_TO_OPEN_CIRCUIT; i++) {
            BuyerSession buyer = admittedBuyer();
            String holdToken = reserve(buyer, 1);

            var response =
                    buyer.post(
                            "/orders/checkout",
                            checkout(holdToken, "pm_card_error"));

            assertThat(response.status()).isEqualTo(503);
            assertThat(response.errorCode())
                    .isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");

            /*
             * Provider outage is not the buyer's fault.
             * The order layer therefore retains the hold.
             */
            assertThat(fixture.holdStatus(holdToken))
                    .isEqualTo("ACTIVE");

            /*
             * Gateway transport failure must not consume one of the buyer's
             * three payment attempts.
             */
            assertThat(fixture.paymentAttemptsFor(holdToken))
                    .isZero();
        }

        /*
         * The twentieth logical gateway failure has now satisfied the
         * production breaker configuration and opened the circuit.
         *
         * Use a fresh buyer and a fresh hold so the next checkout proves
         * that the circuit itself is rejecting the call.
         */
        BuyerSession circuitOpenBuyer = admittedBuyer();
        String circuitOpenHold = reserve(circuitOpenBuyer, 1);

        /*
         * Use a normally successful payment method here. If the breaker were
         * closed, this would succeed. Because the breaker is open, it must
         * return the normal gateway-unavailable path without reaching the
         * provider.
         */
        var refused =
                circuitOpenBuyer.post(
                        "/orders/checkout",
                        checkout(circuitOpenHold, "pm_card_visa"));

        assertThat(refused.status()).isEqualTo(503);
        assertThat(refused.errorCode())
                .isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(refused.json().get("retryable").asBoolean())
                .isTrue();

        /*
         * The open-circuit path must retain the buyer's seats.
         */
        assertThat(fixture.holdStatus(circuitOpenHold))
                .isEqualTo("ACTIVE");

        /*
         * No payment attempt is consumed while the provider is unavailable.
         */
        assertThat(fixture.paymentAttemptsFor(circuitOpenHold))
                .isZero();

        /*
         * None of the twenty-one reserved tickets may disappear.
         */
        assertThat(fixture.stockInvariantHolds(tierId))
                .isTrue();
    }

    // ----------------------------------------------------------------- helpers

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);

        buyer.get("/events/" + eventId);

        buyer.post(
                "/queue/join",
                Map.of("eventId", eventId));

        String passToken =
                await().atMost(PATIENCE)
                        .until(
                                () -> buyer
                                        .get("/queue/status?eventId=" + eventId)
                                        .text("passToken"),
                                token -> token != null);

        admissionToken =
                buyer.post(
                                "/queue/admit",
                                Map.of("eventId", eventId),
                                Map.of(
                                        "X-Queue-Pass-Token",
                                        passToken))
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
                        Map.of(
                                "X-Admission-Token",
                                admissionToken))
                .text("holdToken");
    }

    private Map<String, Object> checkout(
            String holdToken,
            String paymentMethodId) {

        return Map.of(
                "holdToken",
                holdToken,
                "userEmail",
                "buyer@example.com",
                "paymentMethodId",
                paymentMethodId,
                "idempotencyKey",
                "circuit-" + holdToken);
    }
}