package com.flashseats.flashseats.hold.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hold")
public class HoldProperties {

    private long ttlSeconds = 300;
    private long expirationSweepIntervalMs = 30_000;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("hold.ttl-seconds must be > 0");
        }
        this.ttlSeconds = ttlSeconds;
    }

    public long getExpirationSweepIntervalMs() {
        return expirationSweepIntervalMs;
    }

    public void setExpirationSweepIntervalMs(long expirationSweepIntervalMs) {
        if (expirationSweepIntervalMs <= 0) {
            throw new IllegalArgumentException("hold.expiration-sweep-interval-ms must be > 0");
        }
        this.expirationSweepIntervalMs = expirationSweepIntervalMs;
    }
}
