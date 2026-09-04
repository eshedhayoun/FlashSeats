package com.flashseats.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outbox relay tunables. */
@ConfigurationProperties(prefix = "flashseats.outbox")
public class OutboxProperties {

    private long pollIntervalMs = 1_000;

    private int batchSize = 100;

    /** After this long, a row still {@code PROCESSING} is assumed orphaned and re-queued. */
    private int staleClaimSeconds = 60;

    /** {@code PROCESSED} rows older than this are deleted, so the table does not grow forever. */
    private int purgeAfterDays = 7;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getStaleClaimSeconds() {
        return staleClaimSeconds;
    }

    public void setStaleClaimSeconds(int staleClaimSeconds) {
        this.staleClaimSeconds = staleClaimSeconds;
    }

    public int getPurgeAfterDays() {
        return purgeAfterDays;
    }

    public void setPurgeAfterDays(int purgeAfterDays) {
        this.purgeAfterDays = purgeAfterDays;
    }
}
