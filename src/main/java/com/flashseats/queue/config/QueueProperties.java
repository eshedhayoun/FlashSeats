package com.flashseats.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Waiting-room tunables (ADR-007, ADR-020, ADR-026, ADR-028, ADR-049). */
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
     * Cluster-wide database connection budget available to promoted buyers each promotion interval
     * (ADR-049). This is shared across every open sale; {@link #promotionBatchSize} remains the
     * per-event fairness cap.
     */
    private int globalAdmissionConnectionBudget = 90;

    /**
     * Estimated database connection cost of one buyer admitted into checkout (ADR-049). The global
     * admission count per tick is {@code globalAdmissionConnectionBudget / databaseConnectionsPerBuyer}.
     */
    private int databaseConnectionsPerBuyer = 8;

    /**
     * Hold-to-order conversion is well under 100%, so admitting exactly {@code remainingStock}
     * buyers leaves the sale under-filled. Every real waiting room tunes this (ADR-020).
     */
    private double oversubscribeFactor = 1.5;

    /** FIFO is the explainable default; RANDOM is the fair drop mode from ADR-024. */
    private QueueOrdering ordering = QueueOrdering.FIFO;

    private long ssePositionIntervalMs = 2_000;

    /** Comment frames that keep proxies from closing an idle stream. */
    private long sseHeartbeatMs = 15_000;

    /**
     * How long this module's per-sale keys outlive the sale itself (ADR-036).
     *
     * <p>Every queue key expires; none is deleted by the application. An hour past
     * {@code sale_end_time} covers the post-close checkout grace and any late rehydration, after
     * which the waiting, pass and admission sets of a finished sale are simply gone. Redis runs
     * {@code noeviction}, so a key with no TTL is a leak nothing else will clean up.
     */
    private long keyRetentionAfterSaleSeconds = 3_600;

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

    public int getGlobalAdmissionConnectionBudget() {
        return globalAdmissionConnectionBudget;
    }

    public void setGlobalAdmissionConnectionBudget(int globalAdmissionConnectionBudget) {
        this.globalAdmissionConnectionBudget = globalAdmissionConnectionBudget;
    }

    public int getDatabaseConnectionsPerBuyer() {
        return databaseConnectionsPerBuyer;
    }

    public void setDatabaseConnectionsPerBuyer(int databaseConnectionsPerBuyer) {
        this.databaseConnectionsPerBuyer = databaseConnectionsPerBuyer;
    }

    public long getGlobalAdmissionBudgetPerTick() {
        if (globalAdmissionConnectionBudget <= 0 || databaseConnectionsPerBuyer <= 0) {
            return 0;
        }
        return globalAdmissionConnectionBudget / databaseConnectionsPerBuyer;
    }

    public double getOversubscribeFactor() {
        return oversubscribeFactor;
    }

    public void setOversubscribeFactor(double oversubscribeFactor) {
        this.oversubscribeFactor = oversubscribeFactor;
    }

    public QueueOrdering getOrdering() {
        return ordering;
    }

    public void setOrdering(QueueOrdering ordering) {
        this.ordering = ordering;
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

    public long getKeyRetentionAfterSaleSeconds() {
        return keyRetentionAfterSaleSeconds;
    }

    public void setKeyRetentionAfterSaleSeconds(long keyRetentionAfterSaleSeconds) {
        this.keyRetentionAfterSaleSeconds = keyRetentionAfterSaleSeconds;
    }
}
