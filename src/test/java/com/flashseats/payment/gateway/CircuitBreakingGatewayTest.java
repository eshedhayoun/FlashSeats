package com.flashseats.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the breaker counts, and what it must not.
 *
 * <p>A unit test with its own breaker instance rather than the application's, deliberately: opening
 * a shared breaker inside a suite would leak into whatever ran next, and the thing being asserted
 * here is configuration, not wiring.
 *
 * <p>The failure this guards against is not an error. It is ten thousand queued buyers each waiting
 * out a twenty-second read timeout against a provider that is already down, holding a pooled
 * connection apiece — which under virtual threads is the system's real concurrency limit.
 */
@DisplayName("The gateway breaker counts outages, never declines")
class CircuitBreakingGatewayTest {

    private static final GatewayCharge CHARGE =
            new GatewayCharge("TK-1", "hld_1", 7_500, "USD", "pm_card_visa", "idem-1");

    @Test
    @DisplayName("Repeated transport failures open the breaker and stop calling the provider")
    void opensOnTransportFailures() {
        AtomicInteger calls = new AtomicInteger();
        PaymentGateway gateway = breaking(new AlwaysFailing(calls));

        for (int i = 0; i < 5; i++) {
            GatewayResult result = gateway.charge(CHARGE);
            assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.ERROR);
        }
        assertThat(calls.get()).isEqualTo(5);

        // Open now. The next call must answer without touching the provider at all — that is the
        // entire point, and a breaker that still called through would be an expensive no-op.
        GatewayResult refused = gateway.charge(CHARGE);

        assertThat(refused.outcome()).isEqualTo(GatewayResult.Outcome.ERROR);
        assertThat(refused.failureCode()).isEqualTo("circuit_open");
        assertThat(calls.get()).isEqualTo(5);

        // And it leaves by the same door as a real outage, so the caller answers 503 with the seats
        // retained and no payment attempt consumed. Nothing above this class knows the difference.
        assertThat(refused.failureReason()).contains("still held");
    }

    @Test
    @DisplayName("A hundred declines leave the breaker closed")
    void declinesNeverOpenIt() {
        AtomicInteger calls = new AtomicInteger();
        PaymentGateway gateway = breaking(new AlwaysDeclining(calls));

        for (int i = 0; i < 100; i++) {
            GatewayResult result = gateway.charge(CHARGE);
            assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.DECLINED);
        }

        // Every single one reached the provider. A breaker that counted declines would have opened
        // long ago and taken a perfectly healthy sale's payments down with it — during a flash sale,
        // where a burst of expired cards is the normal state of the world rather than a signal.
        assertThat(calls.get()).isEqualTo(100);
    }

    private static PaymentGateway breaking(PaymentGateway delegate) {
        CircuitBreaker breaker = CircuitBreaker.of(
                "test",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .recordExceptions(GatewayTransportException.class)
                        .build());
        return new CircuitBreakingGateway(delegate, breaker);
    }

    private record AlwaysFailing(AtomicInteger calls) implements PaymentGateway {

        @Override
        public GatewayResult charge(GatewayCharge charge) {
            calls.incrementAndGet();
            throw new GatewayTransportException("api_connection_error", "unreachable");
        }

        @Override
        public GatewayResult retrieve(String gatewayReference) {
            return charge(CHARGE);
        }

        @Override
        public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
            return charge(CHARGE);
        }
    }

    private record AlwaysDeclining(AtomicInteger calls) implements PaymentGateway {

        @Override
        public GatewayResult charge(GatewayCharge charge) {
            calls.incrementAndGet();
            return GatewayResult.declined("card_declined", "Your card was declined.");
        }

        @Override
        public GatewayResult retrieve(String gatewayReference) {
            return charge(CHARGE);
        }

        @Override
        public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
            return charge(CHARGE);
        }
    }
}
