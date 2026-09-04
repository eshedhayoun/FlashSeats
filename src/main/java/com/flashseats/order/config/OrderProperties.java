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

    /** Signs receipt tokens. Rotating it invalidates every outstanding receipt link. */
    private String receiptSecret = "dev-only-change-me";

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

    public String getReceiptSecret() {
        return receiptSecret;
    }

    public void setReceiptSecret(String receiptSecret) {
        this.receiptSecret = receiptSecret;
    }
}
