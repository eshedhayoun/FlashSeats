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

    public int getLimitedThresholdPercent() {
        return limitedThresholdPercent;
    }

    public void setLimitedThresholdPercent(int limitedThresholdPercent) {
        this.limitedThresholdPercent = limitedThresholdPercent;
    }
}
