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

    @Test
    void countsDeclinesButNotSuccessfulAttempts() {
        when(store.beginAttempt(any())).thenReturn(
                new ChargeAttempt("tx_success", null),
                new ChargeAttempt("tx_declined", null));
        when(gateway.charge(any()))
                .thenReturn(GatewayResult.succeeded("ch_success"))
                .thenReturn(GatewayResult.declined("card_declined", "declined"));

        payments.authorize(command("order-1", "hold-1"));
        payments.authorize(command("order-2", "hold-2"));

        assertThat(meters.get("flashseats.payment.decline.ratio").gauge().value()).isEqualTo(0.5);
    }

    @Test
    void doesNotCountGatewayErrorsAsDeclines() {
        when(store.beginAttempt(any())).thenReturn(new ChargeAttempt("tx_error", null));
        when(gateway.charge(any())).thenReturn(GatewayResult.error("unavailable", "offline"));

        assertThatThrownBy(() -> payments.authorize(command("order-1", "hold-1")))
                .isInstanceOf(RuntimeException.class);

        assertThat(meters.get("flashseats.payment.decline.ratio").gauge().value()).isZero();
    }

    private static AuthorizeCommand command(String orderNumber, String holdToken) {
        return new AuthorizeCommand(
                orderNumber, holdToken, "session-1", 1_000, "usd", "pm_card", "idem-" + orderNumber, 1);
    }
}
