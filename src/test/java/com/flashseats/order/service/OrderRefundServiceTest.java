package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;

class OrderRefundServiceTest {

    private final PaymentFacade payments = mock(PaymentFacade.class);
    private final OrderCommitService commit = mock(OrderCommitService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final OrderRefundService refunds =
            new OrderRefundService(payments, commit, meters);

    @Test
    void theClaimComesBeforeTheMoneyAndTheNoticeAfterIt() {
        when(commit.claimRefund("TK-00001", "hold expired")).thenReturn(true);
        when(payments.refund("tx-1", 7_500, "hold expired"))
                .thenReturn(new RefundResult("tx-1", true, 7_500, null));

        assertThat(refunds.refund("TK-00001", "tx-1", 7_500, "hold expired")).isTrue();

        var order = inOrder(commit, payments);
        order.verify(commit).claimRefund("TK-00001", "hold expired");
        order.verify(payments).refund("tx-1", 7_500, "hold expired");
        order.verify(commit).recordRefunded("TK-00001", "hold expired");
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isZero();
    }

    @Test
    void anOrderTheOtherPathResolvedMovesNoMoney() {
        // The claim fails when the order is already CONFIRMED (or refunded by the other path).
        when(commit.claimRefund("TK-00004", "hold expired")).thenReturn(false);

        assertThat(refunds.refund("TK-00004", "tx-4", 7_500, "hold expired")).isFalse();

        verify(payments, never()).refund(anyString(), anyLong(), anyString());
        verify(commit, never()).recordRefunded(anyString(), anyString());
        verify(commit, never()).recordRefundFailure(anyString(), anyString());
    }

    @Test
    void aRefusedRefundIsCountedAndNeverAnnouncedToTheBuyer() {
        when(commit.claimRefund("TK-00002", "hold expired")).thenReturn(true);
        when(payments.refund("tx-2", 7_500, "hold expired"))
                .thenReturn(new RefundResult("tx-2", false, 0, "provider rejected refund"));

        assertThat(refunds.refund("TK-00002", "tx-2", 7_500, "hold expired")).isTrue();

        verify(commit).recordRefundFailure("TK-00002", "refund failed: provider rejected refund");
        verify(commit, never()).recordRefunded(anyString(), anyString());
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMissingLedgerRowIsCountedAndNeverAnnouncedToTheBuyer() {
        when(commit.claimRefund("TK-00003", "hold expired")).thenReturn(true);

        assertThat(refunds.refund("TK-00003", null, 7_500, "hold expired")).isTrue();

        verify(payments, never()).refund(anyString(), anyLong(), anyString());
        verify(commit).recordRefundFailure("TK-00003", "refund not issued: no payment transaction found");
        verify(commit, never()).recordRefunded(anyString(), anyString());
        assertThat(meters.get(OrderRefundService.FAILED_REFUNDS).counter().count()).isEqualTo(1.0);
    }
}
