package com.flashseats.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outbox relay tunables. */
@ConfigurationProperties(prefix = "flashseats.outbox")
public class OutboxProperties {

    private long pollIntervalMs = 1_000;

    private int batchSize = 100;

    /**
     * Which {@link com.flashseats.order.service.OutboxPublisher} bean is active — {@code rabbit} or
     * {@code log}.
     *
     * <p>Bound here so it appears in configuration metadata and a typo is a startup failure rather
     * than a silent fallback. {@code OutboxPublisherConfig} selects on the raw property because
     * {@code @ConditionalOnProperty} is evaluated before any binding exists; the two must agree.
     */
    private String transport = "rabbit";

    /**
     * How long a whole batch may take to be confirmed by the broker.
     *
     * <p>Spent once per batch, not once per message. An unconfirmed row is not lost — it stays
     * {@code PROCESSING} until {@link #staleClaimSeconds} returns it — so this bounds how long the
     * relay thread waits on a sick broker, nothing more. Keep it comfortably under
     * {@link #pollIntervalMs} × the tolerance for relay lag.
     */
    private long confirmTimeoutMs = 5_000;

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

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public long getConfirmTimeoutMs() {
        return confirmTimeoutMs;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
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
