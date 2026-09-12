package com.flashseats.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for how inventory is described publicly. */
@ConfigurationProperties(prefix = "flashseats.catalog")
public class CatalogProperties {

    /**
     * Below this percentage of {@code total_capacity}, a tier reads as {@code LIMITED} rather than
     * {@code PLENTY}. Buckets, never counts (ADR-027).
     */
    private int limitedThresholdPercent = 10;

    /**
     * How often Redis is asked whether it is still the same server instance.
     *
     * <p>This is the detection latency for a restart that silently rolled the counters back, so it
     * bounds how long a sale can oversell before it is stopped. Cheap — one {@code INFO server} —
     * so it is set for the damage it prevents, not for the load it adds.
     */
    private int epochCheckIntervalMs = 5_000;

    /**
     * Keeps event and tier metadata out of the hottest queue/status paths. Tests disable it because
     * their fixtures write rows directly and restart ids between methods.
     */
    private boolean metadataCacheEnabled = true;

    public int getLimitedThresholdPercent() {
        return limitedThresholdPercent;
    }

    public void setLimitedThresholdPercent(int limitedThresholdPercent) {
        this.limitedThresholdPercent = limitedThresholdPercent;
    }

    public int getEpochCheckIntervalMs() {
        return epochCheckIntervalMs;
    }

    public void setEpochCheckIntervalMs(int epochCheckIntervalMs) {
        this.epochCheckIntervalMs = epochCheckIntervalMs;
    }

    public boolean isMetadataCacheEnabled() {
        return metadataCacheEnabled;
    }

    public void setMetadataCacheEnabled(boolean metadataCacheEnabled) {
        this.metadataCacheEnabled = metadataCacheEnabled;
    }
}
