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
     * Keeps event and tier metadata off the hottest polling paths (ADR-051).
     *
     * <p>A switch rather than a constant because it is half of the concurrent-sales experiment: the
     * same drill run with this {@code false} is the baseline the cached run is measured against.
     */
    private boolean metadataCacheEnabled = true;

    /**
     * How long a cached {@code events} row is served.
     *
     * <p><strong>This is the cluster-wide bound on how long a paused sale can still answer
     * {@code OPEN}.</strong> Eviction reaches only the replica that handled the operator's call, so
     * every other replica stops selling within this window and not before. One second collapses
     * thousands of identical window checks per second into one query per event, which is the entire
     * benefit; buying more with a longer TTL would be paid for in how long pause takes to bite.
     */
    private long metadataEventTtlMs = 1_000;

    /**
     * How long a cached tier list is served.
     *
     * <p>Far longer, because nothing in this system updates a tier after creation. The TTL is here
     * for rows inserted directly into PostgreSQL by the seed scripts, which no application write
     * can evict.
     */
    private long metadataTierTtlMs = 60_000;

    /**
     * Upper bound on cached events, as a leak guard rather than an eviction policy.
     *
     * <p>The operating envelope is 3–10 concurrent sales and only real rows are ever cached, so this
     * is unreachable in practice. At the bound a new event is served uncached instead of displacing
     * a live one.
     */
    private int metadataCacheMaxEvents = 1_000;

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

    public long getMetadataEventTtlMs() {
        return metadataEventTtlMs;
    }

    public void setMetadataEventTtlMs(long metadataEventTtlMs) {
        this.metadataEventTtlMs = metadataEventTtlMs;
    }

    public long getMetadataTierTtlMs() {
        return metadataTierTtlMs;
    }

    public void setMetadataTierTtlMs(long metadataTierTtlMs) {
        this.metadataTierTtlMs = metadataTierTtlMs;
    }

    public int getMetadataCacheMaxEvents() {
        return metadataCacheMaxEvents;
    }

    public void setMetadataCacheMaxEvents(int metadataCacheMaxEvents) {
        this.metadataCacheMaxEvents = metadataCacheMaxEvents;
    }
}
