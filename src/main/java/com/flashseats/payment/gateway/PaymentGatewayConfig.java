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
 * Chooses the payment provider and wraps it in the breaker.
 *
 * <p>One {@link PaymentGateway} bean, assembled here rather than two beans competing under
 * {@code @ConditionalOnMissingBean}: with a real gateway, a stub and a decorator all implementing
 * the interface, "whichever bean exists" stops being a seam and becomes an ambiguity.
 *
 * <p>The stub is the default. {@code flashseats.payment.stripe.enabled} is false unless a deployment
 * says otherwise, so a clean checkout, the test suite and the load harness all run the full buyer
 * journey — including decline, outage and 3-D Secure — with no keys and no network.
 *
 * <p>Resilience4j is wired as plain beans. Its Spring Boot starter targets Boot 3 and autoconfigures
 * against APIs this application does not have.
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
