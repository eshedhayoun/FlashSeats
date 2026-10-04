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

/**
 * Only a refund is retried in-process (ADR-072). A charge runs against a hold's clock, and three
 * attempts with backoff could outlast the 45 s checkout guarantees; the buyer's re-POST is its retry.
 */
@DisplayName("The gateway retries refunds, and leaves a charge's retry to the buyer")
class PaymentGatewayRetryTest {

    private static final GatewayCharge CHARGE =
            new GatewayCharge("TK-1", "hld-1", 7_500, "USD", "pm_card_visa", "idem-1");

    private final AtomicInteger charges = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();

    /** Fails twice with a transport error, then succeeds — whichever operation is called. */
    private final PaymentGateway flaky = new PaymentGateway() {
        @Override
        public GatewayResult charge(GatewayCharge charge) {
            if (charges.incrementAndGet() < 3) {
                throw new GatewayTransportException("api_connection_error", "unreachable");
            }
            return GatewayResult.succeeded("pi_123");
        }

        @Override
        public GatewayResult retrieve(String gatewayReference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
            if (refunds.incrementAndGet() < 3) {
                throw new GatewayTransportException("api_connection_error", "unreachable");
            }
            return GatewayResult.succeeded("re_123");
        }
    };

    private final PaymentGateway gateway = new CircuitBreakingGateway(
            flaky,
            CircuitBreaker.of(
                    "testBreaker",
                    CircuitBreakerConfig.custom()
                            .minimumNumberOfCalls(10)
                            .slidingWindowSize(20)
                            .failureRateThreshold(50)
                            .build()),
            Retry.of(
                    "testRetry",
                    RetryConfig.custom()
                            .maxAttempts(3)
                            .intervalFunction(IntervalFunction.of(10))
                            .retryExceptions(GatewayTransportException.class)
                            .build()));

    @Test
    void aChargeIsTriedOnceAndItsFailureIsReportedAsAnOutage() {
        GatewayResult result = gateway.charge(CHARGE);

        assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.ERROR);
        assertThat(charges.get()).isEqualTo(1);
    }

    @Test
    void aRefundIsRetriedUntilItGoesThrough() {
        GatewayResult result = gateway.refund("pi_123", 7_500, "seats gone");

        assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.SUCCEEDED);
        assertThat(refunds.get()).isEqualTo(3);
    }
}
