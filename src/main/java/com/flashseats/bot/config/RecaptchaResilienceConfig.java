package com.flashseats.bot.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.flashseats.bot.exception.RecaptchaTransportException;

@Configuration
public class RecaptchaResilienceConfig {

    public static final String BREAKER_NAME = "recaptcha";
    public static final String RETRY_NAME = "recaptcha";

    @Bean
    public CircuitBreaker recaptchaCircuitBreaker() {
        return CircuitBreaker.of(
                BREAKER_NAME,
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(20)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(RecaptchaTransportException.class)
                        .build());
    }

    @Bean
    public Retry recaptchaRetry() {
        return Retry.of(
                RETRY_NAME,
                RetryConfig.custom()
                        .maxAttempts(2) // initial request + one retry
                        .retryExceptions(RecaptchaTransportException.class)
                        .build());
    }
}