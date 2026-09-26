package com.flashseats.payment.gateway;

import com.flashseats.payment.config.PaymentProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Qualifier;
/**
 * Chooses the payment provider and wraps it in the breaker: one {@link PaymentGateway} bean, not
 * competing conditional beans. The stub is the default. Resilience4j is wired as plain beans,
 * because its starter targets Boot 3.
 */
@Slf4j
@Configuration
public class PaymentGatewayConfig {

    /** The one breaker's name, and the {@code name} tag on every metric it publishes. */
    public static final String GATEWAY_BREAKER = "paymentGateway";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(PaymentProperties properties) {
        PaymentProperties.Breaker breaker = properties.getBreaker();
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.getSlidingWindowSize())
                .minimumNumberOfCalls(breaker.getMinimumNumberOfCalls())
                .failureRateThreshold(breaker.getFailureRateThresholdPercent())
                .waitDurationInOpenState(Duration.ofSeconds(breaker.getWaitInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(breaker.getPermittedCallsInHalfOpenState())
                // The whole point. A decline arrives as a return value and must never count;
                // only a failure to reach the provider does.
                .recordExceptions(GatewayTransportException.class)
                .build());
    }

    @Bean
    public CircuitBreaker paymentGatewayBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(GATEWAY_BREAKER);
    }

    /** Publishes {@code resilience4j_circuitbreaker_*} alongside the rest of the Prometheus scrape. */
    @Bean
    public TaggedCircuitBreakerMetrics paymentGatewayBreakerMetrics(
            CircuitBreakerRegistry registry, MeterRegistry meters) {
        TaggedCircuitBreakerMetrics metrics =
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meters);
        return metrics;
    }

    @Bean
   public PaymentGateway paymentGateway(
        PaymentProperties properties,
        @Qualifier("paymentGatewayBreaker") CircuitBreaker paymentGatewayBreaker,
        @Qualifier("paymentGatewayRetry") Retry paymentGatewayRetry){
        PaymentGateway delegate;
        if (properties.getStripe().isEnabled()) {
            log.info("Payment gateway: Stripe");
            delegate = new StripePaymentGateway(properties.getStripe());
        } else {
            log.info("Payment gateway: in-process stub (flashseats.payment.stripe.enabled is false)");
            delegate = new StubPaymentGateway();
        }
        return new CircuitBreakingGateway(delegate,paymentGatewayBreaker,paymentGatewayRetry);
    }
    @Bean
    public Retry paymentGatewayRetry() {
        return Retry.of(
                "paymentGatewayRetry",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialBackoff())
                        .retryExceptions(GatewayTransportException.class)
                        .build());
    }
}
