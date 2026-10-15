package com.flashseats.bot.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for rate limiting.
 *
 * <p>Session identity is <strong>not</strong> here. {@code sessionSecret} and the cookie settings
 * moved to {@code flashseats.session.*} in {@code shared}, because identity is what every module's
 * authorisation rests on and abuse defence is a different concern that merely happens to run in a
 * filter too.
 */
@ConfigurationProperties(prefix = "flashseats.bot")
@Getter
@Setter
public class BotProperties {

    private final Bucket sessionBucket = new Bucket(20, 10);
    private final Bucket ipBucket = new Bucket(300, 150);
    private final Recaptcha recaptcha = new Recaptcha();

    /**
     * How long a replica may trust its {@code ip_rules} snapshot. The TTL is the cross-replica
     * invalidation (ADR-051, ADR-055); ten seconds of a burst is survivable, a per-request query is not.
     */
    private long ipRuleCacheTtlMs = 10_000;

    /**
     * Peer addresses whose {@code X-Forwarded-For} header is believed (ADR-039).
     *
     * <p><strong>Empty means trust nobody</strong>, which is the correct default for an app with
     * nothing in front of it. Set it to the load balancer's address wherever one terminates —
     * {@code compose.yaml} does this for the {@code cluster} profile. Leaving it empty behind a
     * proxy is safe but coarse: every request then looks like it came from the balancer and shares
     * one IP bucket.
     */
    private List<String> trustedProxies = List.of();

    /**
     * The challenge provider, sized around <strong>failing open</strong> (ADR-011): a blank
     * {@code secret} disables it, and a timeout or non-2xx allows the request. The timeouts are
     * therefore correctness settings: this call sits on the queue-join path.
     */
    @Getter
    @Setter
    public static class Recaptcha {

        private String secret = "";
        private String verifyUrl = "https://www.google.com/recaptcha/api/siteverify";
        private double minScore = 0.5;
        private int connectTimeoutMs = 1_000;
        private int readTimeoutMs = 2_000;

        /**
         * How long one verification stands for a session.
         *
         * <p>Verified once per session rather than per join: a buyer who rejoins after a dropped
         * connection has already proved what there is to prove, and re-challenging them mid-sale is
         * a cost paid entirely by the legitimate.
         */
        private long verifiedTtlSeconds = 900;

        /** Verification runs only when a secret is configured. Blank is "off", and off is allowed. */
        public boolean isEnabled() {
            return secret != null && !secret.isBlank();
        }
    }

    /** A token bucket: {@code capacity} tokens, refilled at {@code refillPerSecond}. */
    @Getter
    @Setter
    public static class Bucket {
        private long capacity;
        private long refillPerSecond;

        Bucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }
    }
}
