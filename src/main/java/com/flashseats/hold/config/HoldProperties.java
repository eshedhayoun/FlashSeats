package com.flashseats.hold.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hold timers and limits (ADR-006, ADR-017, ADR-030).
 *
 * <p>Every one of these is a named property rather than a literal, because the client renders what
 * the server says: hardcoding "5 minutes" or "max 4" in a UI component is how a limit change becomes
 * a two-repository deploy.
 */
@ConfigurationProperties(prefix = "flashseats.hold")
@Getter
@Setter
public class HoldProperties {

    /** Initial reservation window. */
    private int ttlSeconds = 300;

    /** The single grace extension granted before the first charge attempt. */
    private int graceSeconds = 120;

    /** Absolute ceiling from creation. 300 + 120 — a hold can never outlive this. */
    private int maxTtlSeconds = 420;

    /** Seats per hold (ADR-017). The tier's own {@code maxPerOrder} may be lower. */
    private int maxQuantity = 6;

    private long sweeperIntervalMs = 10_000;

    /** Rows the sweeper claims per pass; bounds its transaction size under a large expiry burst. */
    private int sweeperBatchSize = 500;
}
