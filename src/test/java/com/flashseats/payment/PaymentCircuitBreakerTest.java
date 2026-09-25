package com.flashseats.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.payment.gateway.CircuitBreakingGateway;
import com.flashseats.payment.gateway.GatewayCharge;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.GatewayTransportException;
import com.flashseats.payment.gateway.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The payment circuit breaker protects checkout from provider outages")
class PaymentCircuitBreakerTest {

    private static final GatewayCharge CHARGE =
            new GatewayCharge(
                    "TK-1",
                    "hld-1",
                    7_500,
                    "USD",
                    "pm_card_visa",
                    "circuit-test");

    @Test
    @DisplayName("Five transport failures open the breaker and the next call never reaches the provider")
    void providerOutageOpensCircuit() {
        AtomicInteger providerCalls = new AtomicInteger();

        PaymentGateway provider = new PaymentGateway() {

            @Override
            public GatewayResult charge(GatewayCharge charge) {
                providerCalls.incrementAndGet();
                throw new GatewayTransportException(
                        "gateway_error",
                        "The payment provider is unavailable.");
            }

            @Override
            public GatewayResult retrieve(String gatewayReference) {
                throw new UnsupportedOperationException();
            }

            @Override
            public GatewayResult refund(
                    String gatewayReference,
                    long amountCents,
                    String reason) {
                throw new UnsupportedOperationException();
            }
        };

        CircuitBreaker breaker =
                CircuitBreaker.of(
                        "payment-circuit-test",
                        CircuitBreakerConfig.custom()
                                .slidingWindowType(
                                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(5)
                                .minimumNumberOfCalls(5)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofMinutes(1))
                                .recordExceptions(GatewayTransportException.class)
                                .build());

        Retry retry =
                Retry.of(
                        "payment-retry-test",
                        RetryConfig.custom()
                                .maxAttempts(1)
                                .build());

        PaymentGateway gateway =
                new CircuitBreakingGateway(
                        provider,
                        breaker,
                        retry);

        for (int i = 0; i < 5; i++) {
            GatewayResult result = gateway.charge(CHARGE);

            assertThat(result.outcome())
                    .isEqualTo(GatewayResult.Outcome.ERROR);
        }

        assertThat(providerCalls.get()).isEqualTo(5);
        assertThat(breaker.getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        GatewayResult refused = gateway.charge(CHARGE);

        assertThat(refused.outcome())
                .isEqualTo(GatewayResult.Outcome.ERROR);
        assertThat(refused.failureCode())
                .isEqualTo("circuit_open");

        // The open circuit rejected the request before the provider was touched.
        assertThat(providerCalls.get()).isEqualTo(5);

        // This is the result PaymentService turns into the buyer-visible 503 path.
        assertThat(refused.failureReason())
                .contains("still held");
    }

    @Test
    @DisplayName("A successful provider call keeps the breaker closed")
    void successfulCallsDoNotOpenCircuit() {
        AtomicInteger providerCalls = new AtomicInteger();

        PaymentGateway provider = new PaymentGateway() {

            @Override
            public GatewayResult charge(GatewayCharge charge) {
                providerCalls.incrementAndGet();
                return GatewayResult.succeeded("pi_success");
            }

            @Override
            public GatewayResult retrieve(String gatewayReference) {
                return GatewayResult.succeeded(gatewayReference);
            }

            @Override
            public GatewayResult refund(
                    String gatewayReference,
                    long amountCents,
                    String reason) {
                return GatewayResult.succeeded(gatewayReference);
            }
        };

        CircuitBreaker breaker =
                CircuitBreaker.of(
                        "payment-success-test",
                        CircuitBreakerConfig.custom()
                                .slidingWindowType(
                                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(5)
                                .minimumNumberOfCalls(5)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofMinutes(1))
                                .recordExceptions(GatewayTransportException.class)
                                .build());

        Retry retry =
                Retry.of(
                        "payment-success-retry-test",
                        RetryConfig.custom()
                                .maxAttempts(1)
                                .build());

        PaymentGateway gateway =
                new CircuitBreakingGateway(
                        provider,
                        breaker,
                        retry);

        for (int i = 0; i < 5; i++) {
            GatewayResult result = gateway.charge(CHARGE);

            assertThat(result.outcome())
                    .isEqualTo(GatewayResult.Outcome.SUCCEEDED);
        }

        assertThat(providerCalls.get()).isEqualTo(5);
        assertThat(breaker.getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}