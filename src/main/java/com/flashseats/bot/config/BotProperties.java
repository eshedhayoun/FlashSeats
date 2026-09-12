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
