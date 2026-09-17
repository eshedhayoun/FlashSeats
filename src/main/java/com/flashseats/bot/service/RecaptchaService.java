package com.flashseats.bot.service;

import com.flashseats.bot.config.BotProperties;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Asks the challenge provider whether a visitor looks human, and <strong>fails open</strong>.
 *
 * <p>This is §10 S5's missing compensating control. Session identity is free to mint — anyone can
 * discard a cookie for a fresh session bucket — so ADR-011's per-session limit does not constrain a
 * determined attacker at all, and the IP bucket is deliberately loose so carrier-grade NAT
 * populations are not blocked. A score that costs something to obtain is the only thing on this path
 * that an attacker cannot simply mint more of.
 *
 * <p><strong>Failing open is the decision, not a fallback</strong> (ADR-011). A timeout, a non-2xx, a
 * malformed body or a blank secret all allow the request. The alternative is letting a third party's
 * bad afternoon close a sale that ten thousand people are waiting for — and the failure would arrive
 * at exactly the moment of peak load, because that is when the provider is also busiest. Every
 * degraded verification is audited, though: "our bot defence was off for three hours" must not be
 * something anyone learns afterwards from an absence.
 *
 * <p>The timeouts are therefore correctness settings. This call sits on the queue-join path; a
 * default-timeout client here turns a provider slowdown into a sale-length outage — reintroducing
 * the exact failure that failing open exists to prevent, through the client that implements it.
 *
 * <p>Verified once per session, not per join. A buyer who rejoins after a dropped connection has
 * already proved whatever there is to prove, and re-challenging them is a cost paid entirely by the
 * legitimate.
 */
@Slf4j
@Service
public class RecaptchaService {

    /** Owned by this module. Nothing else reads or writes this prefix. */
    private static final String VERIFIED_KEY = "bot:verified:";

    private final BotProperties properties;
    private final StringRedisTemplate redis;
    private final RestClient http;

    public RecaptchaService(BotProperties properties, StringRedisTemplate redis) {
        this(properties, redis, RestClient.builder());
    }

    RecaptchaService(
            BotProperties properties, StringRedisTemplate redis, RestClient.Builder clientBuilder) {
        this(properties, redis, requestFactory(properties), clientBuilder);
    }

    RecaptchaService(
            BotProperties properties,
            StringRedisTemplate redis,
            ClientHttpRequestFactory requestFactory,
            RestClient.Builder clientBuilder) {
        this(properties, redis, clientBuilder.requestFactory(requestFactory).build());
    }

    RecaptchaService(BotProperties properties, StringRedisTemplate redis, RestClient http) {
        this.properties = properties;
        this.redis = redis;
        this.http = http;
    }

    private static ClientHttpRequestFactory requestFactory(BotProperties properties) {
        // Timeouts set on the factory explicitly, never left to the default. A client with no
        // read timeout on the join path turns a provider slowdown into a sale-length outage.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getRecaptcha().getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getRecaptcha().getReadTimeoutMs()));
        return factory;
    }

    /**
     * @return a verdict the caller acts on. {@code FAILED} is the <em>only</em> outcome that refuses
     *     a request; everything else — including every kind of provider trouble — lets it through.
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
            // No token where verification is switched on is a client that has not been updated, not
            // evidence of a bot. Allowed, and recorded, so the gap is visible rather than silent.
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

    private Double scoreFor(BotProperties.Recaptcha config, String token) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", config.getSecret());
            form.add("response", token);

            Map<?, ?> body = http.post()
                    .uri(config.getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
                // `success: false` means the provider could not evaluate the token — expired,
                // duplicate, wrong site key. It is NOT a low score, so it is not evidence of a bot.
                log.debug("Challenge provider returned an unusable verdict: {}", body);
                return null;
            }
            Object score = body.get("score");
            // v2 answers `success` with no score at all; treat that as a pass rather than inventing
            // a number to compare against a v3 threshold.
            return score instanceof Number number ? number.doubleValue() : 1.0d;

        } catch (RuntimeException unreachable) {
            log.warn("Challenge provider unreachable; allowing the request (ADR-011)", unreachable);
            return null;
        }
    }

    private boolean isAlreadyVerified(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(VERIFIED_KEY + sessionId));
        } catch (RuntimeException redisDown) {
            // Missing the cache costs one extra provider call, never a refusal.
            return false;
        }
    }

    private void remember(String sessionId, long ttlSeconds) {
        try {
            redis.opsForValue()
                    .set(VERIFIED_KEY + sessionId, "1", Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException redisDown) {
            log.debug("Could not cache a verification for {}", sessionId, redisDown);
        }
    }

    /** What the provider concluded, in terms the caller can act on. */
    public enum Verdict {
        /** No secret configured. Verification is off, and off is allowed. */
        DISABLED,
        /** Above the threshold, or already verified for this session. */
        PASSED,
        /** Below the threshold. <strong>The only refusing outcome.</strong> */
        FAILED,
        /** The provider could not answer. Allowed, and audited (ADR-011). */
        DEGRADED
    }
}
