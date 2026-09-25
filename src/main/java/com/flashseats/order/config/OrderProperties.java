package com.flashseats.order.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Checkout and outbox tunables. */
@ConfigurationProperties(prefix = "flashseats.order")
@Getter
@Setter
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
     * How long a {@code PENDING} order is presumed to have a charge in flight (ADR-034); past it, a
     * retry resumes the order. It mirrors {@code flashseats.payment.inflight-ttl-seconds}. Never below
     * the gateway timeout, or a second charge could start while the first is live.
     */
    private int stalePendingSeconds = 90;

    /**
     * How long a rebuild waits between its two ledger snapshots, so a reserve whose hold row has not
     * yet committed is caught by the second read. Must comfortably exceed one local INSERT.
     */
    private int rebuildSettleMillis = 1_000;

    /** How often the stock-drift gauge is recomputed (ADR-045, invariant 1). */
    private int driftIntervalMs = 60_000;

    /** Signs receipt tokens. Rotating it invalidates every outstanding receipt link. */
    private String receiptSecret = "dev-only-change-me";

    /** How long a receipt link stays usable (ADR-039). Long enough to survive a forwarded email. */
    private int receiptTokenTtlDays = 90;
}
