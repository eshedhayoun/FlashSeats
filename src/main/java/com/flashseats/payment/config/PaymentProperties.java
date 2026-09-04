package com.flashseats.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment tunables. */
@ConfigurationProperties(prefix = "flashseats.payment")
public class PaymentProperties {

    /**
     * How long the duplicate-click guard holds a hold token, roughly one gateway timeout (ADR-014).
     *
     * <p>Deliberately short. An earlier design used 24 hours, which meant a crash mid-charge locked
     * that buyer out for a day.
     */
    private int inflightTtlSeconds = 90;

    public int getInflightTtlSeconds() {
        return inflightTtlSeconds;
    }

    public void setInflightTtlSeconds(int inflightTtlSeconds) {
        this.inflightTtlSeconds = inflightTtlSeconds;
    }
}
