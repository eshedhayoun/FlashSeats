package com.flashseats.bot.config;

import java.util.List;
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
public class BotProperties {

    private final Bucket sessionBucket = new Bucket(20, 10);
    private final Bucket ipBucket = new Bucket(300, 150);
    private final Recaptcha recaptcha = new Recaptcha();

    /**
     * How long a replica may believe its snapshot of {@code ip_rules}.
     *
     * <p>The rules are consulted on every request, so they cannot be read from the database per
     * request — that would put the rate limiter inside the connection pool it exists to protect
     * (ADR-051). <strong>The TTL is the cross-replica invalidation</strong>: an operator's call
     * evicts on the one replica that served it, and the others pick the change up when their
     * snapshot expires. A cache with no TTL would make a block permanent on two replicas out of
     * three and absent on the third, for the life of the process.
     *
     * <p>Ten seconds, not one: an operator adding a block is responding to something already
     * happening, and ten seconds of a burst is survivable where a per-request query is not.
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

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public Bucket getSessionBucket() {
        return sessionBucket;
    }

    public Bucket getIpBucket() {
        return ipBucket;
    }

    public Recaptcha getRecaptcha() {
        return recaptcha;
    }

    public long getIpRuleCacheTtlMs() {
        return ipRuleCacheTtlMs;
    }

    public void setIpRuleCacheTtlMs(long ipRuleCacheTtlMs) {
        this.ipRuleCacheTtlMs = ipRuleCacheTtlMs;
    }

    /**
     * The challenge provider.
     *
     * <p><strong>Everything here is sized around failing open</strong> (ADR-011). A blank
     * {@code secret} disables verification entirely, and a timeout or a non-2xx allows the request.
     * A challenge provider being unreachable must never stop a sale — the whole point of the sale is
     * that ten thousand people arrive in the same second, and a third party's bad afternoon is not
     * a reason to turn them all away.
     *
     * <p>That makes the timeouts a correctness setting rather than a tuning one. This call sits on
     * the queue-join path; a default-timeout HTTP client there turns a provider slowdown into a
     * sale-length outage, which is the failure mode failing open exists to prevent, reintroduced by
     * the client that implements it.
     */
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
        private long verifiedTtlSeconds = 1800;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getVerifyUrl() {
            return verifyUrl;
        }

        public void setVerifyUrl(String verifyUrl) {
            this.verifyUrl = verifyUrl;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public long getVerifiedTtlSeconds() {
            return verifiedTtlSeconds;
        }

        public void setVerifiedTtlSeconds(long verifiedTtlSeconds) {
            this.verifiedTtlSeconds = verifiedTtlSeconds;
        }

        /** Verification runs only when a secret is configured. Blank is "off", and off is allowed. */
        public boolean isEnabled() {
            return secret != null && !secret.isBlank();
        }
    }

    /** A token bucket: {@code capacity} tokens, refilled at {@code refillPerSecond}. */
    public static class Bucket {
        private long capacity;
        private long refillPerSecond;

        Bucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(long refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }
}
