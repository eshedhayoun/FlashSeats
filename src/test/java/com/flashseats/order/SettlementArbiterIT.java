package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.service.OrderCommitService;
import com.flashseats.order.service.OrderRefundService;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The checkout and the payment webhook settle the same charge, and can do it at the same moment.
 * The order row decides how it ends: confirming and claiming a refund are both compare-and-sets on
 * it, so exactly one of them wins (ADR-064). These run against PostgreSQL, because the guarantee is
 * the database's, not the code's.
 */
@DisplayName("A settled charge ends exactly one way: confirmed or refunded, never both")
class SettlementArbiterIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final long PRICE = 7_500;

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderCommitService commit;

    @Autowired
    private OrderRefundService refunds;

    @Autowired
    private HoldFacade holds;

    @Autowired
    private CatalogFacade catalog;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Arbiter Fest");
        tierId = fixture.tier(eventId, "VIP", PRICE, 60);
    }

    @Test
    @DisplayName("A refund attempted against a confirmed purchase moves no money and changes nothing")
    void aConfirmedPurchaseCannotBeRefunded() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve();
        var purchase = buyer.session().post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(purchase.status()).isEqualTo(201);

        String orderNumber = fixture.orderNumberFor(holdToken);
        String transactionReference = jdbc.queryForObject(
                "SELECT payment_transaction_ref FROM orders WHERE hold_token = ?", String.class, holdToken);

        // What the webhook's refund arm does when it lost the race to this confirmation.
        boolean refunded = refunds.refund(orderNumber, transactionReference, PRICE, "late webhook");

        assertThat(refunded).isFalse();
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
        assertThat(fixture.refundedAmountFor(holdToken)).isZero();
        assertThat(outboxRows(orderNumber, "ORDER_REFUNDED")).isZero();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A confirmation attempted against a refunded order takes no seats")
    void aRefundedOrderCannotBeConfirmed() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve();
        fixture.strandPendingOrder(holdToken, PRICE, "TK-REFUNDED-FIRST");
        String transactionReference = fixture.seedSettledPayment(holdToken, "pi_refunded_first", PRICE);

        assertThat(refunds.refund("TK-REFUNDED-FIRST", transactionReference, PRICE, "reservation ended")).isTrue();

        HoldSummary hold = activeHold(holdToken);
        assertThatThrownBy(() -> commit.confirm("TK-REFUNDED-FIRST", hold, tier(), settled(transactionReference)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(fixture.orderStatus(holdToken)).isEqualTo("REFUNDED");
        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.refundedAmountFor(holdToken)).isEqualTo(PRICE);
        assertThat(outboxRows("TK-REFUNDED-FIRST", "ORDER_CONFIRMED")).isZero();
        assertThat(outboxRows("TK-REFUNDED-FIRST", "ORDER_REFUNDED")).isEqualTo(1);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("Confirm and refund racing for one charge: exactly one wins, every time")
    void confirmAndRefundRaceToExactlyOneEnding() throws Exception {
        try (ExecutorService pair = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 15; round++) {
                Admitted buyer = admittedBuyer();
                String holdToken = buyer.reserve();
                String orderNumber = "TK-RACE-" + round;
                fixture.strandPendingOrder(holdToken, PRICE, orderNumber);
                String transactionReference = fixture.seedSettledPayment(holdToken, "pi_race_" + round, PRICE);
                HoldSummary hold = activeHold(holdToken);
                TierSummary tier = tier();

                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> confirmed = pair.submit(() -> {
                    start.await();
                    try {
                        commit.confirm(orderNumber, hold, tier, settled(transactionReference));
                        return true;
                    } catch (OptimisticLockingFailureException lost) {
                        return false;
                    }
                });
                // Confirming does more work before it reaches the order row than claiming a refund
                // does, so an unstaggered race is always won by the refund. The stagger walks the
                // claim across the whole confirm transaction, so both orderings and the overlaps
                // between them are exercised.
                long stagger = (round % 5) * 4L;
                Future<Boolean> refunded = pair.submit(() -> {
                    start.await();
                    Thread.sleep(stagger);
                    return refunds.refund(orderNumber, transactionReference, PRICE, "race");
                });
                start.countDown();

                boolean confirmWon = confirmed.get(10, TimeUnit.SECONDS);
                boolean refundWon = refunded.get(10, TimeUnit.SECONDS);

                assertThat(confirmWon ^ refundWon)
                        .as("round %d: exactly one ending (confirmed=%s, refunded=%s)", round, confirmWon, refundWon)
                        .isTrue();
                if (confirmWon) {
                    assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
                    assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
                    assertThat(fixture.refundedAmountFor(holdToken)).isZero();
                    assertThat(outboxRows(orderNumber, "ORDER_REFUNDED")).isZero();
                } else {
                    assertThat(fixture.orderStatus(holdToken)).isEqualTo("REFUNDED");
                    assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
                    assertThat(fixture.refundedAmountFor(holdToken)).isEqualTo(PRICE);
                    assertThat(outboxRows(orderNumber, "ORDER_CONFIRMED")).isZero();
                }
            }
        }
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A retry after a commit that proved nothing confirms the charge that settled, without a second")
    void aRetryReusesTheSettledCharge() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve();
        // The state an ambiguous commit failure leaves: charged, then abandoned, hold still live.
        fixture.strandPendingOrder(holdToken, PRICE, "TK-AMBIGUOUS");
        String transactionReference = fixture.seedSettledPayment(holdToken, "pi_ambiguous_commit", PRICE);
        jdbc.update("UPDATE orders SET status = 'FAILED' WHERE order_number = 'TK-AMBIGUOUS'");

        var retry = buyer.session().post("/orders/checkout", checkout(holdToken, "pm_card_visa"));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("orderNumber")).isEqualTo("TK-AMBIGUOUS");
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT payment_transaction_ref FROM orders WHERE hold_token = ?", String.class, holdToken))
                .isEqualTo(transactionReference);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    // ----------------------------------------------------------------- helpers

    private record Admitted(BuyerSession session, String admissionToken, long eventId, long tierId) {

        String reserve() {
            return session.post(
                            "/holds",
                            Map.of("eventId", eventId, "tierId", tierId, "quantity", 1),
                            Map.of("X-Admission-Token", admissionToken))
                    .text("holdToken");
        }
    }

    private Admitted admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"), token -> token != null);

        String admissionToken = buyer
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");
        return new Admitted(buyer, admissionToken, eventId, tierId);
    }

    private HoldSummary activeHold(String holdToken) {
        String sessionId = jdbc.queryForObject(
                "SELECT user_session_id FROM ticket_holds WHERE hold_token = ?", String.class, holdToken);
        return holds.getActiveHold(holdToken, sessionId);
    }

    private TierSummary tier() {
        return catalog.getTierSummary(eventId, tierId);
    }

    private static PaymentResult settled(String transactionReference) {
        return new PaymentResult(transactionReference, true, "pi_settled", null, null, null, false, false);
    }

    private int outboxRows(String orderNumber, String eventType) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                orderNumber,
                eventType);
        return rows == null ? 0 : rows;
    }

    private static Map<String, Object> checkout(String holdToken, String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "arbiter-" + holdToken);
    }
}
