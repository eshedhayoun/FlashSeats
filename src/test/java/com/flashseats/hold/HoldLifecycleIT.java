package com.flashseats.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.facade.HoldFacade;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The settle-once claim: a hold's seats come back <strong>exactly once</strong>, however it ends and
 * however many callers race to end it.
 *
 * <p>This is the guarantee that lets consume, release, expiry and the sweeper all exist without any
 * coordination between them.
 */
@DisplayName("A hold's seats are restored exactly once, whatever ends it")
class HoldLifecycleIT extends IntegrationTest {

    private static final int CAPACITY = 20;

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private HoldFacade holds;

    @Autowired
    private TransactionTemplate transactions;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Lifecycle Test");
        tierId = fixture.tier(eventId, "Floor", 4_500, CAPACITY);
    }

    @Test
    @DisplayName("Consuming a hold twice: the second caller is told the claim is already spent")
    void doubleConsumeIsRejected() {
        String holdToken = newHold(2);

        transactions.executeWithoutResult(tx -> holds.consumeHold(holdToken));

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> holds.consumeHold(holdToken)))
                .isInstanceOf(HoldAlreadySettledException.class);

        // Consumed seats are sold, so they must NOT return to the pool.
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 2);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("Ten threads releasing the same hold restore its seats once, not ten times")
    void concurrentReleasesRestoreOnce() throws Exception {
        String holdToken = newHold(3);
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 3);

        int racers = 10;
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    startLine.await();
                    holds.releaseHold(holdToken, "test");
                    completed.incrementAndGet();
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(completed.get()).isEqualTo(racers);
        // The number that matters: restored once, by whichever caller won the claim.
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("RELEASED");
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("An abandoned hold is reclaimed by the sweeper and its seats come back")
    void sweeperReclaimsAbandonedHolds() {
        String holdToken = newHold(4);
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 4);

        // The buyer walked away; their window passes.
        fixture.expireHold(holdToken);

        // No listener, no timer, no coordination — the sweeper alone makes expiry correct.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(fixture.holdStatus(holdToken)).isEqualTo("EXPIRED");
            assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
        });

        // And it does not keep restoring them on every subsequent pass.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY));
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A session may hold seats for an event only once")
    void oneActiveHoldPerSessionPerEvent() {
        BuyerSession buyer = admittedBuyer();
        String admission = admissionToken;

        var first = reserve(buyer, admission, 2);
        assertThat(first.status()).isEqualTo(201);

        var second = reserve(buyer, admission, 2);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.errorCode()).isEqualTo("HOLD_LIMIT_EXCEEDED");

        // The rejected attempt must not have cost the tier any inventory: the decrement and the
        // insert share a transaction, so the constraint violation rolled both back.
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 2);
        assertThat(fixture.countHolds("ACTIVE")).isEqualTo(1);
    }

    @Test
    @DisplayName("Asking for more seats than the tier allows is refused without touching stock")
    void quantityAboveTheTierLimitIsRefused() {
        BuyerSession buyer = admittedBuyer();

        var refused = reserve(buyer, admissionToken, 7);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.errorCode()).isEqualTo("QUANTITY_EXCEEDS_LIMIT");
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("A missing inventory counter is a fault, never a sold-out sale")
    void missingCounterIsAFaultNotSoldOut() {
        long unwarmedTier = fixture.tierWithoutInventory(eventId, "Balcony", 3_000, 50);
        BuyerSession buyer = admittedBuyer();

        var response = buyer.post(
                "/holds",
                Map.of("eventId", eventId, "tierId", unwarmedTier, "quantity", 1),
                Map.of("X-Admission-Token", admissionToken));

        // 503, not 409. Rendering this as "sold out" would tell thousands of buyers the sale had
        // ended when a row was merely missing (ADR-004).
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.errorCode()).isEqualTo("INVENTORY_UNAVAILABLE");
        assertThat(response.json().get("retryable").asBoolean()).isTrue();
    }

    // ----------------------------------------------------------------- helpers

    private String admissionToken;

    private String newHold(int quantity) {
        BuyerSession buyer = admittedBuyer();
        return reserve(buyer, admissionToken, quantity).text("holdToken");
    }

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(Duration.ofSeconds(15))
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        admissionToken = buyer
                .post("/queue/admit?eventId=" + eventId, null, Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");
        return buyer;
    }

    private BuyerSession.Response reserve(BuyerSession buyer, String admission, int quantity) {
        return buyer.post(
                "/holds",
                Map.of("eventId", eventId, "tierId", tierId, "quantity", quantity),
                Map.of("X-Admission-Token", admission));
    }
}
