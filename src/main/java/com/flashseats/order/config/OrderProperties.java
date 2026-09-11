package com.flashseats.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Checkout and outbox tunables. */
@ConfigurationProperties(prefix = "flashseats.order")
public class OrderProperties {

    /**
     * How long after {@code sale_end_time} a checkout already in progress may still complete
     * (ADR-016). A buyer who reached the payment form in time should not be cut off mid-transaction.
     */
    private int checkoutGraceMinutes = 15;

    /** Charge attempts per hold (ADR-014). Retrying a decline changes nothing but the fraud signal. */
    private int maxPaymentAttempts = 3;

    /**
     * A retry submitted with less time left than this returns {@code 409} instead of charging
     * (ADR-030) — better to say so plainly than to start a charge that cannot finish.
     */
    private int minRemainingSecondsForRetry = 45;

    /**
     * How long a {@code PENDING} order is presumed to have a charge in flight (ADR-034).
     *
     * <p>Past this, the row is treated as stranded and a retry resumes it on the same order number.
     * It mirrors {@code flashseats.payment.inflight-ttl-seconds} — the duplicate-click guard whose
     * expiry means no charge can still be running — but is declared here rather than read across
     * the module boundary.
     *
     * <p><strong>Never zero.</strong> A value below the gateway timeout would let a second request
     * start a charge while the first is still live.
     */
    private int stalePendingSeconds = 90;

    /**
     * How long a rebuild waits between its two ledger snapshots.
     *
     * <p>A reserve decrements Redis a moment before its {@code ticket_holds} row commits, so a single
     * snapshot can miss a hold that is seconds from existing and compute a count that is too high —
     * an oversell written by the very procedure meant to repair one. Two snapshots a settling window
     * apart, and the smaller of the two, make that impossible: an in-flight hold has landed by the
     * second read, while seats genuinely abandoned read the same both times and are correctly
     * returned.
     *
     * <p>Must comfortably exceed the reserve-to-insert window, which is one local INSERT.
     */
    private int rebuildSettleMillis = 1_000;

    /** How often the stock-drift gauge is recomputed (ADR-045, invariant 1). */
    private int driftIntervalMs = 60_000;

    /** Signs receipt tokens. Rotating it invalidates every outstanding receipt link. */
    private String receiptSecret = "dev-only-change-me";

    /** How long a receipt link stays usable (ADR-039). Long enough to survive a forwarded email. */
    private int receiptTokenTtlDays = 90;

    public int getCheckoutGraceMinutes() {
        return checkoutGraceMinutes;
    }

    public void setCheckoutGraceMinutes(int checkoutGraceMinutes) {
        this.checkoutGraceMinutes = checkoutGraceMinutes;
    }

    public int getMaxPaymentAttempts() {
        return maxPaymentAttempts;
    }

    public void setMaxPaymentAttempts(int maxPaymentAttempts) {
        this.maxPaymentAttempts = maxPaymentAttempts;
    }

    public int getMinRemainingSecondsForRetry() {
        return minRemainingSecondsForRetry;
    }

    public void setMinRemainingSecondsForRetry(int minRemainingSecondsForRetry) {
        this.minRemainingSecondsForRetry = minRemainingSecondsForRetry;
    }

    public int getStalePendingSeconds() {
        return stalePendingSeconds;
    }

    public void setStalePendingSeconds(int stalePendingSeconds) {
        this.stalePendingSeconds = stalePendingSeconds;
    }

    public int getRebuildSettleMillis() {
        return rebuildSettleMillis;
    }

    public void setRebuildSettleMillis(int rebuildSettleMillis) {
        this.rebuildSettleMillis = rebuildSettleMillis;
    }

    public int getDriftIntervalMs() {
        return driftIntervalMs;
    }

    public void setDriftIntervalMs(int driftIntervalMs) {
        this.driftIntervalMs = driftIntervalMs;
    }

    public String getReceiptSecret() {
        return receiptSecret;
    }

    public void setReceiptSecret(String receiptSecret) {
        this.receiptSecret = receiptSecret;
    }

    public int getReceiptTokenTtlDays() {
        return receiptTokenTtlDays;
    }

    public void setReceiptTokenTtlDays(int receiptTokenTtlDays) {
        this.receiptTokenTtlDays = receiptTokenTtlDays;
    }
}
