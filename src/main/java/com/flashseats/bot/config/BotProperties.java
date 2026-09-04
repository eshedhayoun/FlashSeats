package com.flashseats.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for session identity and rate limiting.
 *
 * <p>{@code sessionSecret} signs the {@code fsid} cookie. Rotating it invalidates every live
 * session, so it must not be rotated mid-sale.
 */
@ConfigurationProperties(prefix = "flashseats.bot")
public class BotProperties {

    private String sessionSecret = "dev-only-change-me";
    private final Cookie cookie = new Cookie();
    private final Bucket sessionBucket = new Bucket(20, 10);
    private final Bucket ipBucket = new Bucket(300, 150);

    public String getSessionSecret() {
        return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public Bucket getSessionBucket() {
        return sessionBucket;
    }

    public Bucket getIpBucket() {
        return ipBucket;
    }

    public static class Cookie {
        private String name = "fsid";
        /**
         * A {@code Secure} cookie is dropped over plain HTTP, which would give every request a new
         * identity and silently break the entire flow. False for local development; true wherever
         * the app is served over TLS.
         */
        private boolean secure = false;

        private String sameSite = "Lax";
        private int maxAgeSeconds = 86_400;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }

        public int getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(int maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
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
