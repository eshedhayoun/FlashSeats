package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.dto.CheckoutRequest;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.PaymentResult;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The decisions checkout makes when the hold turns out to be gone, or the commit fails. The
 * compare-and-set that makes them safe is proven against PostgreSQL in {@code SettlementArbiterIT};
 * this pins which way each failure is routed (ADR-064).
 */
@DisplayName("Checkout answers a lost hold from the order row, and never refunds on ambiguity")
class CheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final String HOLD = "hld-1";
    private static final String ORDER = "TK-00001";

    private final HoldFacade holds = mock(HoldFacade.class);
    private final CatalogFacade catalog = mock(CatalogFacade.class);
    private final PaymentFacade payments = mock(PaymentFacade.class);
    private final OrderCommitService commit = mock(OrderCommitService.class);
    private final OrderRefundService refunds = mock(OrderRefundService.class);
    private final OrderQueryService queries = mock(OrderQueryService.class);

    private final CheckoutService checkout = new CheckoutService(
            holds, catalog, payments, commit, refunds, queries, new OrderProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final HoldSummary hold =
            new HoldSummary(HOLD, "sid", 1L, 2L, 2, NOW.plusSeconds(300), NOW.minusSeconds(10));
    private final TierSummary tier = new TierSummary(
            1L, 2L, "VIP", 7_500, "USD", 6, "Fest", "Hall", NOW.plusSeconds(86_400),
            NOW.plusSeconds(3_600), EventWindowStatus.OPEN);
    private final PaymentResult settled =
            new PaymentResult("pt_1", true, "pi_1", null, null, null, false, false);
    private final OrderReceiptResponse receipt = new OrderReceiptResponse(
            ORDER, OrderStatus.CONFIRMED, "b@example.com", 15_000, "USD", "tok", NOW, List.of());

    @BeforeEach
    void aHoldThatChargesSuccessfully() {
        when(queries.findConfirmedReceiptFor(HOLD)).thenReturn(Optional.empty());
        when(holds.getActiveHold(HOLD, "sid")).thenReturn(hold);
        when(catalog.getTierSummary(1L, 2L)).thenReturn(tier);
        when(commit.findOrCreate(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new CheckoutOrder(ORDER, 0, false));
        when(holds.grantGrace(HOLD)).thenReturn(NOW.plusSeconds(420));
        when(payments.authorize(any())).thenReturn(settled);
    }

    @Test
    @DisplayName("The webhook confirmed this charge first: the buyer gets the receipt, and nothing is refunded")
    void lostClaimToAConfirmationReplaysTheReceipt() {
        when(commit.confirm(ORDER, hold, tier, settled)).thenThrow(new HoldAlreadySettledException(HOLD));
        when(refunds.refund(anyString(), any(), anyLong(), anyString()))
                .thenReturn(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);
        when(queries.findConfirmedReceiptFor(HOLD)).thenReturn(Optional.empty()).thenReturn(Optional.of(receipt));

        CheckoutOutcome outcome = checkout.checkout("sid", request());

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.receipt()).isEqualTo(receipt);
    }

    @Test
    @DisplayName("The reservation ended under the charge: the refund claim wins and the buyer is told")
    void lostClaimToAnExpiryRefunds() {
        when(commit.confirm(ORDER, hold, tier, settled)).thenThrow(new HoldAlreadySettledException(HOLD));
        when(refunds.refund(ORDER, "pt_1", 15_000, "the reservation ended before the order could be confirmed"))
                .thenReturn(OrderRefundService.Outcome.REFUNDED);

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOfSatisfying(FlashSeatsException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.ORDER_REFUNDED));
    }

    @Test
    @DisplayName("An order the webhook refunded first is reported as refunded, with no second refund")
    void orderRefundedByTheOtherPathIsReportedAsRefunded() {
        when(commit.confirm(ORDER, hold, tier, settled))
                .thenThrow(new OptimisticLockingFailureException("Order TK-00001 is already REFUNDED"));
        when(refunds.refund(anyString(), any(), anyLong(), anyString()))
                .thenReturn(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOfSatisfying(FlashSeatsException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.ORDER_REFUNDED));
    }

    @Test
    @DisplayName("A refund the provider refused is reported as such, never as refunded")
    void aRefusedRefundIsReportedHonestly() {
        when(commit.confirm(ORDER, hold, tier, settled)).thenThrow(new HoldAlreadySettledException(HOLD));
        when(refunds.refund(anyString(), any(), anyLong(), anyString()))
                .thenReturn(OrderRefundService.Outcome.REFUND_FAILED);

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOfSatisfying(FlashSeatsException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.REFUND_FAILED));
    }

    @Test
    @DisplayName("Too little time to start a charge is refused, and nothing is charged")
    void tooLittleTimeRefusesANewCharge() {
        when(holds.grantGrace(HOLD)).thenReturn(NOW.plusSeconds(30));
        when(payments.hasChargeFor(HOLD)).thenReturn(false);

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOfSatisfying(FlashSeatsException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INSUFFICIENT_TIME_REMAINING));
        verify(payments, never()).authorize(any());
    }

    /**
     * Finishing 3-D Secure starts no new charge, so the time budget for starting one does not apply.
     * Refusing it answered "nothing was charged" about money that had moved (ADR-074).
     */
    @Test
    @DisplayName("Too little time is no reason to refuse completing a charge that already exists")
    void completingAnExistingChargeIsNotRefusedForTime() {
        when(holds.grantGrace(HOLD)).thenReturn(NOW.plusSeconds(30));
        when(payments.hasChargeFor(HOLD)).thenReturn(true);
        when(commit.confirm(ORDER, hold, tier, settled)).thenReturn(receipt);

        assertThat(checkout.checkout("sid", request()).receipt()).isEqualTo(receipt);
    }

    @Test
    @DisplayName("A commit that failed for no stated reason moves no money and leaves the order resumable")
    void ambiguousCommitFailureIsNeverRefunded() {
        when(commit.confirm(ORDER, hold, tier, settled))
                .thenThrow(new CannotCreateTransactionException("pool exhausted"));

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOf(CannotCreateTransactionException.class);

        verify(refunds, never()).refund(anyString(), any(), anyLong(), anyString());
        verify(commit).markAbandoned(ORDER, "pool exhausted");
    }

    @Test
    @DisplayName("A hold gone at step 1 because the purchase completed answers with the receipt")
    void holdGoneBecauseThePurchaseCompletedReplays() {
        when(holds.getActiveHold(HOLD, "sid")).thenThrow(new HoldExpiredException(HOLD, NOW));
        when(queries.findConfirmedReceiptFor(HOLD)).thenReturn(Optional.empty()).thenReturn(Optional.of(receipt));

        assertThat(checkout.checkout("sid", request()).receipt()).isEqualTo(receipt);
        verify(payments, never()).authorize(any());
    }

    @Test
    @DisplayName("A hold gone at the grace step with no confirmed order is still 'expired', and nothing is charged")
    void holdGoneAtGraceWithNoPurchaseIsExpired() {
        when(holds.grantGrace(HOLD)).thenThrow(new HoldExpiredException(HOLD, NOW));

        assertThatThrownBy(() -> checkout.checkout("sid", request()))
                .isInstanceOf(HoldExpiredException.class);

        verify(payments, never()).authorize(any());
        verify(refunds, never()).refund(anyString(), any(), anyLong(), anyString());
        verify(commit).markAbandoned(any(), any());
    }

    private static CheckoutRequest request() {
        return new CheckoutRequest(HOLD, "b@example.com", "pm_card_visa", "idem-1");
    }
}
