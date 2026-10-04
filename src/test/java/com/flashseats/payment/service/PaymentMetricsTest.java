package com.flashseats.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.PaymentGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.mockito.ArgumentMatchers.anyString;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


import com.flashseats.payment.gateway.GatewayCharge;
import org.mockito.ArgumentCaptor;

class PaymentMetricsTest {

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentTransactionStore store = mock(PaymentTransactionStore.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentService payments;

    PaymentMetricsTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        payments = new PaymentService(gateway, store, redis, new PaymentProperties(), meters);
    }

    private double attempts(String outcome) {
        return meters.get("flashseats.payment.attempts").tag("outcome", outcome).counter().count();
    }

    @Test
    void countsEachOutcomeSeparately() {
        when(store.beginAttempt(any())).thenReturn(
                ChargeAttempt.fresh("tx_success"),
                ChargeAttempt.fresh("tx_declined"));
        when(gateway.charge(any()))
                .thenReturn(GatewayResult.succeeded("ch_success"))
                .thenReturn(GatewayResult.declined("card_declined", "declined"));

        payments.authorize(command("order-1", "hold-1"));
        payments.authorize(command("order-2", "hold-2"));

        assertThat(attempts("succeeded")).isEqualTo(1);
        assertThat(attempts("declined")).isEqualTo(1);
    }

    @Test
    void countsAGatewayErrorAsAnError_notADecline() {
        when(store.beginAttempt(any())).thenReturn(ChargeAttempt.fresh("tx_error"));
        when(gateway.charge(any())).thenReturn(GatewayResult.error("unavailable", "offline"));

        assertThatThrownBy(() -> payments.authorize(command("order-1", "hold-1")))
                .isInstanceOf(RuntimeException.class);

        // A provider outage and a refused card are different events with different
        // responses, and a ratio that conflated them would open the breaker on healthy
        // traffic (ADR-052). Separate series keep them distinguishable.
        assertThat(attempts("error")).isEqualTo(1);
        assertThat(attempts("declined")).isZero();
    }

    @Test
    void redisCleanupFailureDoesNotReplaceASuccessfulPaymentResult() {
        when(store.beginAttempt(any()))
                .thenReturn(ChargeAttempt.fresh("tx_success"));

        when(gateway.charge(any()))
                .thenReturn(GatewayResult.succeeded("ch_success"));

        when(redis.delete(anyString()))
                .thenThrow(new RuntimeException("redis unavailable"));

        var result = payments.authorize(command("order-1", "hold-1"));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.transactionReference()).isEqualTo("tx_success");
        assertThat(result.gatewayReference()).isEqualTo("ch_success");
        assertThat(result.failureReason()).isNull();
    }
    @Test
    void existingInflightPaymentIsRejectedBeforeStartingAnotherCharge() {
        when(values.setIfAbsent(any(), any(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> payments.authorize(command("order-1", "hold-1")))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(store, never()).beginAttempt(any());
        verify(gateway, never()).charge(any());
    }

    @Test
    void forwardsTheExactChargeAndIdempotencyDataToTheGateway() {
        when(store.beginAttempt(any()))
                .thenReturn(ChargeAttempt.fresh("tx_success"));

        when(gateway.charge(any()))
                .thenReturn(GatewayResult.succeeded("ch_success"));

        var command = new AuthorizeCommand(
                "order-42",
                "hold-42",
                "session-7",
                7_500,
                "usd",
                "pm_card_visa",
                "client-idem-42",
                1);

        payments.authorize(command);

        ArgumentCaptor<GatewayCharge> captor =
                ArgumentCaptor.forClass(GatewayCharge.class);

        verify(gateway).charge(captor.capture());

        GatewayCharge charge = captor.getValue();

        assertThat(charge.orderNumber()).isEqualTo("order-42");
        assertThat(charge.holdToken()).isEqualTo("hold-42");
        assertThat(charge.amountCents()).isEqualTo(7_500);
        assertThat(charge.currency()).isEqualTo("usd");
        assertThat(charge.paymentMethodId()).isEqualTo("pm_card_visa");
        // Scoped to the attempt, so a second card after a decline is a new request to the provider (ADR-074).
        assertThat(charge.idempotencyKey()).isEqualTo("client-idem-42:1");
    }
    @Test
    void failedResumeRetrievalDoesNotStartASecondCharge() {
        when(store.beginAttempt(any()))
                .thenReturn(ChargeAttempt.resume("tx_existing", "pi_existing"));

        when(gateway.retrieve("pi_existing"))
                .thenReturn(
                        GatewayResult.error(
                                "provider_unavailable",
                                "Stripe is unavailable."));

        assertThatThrownBy(
                () -> payments.authorize(command("order-1", "hold-1")))
                .isInstanceOfSatisfying(FlashSeatsException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));

        verify(gateway).retrieve("pi_existing");
        verify(gateway, never()).charge(any());
    }

    private static AuthorizeCommand command(String orderNumber, String holdToken) {
        return new AuthorizeCommand(
                orderNumber, holdToken, "session-1", 1_000, "usd", "pm_card", "idem-" + orderNumber, 1);
    }

    /** Each attempt is its own request to the provider; a retry of one attempt is the same request (ADR-074). */
    @Test
    void theProviderKeyIsScopedToTheAttempt() {
        var second = new com.flashseats.payment.facade.AuthorizeCommand(
                "order-42", "hold-42", "session-7", 7_500, "usd", "pm_card_visa", "client-idem-42", 2);

        assertThat(PaymentService.providerKey(second)).isEqualTo("client-idem-42:2");
    }
}
