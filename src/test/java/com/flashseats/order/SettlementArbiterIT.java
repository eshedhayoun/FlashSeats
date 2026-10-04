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
import com.flashseats.order.service.PaymentSettlementService;
import com.flashseats.order.service.SettledCharge;
import com.flashseats.payment.event.PaymentSettledEvent;
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
    private PaymentSettlementService settlement;

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
        String gatewayReference = jdbc.queryForObject(
                "SELECT stripe_payment_intent_id FROM orders WHERE hold_token = ?", String.class, holdToken);
        var outcome = refunds.refund(
                orderNumber, new SettledCharge(transactionReference, gatewayReference), PRICE, "late webhook");

        assertThat(outcome).isEqualTo(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);
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

        assertThat(refunds.refund(
                        "TK-REFUNDED-FIRST",
                        new SettledCharge(transactionReference, "pi_refunded_first"),
                        PRICE,
                        "reservation ended"))
                .isEqualTo(OrderRefundService.Outcome.REFUNDED);

        HoldSummary hold = activeHold(holdToken);
        assertThatThrownBy(() -> commit.confirm(
                        "TK-REFUNDED-FIRST", hold, tier(), settled(transactionReference, "pi_refunded_first")))
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
                String gatewayReference = "pi_race_" + round;
                fixture.strandPendingOrder(holdToken, PRICE, orderNumber);
                String transactionReference = fixture.seedSettledPayment(holdToken, gatewayReference, PRICE);
                HoldSummary hold = activeHold(holdToken);
                TierSummary tier = tier();

                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> confirmed = pair.submit(() -> {
                    start.await();
                    try {
                        commit.confirm(orderNumber, hold, tier, settled(transactionReference, gatewayReference));
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
                    return refunds.refund(
                                    orderNumber,
                                    new SettledCharge(transactionReference, gatewayReference),
                                    PRICE,
                                    "race")
                            == OrderRefundService.Outcome.REFUNDED;
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

    @Test
    @DisplayName("A retry resuming the order cannot fail a confirmation already under way")
    void aResumeNeverFailsAConfirmation() throws Exception {
        try (ExecutorService pair = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 15; round++) {
                Admitted buyer = admittedBuyer();
                String holdToken = buyer.reserve();
                String orderNumber = "TK-RESUME-" + round;
                String gatewayReference = "pi_resume_" + round;
                fixture.strandPendingOrder(holdToken, PRICE, orderNumber);
                String transactionReference = fixture.seedSettledPayment(holdToken, gatewayReference, PRICE);
                HoldSummary hold = activeHold(holdToken);
                TierSummary tier = tier();
                long version = jdbc.queryForObject(
                        "SELECT version FROM orders WHERE order_number = ?", Long.class, orderNumber);

                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> confirmed = pair.submit(() -> {
                    start.await();
                    try {
                        commit.confirm(orderNumber, hold, tier, settled(transactionReference, gatewayReference));
                        return true;
                    } catch (OptimisticLockingFailureException lost) {
                        return false;
                    }
                });
                // What a second request does when it judges this order stranded: put it back in
                // flight on the version it read (OrderRepository.resume). The order stays this
                // purchase, so the confirmation must stand, whichever lands first.
                long stagger = (round % 5) * 3L;
                Future<Integer> resumed = pair.submit(() -> {
                    start.await();
                    Thread.sleep(stagger);
                    return jdbc.update(
                            "UPDATE orders SET status = 'PENDING', version = version + 1, updated_at = now()"
                                    + " WHERE order_number = ? AND version = ?",
                            orderNumber,
                            version);
                });
                start.countDown();

                assertThat(confirmed.get(10, TimeUnit.SECONDS))
                        .as("round %d: the confirmation stands (resumed %d row)", round, resumed.get(10, TimeUnit.SECONDS))
                        .isTrue();
                assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
                assertThat(fixture.holdStatus(holdToken)).isEqualTo("CONSUMED");
            }
        }
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("A second charge for a purchase goes back, and the order keeps the charge it names")
    void aSecondChargeIsReturned() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve();
        var purchase = buyer.session().post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(purchase.status()).isEqualTo(201);
        String orderNumber = fixture.orderNumberFor(holdToken);
        String kept = jdbc.queryForObject(
                "SELECT payment_transaction_ref FROM orders WHERE hold_token = ?", String.class, holdToken);

        // What a checkout stalled past the in-flight guard leaves behind: its own settled charge,
        // reaching an order a retry already completed.
        String second = fixture.seedSettledPayment(holdToken, "pi_second_charge", PRICE);
        var outcome = refunds.refund(
                orderNumber, new SettledCharge(second, "pi_second_charge"), PRICE, "the reservation ended");

        assertThat(outcome).isEqualTo(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);
        assertThat(refundedAmount(second)).isEqualTo(PRICE);
        assertThat(refundedAmount(kept)).isZero();
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
        assertThat(outboxRows(orderNumber, "ORDER_REFUNDED")).isZero();
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("The webhook for a second charge returns it; the one for the order's own charge moves nothing")
    void theWebhookReturnsOnlyASecondCharge() {
        Admitted buyer = admittedBuyer();
        String holdToken = buyer.reserve();
        var purchase = buyer.session().post("/orders/checkout", checkout(holdToken, "pm_card_visa"));
        assertThat(purchase.status()).isEqualTo(201);
        String kept = jdbc.queryForObject(
                "SELECT payment_transaction_ref FROM orders WHERE hold_token = ?", String.class, holdToken);
        String keptIntent = jdbc.queryForObject(
                "SELECT stripe_payment_intent_id FROM orders WHERE hold_token = ?", String.class, holdToken);
        String second = fixture.seedSettledPayment(holdToken, "pi_webhook_second", PRICE);

        settlement.onPaymentSettled(new PaymentSettledEvent(holdToken, keptIntent, kept, PRICE, "USD"));
        assertThat(refundedAmount(kept)).isZero();

        settlement.onPaymentSettled(new PaymentSettledEvent(holdToken, "pi_webhook_second", second, PRICE, "USD"));
        assertThat(refundedAmount(second)).isEqualTo(PRICE);
        assertThat(refundedAmount(kept)).isZero();
        assertThat(fixture.orderStatus(holdToken)).isEqualTo("CONFIRMED");
    }

    // ----------------------------------------------------------------- helpers

    private long refundedAmount(String transactionReference) {
        return jdbc.queryForObject(
                "SELECT refunded_amount_cents FROM payment_transactions WHERE transaction_reference = ?",
                Long.class,
                transactionReference);
    }

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

    private static PaymentResult settled(String transactionReference, String gatewayReference) {
        return new PaymentResult(transactionReference, true, gatewayReference, null, null, null, false, false);
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
