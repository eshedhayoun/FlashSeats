
package com.flashseats.flashseats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@DisplayName("Checkout concurrency")
class CheckoutConcurrencyIT extends IntegrationTest {
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
        eventId = fixture.openEvent("Checkout Concurrency Test");
        tierId = fixture.tier(eventId, "VIP", 7_500, 10);
    }

    @Test
    @DisplayName("Two simultaneous checkouts for the same hold create one order and one charge")
    void simultaneousCheckoutProducesOneOrder() throws Exception {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);
        assertThat(fixture.remaining(tierId)).isEqualTo(9);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                awaitStart(start);
                return buyer.post(
                        "/orders/checkout",
                        checkout(holdToken, "pm_card_visa")
                ).status();
            });
            Future<Integer> second = executor.submit(() -> {
                awaitStart(start);
                return buyer.post(
                        "/orders/checkout",
                        checkout(holdToken, "pm_card_visa")
                ).status();
            });
            start.countDown();

            int status1 = first.get();
            int status2 = second.get();

            boolean firstSucceeded = status1 == 200 || status1 == 201;
            boolean secondSucceeded = status2 == 200 || status2 == 201;

            assertThat(firstSucceeded || secondSucceeded)
                    .as("At least one concurrent checkout must succeed")
                    .isTrue();
            assertThat(status1)
                    .as("First checkout status")
                    .isIn(200, 201, 409);

            assertThat(status2)
                    .as("Second checkout status")
                    .isIn(200, 201, 409);
            assertThat(fixture.countOrders()).isEqualTo(1);
            assertThat(fixture.countPaymentTransactions()).isEqualTo(1);

            assertThat(fixture.holdStatus(holdToken))
                    .isEqualTo("CONSUMED");

            assertThat(fixture.remaining(tierId))
                    .isEqualTo(9);

            assertThat(fixture.stockInvariantHolds(tierId))
                    .isTrue();
        }
    }

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post(
                "/queue/join",
                Map.of("eventId", eventId)
        );
        String passToken = await()
                .atMost(PATIENCE)
                .until(
                        () -> buyer
                                .get("/queue/status?eventId=" + eventId)
                                .text("passToken"),
                        token -> token != null
                );
        admissionToken = buyer
                .post(
                        "/queue/admit",
                        Map.of("eventId", eventId),
                        Map.of("X-Queue-Pass-Token", passToken)
                )
                .text("admissionToken");
        assertThat(admissionToken).isNotNull();
        return buyer;
    }

    private String reserve(BuyerSession buyer, int quantity) {
        return buyer
                .post(
                        "/holds",
                        Map.of(
                                "eventId", eventId,
                                "tierId", tierId,
                                "quantity", quantity
                        ),
                        Map.of("X-Admission-Token", admissionToken)
                )
                .text("holdToken");
    }

    private Map<String, Object> checkout(String holdToken,String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "test-" + holdToken
        );
    }

    private void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
    
}

