package com.flashseats.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Payment gateway retries transport failures")
class PaymentGatewayRetryTest {

    private static final GatewayCharge CHARGE =
            new GatewayCharge(
                    "TK-1",
                    "hld-1",
                    7_500,
                    "USD",
                    "pm_card_visa",
                    "idem-1");

    @Test
    void retriesTransportFailureAndSucceedsOnTheThirdAttempt() {
        AtomicInteger calls = new AtomicInteger();

        PaymentGateway delegate = new PaymentGateway() {
            @Override
            public GatewayResult charge(GatewayCharge charge) {
                int attempt = calls.incrementAndGet();

                if (attempt < 3) {
                    throw new GatewayTransportException(
                            "api_connection_error",
                            "unreachable");
                }

                return GatewayResult.succeeded("pi_123");
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

        CircuitBreaker breaker = CircuitBreaker.of(
                "testBreaker",
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(10)
                        .slidingWindowSize(20)
                        .failureRateThreshold(50)
                        .build());

        Retry retry = Retry.of(
                "testRetry",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.of(10))
                        .retryExceptions(GatewayTransportException.class)
                        .build());

        PaymentGateway gateway =
                new CircuitBreakingGateway(delegate, breaker, retry);

        GatewayResult result = gateway.charge(CHARGE);

        assertThat(result.outcome())
                .isEqualTo(GatewayResult.Outcome.SUCCEEDED);
        assertThat(result.gatewayReference())
                .isEqualTo("pi_123");
        assertThat(calls.get()).isEqualTo(3);
    }
}