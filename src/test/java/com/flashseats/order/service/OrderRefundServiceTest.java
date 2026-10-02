package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.RefundResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderRefundServiceTest {

    private final PaymentFacade payments = mock(PaymentFacade.class);
    private final OrderCommitService commit = mock(OrderCommitService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final OrderRefundService refunds =
            new OrderRefundService(payments, commit, meters);

    @Test
    void theClaimComesBeforeTheMoneyAndTheNoticeAfterIt() {
        SettledCharge charge = new SettledCharge("tx-1", "pi_1");
        when(commit.claimRefund("TK-00001", charge, "hold expired")).thenReturn(true);
        when(payments.refund("tx-1", 7_500, "hold expired"))
                .thenReturn(new RefundResult("tx-1", true, 7_500, null));

        assertThat(refunds.refund("TK-00001", charge, 7_500, "hold expired")).isEqualTo(OrderRefundService.Outcome.REFUNDED);

        var order = inOrder(commit, payments);
        order.verify(commit).claimRefund("TK-00001", charge, "hold expired");
        order.verify(payments).refund("tx-1", 7_500, "hold expired");
        order.verify(commit).recordRefunded("TK-00001", "hold expired");
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isZero();
    }

    @Test
    void anOrderTheOtherPathResolvedWithThisChargeMovesNoMoney() {
        // The claim fails when the order is already CONFIRMED (or refunded by the other path), and
        // the row names this very charge: it is accounted for.
        SettledCharge charge = new SettledCharge("tx-4", "pi_4");
        when(commit.claimRefund("TK-00004", charge, "hold expired")).thenReturn(false);
        when(commit.chargeOfRecord("TK-00004")).thenReturn(Optional.of("pi_4"));

        assertThat(refunds.refund("TK-00004", charge, 7_500, "hold expired"))
                .isEqualTo(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);

        verify(payments, never()).refund(anyString(), anyLong(), anyString());
        verify(commit, never()).recordRefunded(anyString(), anyString());
        verify(commit, never()).recordRefundFailure(anyString(), anyString());
        assertThat(strays("returned")).isZero();
        assertThat(strays("unaccounted")).isZero();
    }

    @Test
    void aSecondChargeTheOrderDoesNotNameGoesBackWithoutTouchingTheOrder() {
        // Two checkouts for one hold both reached the provider; the order kept pi_kept.
        SettledCharge second = new SettledCharge("tx-5", "pi_second");
        when(commit.claimRefund("TK-00005", second, "hold expired")).thenReturn(false);
        when(commit.chargeOfRecord("TK-00005")).thenReturn(Optional.of("pi_kept"));
        when(payments.refund("tx-5", 7_500, "a second charge for one reservation"))
                .thenReturn(new RefundResult("tx-5", true, 7_500, null));

        assertThat(refunds.refund("TK-00005", second, 7_500, "hold expired"))
                .isEqualTo(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);

        verify(payments).refund("tx-5", 7_500, "a second charge for one reservation");
        verify(commit, never()).recordRefunded(anyString(), anyString());
        verify(commit, never()).recordRefundFailure(anyString(), anyString());
        assertThat(strays("returned")).isEqualTo(1.0);
    }

    @Test
    void anOrderThatNamesNoChargeIsLeftForAPersonNotGuessedAt() {
        SettledCharge charge = new SettledCharge("tx-6", "pi_6");
        when(commit.claimRefund("TK-00006", charge, "hold expired")).thenReturn(false);
        when(commit.chargeOfRecord("TK-00006")).thenReturn(Optional.empty());

        refunds.refund("TK-00006", charge, 7_500, "hold expired");

        verify(payments, never()).refund(anyString(), anyLong(), anyString());
        assertThat(strays("unaccounted")).isEqualTo(1.0);
    }

    @Test
    void aSecondChargeWithNoLedgerRowIsLeftForAPerson() {
        SettledCharge unknown = new SettledCharge(null, "pi_unknown");

        refunds.returnIfStray("TK-00007", "pi_kept", unknown, 7_500);

        verify(payments, never()).refund(any(), anyLong(), anyString());
        assertThat(strays("unaccounted")).isEqualTo(1.0);
    }

    @Test
    void aRefusedStrayRefundIsCounted() {
        SettledCharge second = new SettledCharge("tx-8", "pi_second");
        when(payments.refund("tx-8", 7_500, "a second charge for one reservation"))
                .thenReturn(new RefundResult("tx-8", false, 0, "provider rejected refund"));

        refunds.returnIfStray("TK-00008", "pi_kept", second, 7_500);

        assertThat(strays("refund_failed")).isEqualTo(1.0);
        assertThat(strays("returned")).isZero();
    }

    @Test
    void aRefusedRefundIsCountedAndNeverAnnouncedToTheBuyer() {
        SettledCharge charge = new SettledCharge("tx-2", "pi_2");
        when(commit.claimRefund("TK-00002", charge, "hold expired")).thenReturn(true);
        when(payments.refund("tx-2", 7_500, "hold expired"))
                .thenReturn(new RefundResult("tx-2", false, 0, "provider rejected refund"));

        assertThat(refunds.refund("TK-00002", charge, 7_500, "hold expired"))
                .isEqualTo(OrderRefundService.Outcome.REFUND_FAILED);

        verify(commit).recordRefundFailure("TK-00002", "refund failed: provider rejected refund");
        verify(commit, never()).recordRefunded(anyString(), anyString());
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMissingLedgerRowIsCountedAndNeverAnnouncedToTheBuyer() {
        SettledCharge charge = new SettledCharge(null, "pi_3");
        when(commit.claimRefund("TK-00003", charge, "hold expired")).thenReturn(true);

        assertThat(refunds.refund("TK-00003", charge, 7_500, "hold expired"))
                .isEqualTo(OrderRefundService.Outcome.REFUND_FAILED);

        verify(payments, never()).refund(anyString(), anyLong(), anyString());
        verify(commit).recordRefundFailure("TK-00003", "refund not issued: no payment transaction found");
        verify(commit, never()).recordRefunded(anyString(), anyString());
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isEqualTo(1.0);
    }

    private double strays(String outcome) {
        return meters.get(OrderRefundService.STRAY_CHARGES).tag("outcome", outcome).counter().count();
    }
}
