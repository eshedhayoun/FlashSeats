package com.flashseats.hold.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hold timers and limits (ADR-006, ADR-017, ADR-030).
 *
 * <p>Every one of these is a named property rather than a literal, because the client renders what
 * the server says: hardcoding "5 minutes" or "max 4" in a UI component is how a limit change becomes
 * a two-repository deploy.
 */
@ConfigurationProperties(prefix = "flashseats.hold")
public class HoldProperties {

    /** Initial reservation window. */
    private int ttlSeconds = 300;

    /** The single grace extension granted before the first charge attempt. */
    private int graceSeconds = 120;

    /** Absolute ceiling from creation. 300 + 120 — a hold can never outlive this. */
    private int maxTtlSeconds = 420;

    /** Seats per hold (ADR-017). The tier's own {@code maxPerOrder} may be lower. */
    private int maxQuantity = 6;

    private long sweeperIntervalMs = 10_000;

    /** Rows the sweeper claims per pass; bounds its transaction size under a large expiry burst. */
    private int sweeperBatchSize = 500;

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getGraceSeconds() {
        return graceSeconds;
    }

    public void setGraceSeconds(int graceSeconds) {
        this.graceSeconds = graceSeconds;
    }

    public int getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(int maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public int getMaxQuantity() {
        return maxQuantity;
    }

    public void setMaxQuantity(int maxQuantity) {
        this.maxQuantity = maxQuantity;
    }

    public long getSweeperIntervalMs() {
        return sweeperIntervalMs;
    }

    public void setSweeperIntervalMs(long sweeperIntervalMs) {
        this.sweeperIntervalMs = sweeperIntervalMs;
    }

    public int getSweeperBatchSize() {
        return sweeperBatchSize;
    }

    public void setSweeperBatchSize(int sweeperBatchSize) {
        this.sweeperBatchSize = sweeperBatchSize;
    }
}
