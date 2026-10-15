package com.flashseats.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.PaymentGateway;
import com.flashseats.payment.model.PaymentTransaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PaymentRefundTest {

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentTransactionStore store = mock(PaymentTransactionStore.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final PaymentService payments;

    PaymentRefundTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        payments = new PaymentService(
                gateway,
                store,
                redis,
                new PaymentProperties(),
                meters);
    }

    @Test
    void failedRefundIsNotRecordedAsRefunded() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionReference("tx_1");
        transaction.setGatewayReference("pi_123");
        transaction.setRefundedAmountCents(0);

        when(store.require("tx_1")).thenReturn(transaction);

        when(gateway.refund(
                "pi_123",
                7_500,
                "hold expired"))
                .thenReturn(
                        GatewayResult.error(
                                "refund_failed",
                                "The refund provider rejected the refund."));

        var result = payments.refund(
                "tx_1",
                7_500,
                "hold expired");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.refundedAmountCents()).isZero();
        assertThat(result.failureReason())
                .isEqualTo("The refund provider rejected the refund.");

        // Most important assertion:
        // a failed refund must NEVER move the durable payment record to REFUNDED.
        verify(store, never()).recordRefund("tx_1", 7_500);
    }
    @Test
    void successfulRefundIsRecordedWithTheExactAmount() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionReference("tx_1");
        transaction.setGatewayReference("pi_123");
        transaction.setRefundedAmountCents(0);

        when(store.require("tx_1")).thenReturn(transaction);

        when(gateway.refund("pi_123",7_500,"hold expired")).thenReturn(GatewayResult.succeeded("re_123"));

        var result = payments.refund("tx_1",7_500,"hold expired");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.transactionReference()).isEqualTo("tx_1");
        assertThat(result.refundedAmountCents()).isEqualTo(7_500);
        assertThat(result.failureReason()).isNull();

        // The durable payment record must be updated only after the
        // provider confirmed the refund.
        verify(store).recordRefund("tx_1", 7_500);
    }
        @Test
        void alreadyRefundedPaymentDoesNotCallGatewayAgain() {

        PaymentTransaction transaction = new PaymentTransaction();

        transaction.setTransactionReference("tx_1");
        transaction.setGatewayReference("pi_123");
        transaction.setRefundedAmountCents(7_500);

        when(store.require("tx_1"))
                .thenReturn(transaction);

        var result =
                payments.refund(
                        "tx_1",
                        7_500,
                        "hold expired");

        assertThat(result.succeeded())
                .isTrue();

        assertThat(result.transactionReference())
                .isEqualTo("tx_1");

        assertThat(result.refundedAmountCents())
                .isEqualTo(7_500);

        verify(gateway, never())
                .refund(
                        "pi_123",
                        7_500,
                        "hold expired");

        verify(store, never())
                .recordRefund(
                        "tx_1",
                        7_500);
        }
}
