package com.flashseats.bot.service;

import com.flashseats.bot.config.BotProperties;
import com.flashseats.bot.exception.RecaptchaTransportException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the challenge provider whether a visitor looks human, and <strong>fails open</strong>: only a
 * low score refuses (ADR-011, ADR-055). Timeouts, errors, malformed answers and an open circuit all
 * produce {@link Verdict#DEGRADED} and allow the request. It is the compensating control for
 * free-to-mint sessions (§10 S5). Verification is cached per session.
 */
@Slf4j
@Service
public class RecaptchaService {

    private static final String VERIFIED_KEY = "bot:verified:";

    private final BotProperties properties;
    private final StringRedisTemplate redis;
    private final RestClient http;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    /**
     * Production constructor. The resilience components are supplied by
     * {@code RecaptchaResilienceConfig}.
     */
    @Autowired
    public RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            @Qualifier("recaptchaCircuitBreaker") CircuitBreaker recaptchaCircuitBreaker,
            @Qualifier("recaptchaRetry") Retry recaptchaRetry) {
        this(
                properties,
                redis,
                buildHttpClient(properties),
                recaptchaCircuitBreaker,
                recaptchaRetry);
    }

    /**
     * Constructor used by tests that provide their own RestClient builder.
     */
    RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            RestClient.Builder clientBuilder) {
        this(
                properties,
                redis,
                clientBuilder.requestFactory(requestFactory(properties)).build(),
                testCircuitBreaker(),
                testRetry());
    }

    /**
     * Constructor used by tests that provide a custom request factory.
     */
    RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            ClientHttpRequestFactory requestFactory,
            RestClient.Builder clientBuilder) {
        this(
                properties,
                redis,
                clientBuilder.requestFactory(requestFactory).build(),
                testCircuitBreaker(),
                testRetry());
    }

    /**
     * Constructor used by tests that provide a fully built RestClient.
     */
    RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            RestClient http) {
        this(
                properties,
                redis,
                http,
                testCircuitBreaker(),
                testRetry());
    }

    /**
     * Main constructor used by all other constructors.
     */
    RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            RestClient http,
            CircuitBreaker circuitBreaker,
            Retry retry) {
        this.properties = properties;
        this.redis = redis;
        this.http = http;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    private static RestClient buildHttpClient(BotProperties properties) {
        return RestClient.builder()
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(BotProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(
                Duration.ofMillis(properties.getRecaptcha().getConnectTimeoutMs()));

        factory.setReadTimeout(
                Duration.ofMillis(properties.getRecaptcha().getReadTimeoutMs()));

        return factory;
    }

    /**
     * @return the provider verdict in terms the caller can act on.
     *
     * <p>{@link Verdict#FAILED} is the only refusing result. Provider outages and other dependency
     * failures fail open.
     */
    public Verdict verify(String sessionId, String token) {
        BotProperties.Recaptcha config = properties.getRecaptcha();

        if (!config.isEnabled()) {
            return Verdict.DISABLED;
        }

        if (isAlreadyVerified(sessionId)) {
            return Verdict.PASSED;
        }

        if (token == null || token.isBlank()) {
            // Missing token is treated as unavailable verification, not as evidence of a bot.
            return Verdict.DEGRADED;
        }

        Double score = scoreFor(config, token);

        if (score == null) {
            return Verdict.DEGRADED;
        }

        if (score < config.getMinScore()) {
            return Verdict.FAILED;
        }

        remember(sessionId, config.getVerifiedTtlSeconds());
        return Verdict.PASSED;
    }

    /**
     * Runs the provider call through:
     *
     * <ol>
     *   <li>Retry: initial call + one retry for transport failures.</li>
     *   <li>Circuit breaker: opens after repeated transport failures.</li>
     * </ol>
     *
     * <p>The breaker sees the final failure after retry is exhausted, rather than counting the
     * intermediate retry attempts as separate business calls.
     */
    private Double scoreFor(BotProperties.Recaptcha config, String token) {
        try {
            return circuitBreaker.executeSupplier(
                    () -> retry.executeSupplier(
                            () -> callProvider(config, token)));

        } catch (CallNotPermittedException open) {
            log.warn(
                    "reCAPTCHA circuit is OPEN; allowing the request (ADR-011)");

            return null;

        } catch (RuntimeException degraded) {
            log.warn(
                    "reCAPTCHA unavailable; allowing the request (ADR-011)",
                    degraded);

            return null;
        }
    }

    /**
     * Makes one actual provider request.
     *
     * <p>Only transport failures become {@link RecaptchaTransportException}, which is what the
     * Retry and CircuitBreaker are configured to recognize.
     */
    private Double callProvider(
            BotProperties.Recaptcha config,
            String token) {

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", config.getSecret());
            form.add("response", token);

            Map<?, ?> body = http.post()
                    .uri(config.getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            (request, response) -> {
                                throw new RecaptchaTransportException(
                                        "reCAPTCHA provider returned "
                                                + response.getStatusCode());
                            })
                    .body(Map.class);

            if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
                // success=false means the provider gave a definite unusable verdict.
                // It is not evidence of a bot, so we fail open without retrying it.
                log.debug(
                        "Challenge provider returned an unusable verdict: {}",
                        body);

                return null;
            }

            Object score = body.get("score");

            // Some provider responses may contain success without a score.
            // Keep the existing behavior: treat that as a pass rather than inventing a low score.
            return score instanceof Number number
                    ? number.doubleValue()
                    : 1.0d;

        } catch (ResourceAccessException failed) {
            // Connection/read timeout or another transport-level client failure.
            throw new RecaptchaTransportException(
                    "reCAPTCHA provider could not be reached",
                    failed);
        }
    }

    private boolean isAlreadyVerified(String sessionId) {
        try {
            return Boolean.TRUE.equals(
                    redis.hasKey(VERIFIED_KEY + sessionId));

        } catch (RuntimeException redisDown) {
            // Cache failure must never block a buyer.
            return false;
        }
    }

    private void remember(String sessionId, long ttlSeconds) {
        try {
            redis.opsForValue()
                    .set(
                            VERIFIED_KEY + sessionId,
                            "1",
                            Duration.ofSeconds(ttlSeconds));

        } catch (RuntimeException redisDown) {
            // Losing the cache only means a future join may call the provider again.
            log.debug(
                    "Could not cache a verification for {}",
                    sessionId,
                    redisDown);
        }
    }

    /**
     * Test-only breaker. Production uses the configured bean from RecaptchaResilienceConfig.
     */
    private static CircuitBreaker testCircuitBreaker() {
        return CircuitBreaker.ofDefaults("recaptcha-test");
    }

    /**
     * Test-only retry policy matching the production retry count:
     * initial request + one retry.
     */
    private static Retry testRetry() {
        return Retry.of(
                "recaptcha-test",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .retryExceptions(RecaptchaTransportException.class)
                        .build());
    }

    /** What the provider concluded, in terms the caller can act on. */
    public enum Verdict {

        /** No secret configured. Verification is disabled and the request is allowed. */
        DISABLED,

        /** Verification passed, including a cached session verification. */
        PASSED,

        /** Score was below the configured threshold. This is the only refusing outcome. */
        FAILED,

        /** Provider unavailable or unable to give a usable verdict. Request is allowed. */
        DEGRADED
    }
}