package com.flashseats.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Waiting-room tunables (ADR-007, ADR-020, ADR-026, ADR-028). */
@ConfigurationProperties(prefix = "flashseats.queue")
public class QueueProperties {

    /** Signs pass and admission tokens. Rotating it invalidates every live pass. */
    private String passSecret = "dev-only-change-me";

    /** Single-use, and short: it only has to survive the hop to the seat-selection screen. */
    private int passTtlSeconds = 120;

    /**
     * How long a buyer may browse the sale. Ten minutes is the industry norm, and it is what makes
     * comparing tiers, using the back button and reloading the tab safe (ADR-020).
     */
    private int admissionTtlSeconds = 600;

    private long promotionIntervalMs = 1_000;

    /**
     * Bounded by the connection pool, not just by inventory (ADR-028). Admission control limits how
     * many buyers there are <em>seats</em> for; this limits how many the system can <em>serve</em>.
     * Must stay at or below {@code hikari.maximum-pool-size × 1.5}.
     */
    private int promotionBatchSize = 45;

    /**
     * Hold-to-order conversion is well under 100%, so admitting exactly {@code remainingStock}
     * buyers leaves the sale under-filled. Every real waiting room tunes this (ADR-020).
     */
    private double oversubscribeFactor = 1.5;

    private long ssePositionIntervalMs = 2_000;

    /** Comment frames that keep proxies from closing an idle stream. */
    private long sseHeartbeatMs = 15_000;

    /**
     * Advisory only — an abandonment metric, <strong>never</strong> a reason to evict (ADR-026). A
     * Wi-Fi to cellular handover routinely outlasts any heartbeat, and evicting on one deletes live
     * buyers from the line through no fault of their own.
     */
    private int heartbeatTtlSeconds = 90;

    public String getPassSecret() {
        return passSecret;
    }

    public void setPassSecret(String passSecret) {
        this.passSecret = passSecret;
    }

    public int getPassTtlSeconds() {
        return passTtlSeconds;
    }

    public void setPassTtlSeconds(int passTtlSeconds) {
        this.passTtlSeconds = passTtlSeconds;
    }

    public int getAdmissionTtlSeconds() {
        return admissionTtlSeconds;
    }

    public void setAdmissionTtlSeconds(int admissionTtlSeconds) {
        this.admissionTtlSeconds = admissionTtlSeconds;
    }

    public long getPromotionIntervalMs() {
        return promotionIntervalMs;
    }

    public void setPromotionIntervalMs(long promotionIntervalMs) {
        this.promotionIntervalMs = promotionIntervalMs;
    }

    public int getPromotionBatchSize() {
        return promotionBatchSize;
    }

    public void setPromotionBatchSize(int promotionBatchSize) {
        this.promotionBatchSize = promotionBatchSize;
    }

    public double getOversubscribeFactor() {
        return oversubscribeFactor;
    }

    public void setOversubscribeFactor(double oversubscribeFactor) {
        this.oversubscribeFactor = oversubscribeFactor;
    }

    public long getSsePositionIntervalMs() {
        return ssePositionIntervalMs;
    }

    public void setSsePositionIntervalMs(long ssePositionIntervalMs) {
        this.ssePositionIntervalMs = ssePositionIntervalMs;
    }

    public long getSseHeartbeatMs() {
        return sseHeartbeatMs;
    }

    public void setSseHeartbeatMs(long sseHeartbeatMs) {
        this.sseHeartbeatMs = sseHeartbeatMs;
    }

    public int getHeartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }

    public void setHeartbeatTtlSeconds(int heartbeatTtlSeconds) {
        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
    }
}
