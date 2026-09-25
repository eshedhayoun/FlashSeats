package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.RefundResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyLong;

class OrderRefundServiceTest {

    private final PaymentFacade payments = mock(PaymentFacade.class);
    private final OrderCommitService commit = mock(OrderCommitService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final OrderRefundService refunds =
            new OrderRefundService(payments, commit, meters);

    @Test
    void successfulRefundMarksOrderRefunded() {
        when(payments.refund("tx-1", 7_500, "hold expired"))
                .thenReturn(new RefundResult(
                        "tx-1",
                        true,
                        7_500,
                        null));

        refunds.refund(
                "TK-00001",
                "tx-1",
                7_500,
                "hold expired");

        verify(payments).refund("tx-1", 7_500, "hold expired");
        verify(commit).markRefunded("TK-00001", "hold expired");

        assertThat(
                meters.get(OrderRefundService.FAILED_REFUNDS).counter().count())
                .isZero();
    }

    @Test
    void failedRefundIsRecordedForManualReconciliation() {
        when(payments.refund("tx-2", 7_500, "hold expired"))
                .thenReturn(new RefundResult(
                        "tx-2",
                        false,
                        0,
                        "provider rejected refund"));

        refunds.refund(
                "TK-00002",
                "tx-2",
                7_500,
                "hold expired");

        verify(payments).refund("tx-2", 7_500, "hold expired");

        verify(commit).markRefunded(
                "TK-00002",
                "refund failed: provider rejected refund");

        assertThat(
                meters.get(OrderRefundService.FAILED_REFUNDS).counter().count())
                .isEqualTo(1);
    }

    @Test
    void missingPaymentTransactionIsAlsoReportedForManualReconciliation() {
        refunds.refund(
                "TK-00003",
                null,
                7_500,
                "webhook settled after ledger loss");

        verify(payments, never())
                .refund(anyString(), anyLong(), anyString());

        verify(commit).markRefunded(
                "TK-00003",
                "refund not issued: no payment transaction found");

        assertThat(
                meters.get(OrderRefundService.FAILED_REFUNDS).counter().count())
                .isEqualTo(1);
    }

}