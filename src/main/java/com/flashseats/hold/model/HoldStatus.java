package com.flashseats.hold.model;

/**
 * The hold lifecycle.
 *
 * <pre>
 *   create ──► ACTIVE ──┬── consume ──► CONSUMED   (terminal — became an order)
 *                       ├── release ──► RELEASED   (terminal — cancelled)
 *                       └── sweep   ──► EXPIRED    (terminal — abandoned)
 * </pre>
 *
 * <p><strong>No transition leaves a terminal state.</strong> In particular there is no
 * {@code CONSUMED → RELEASED}, which is precisely why checkout charges <em>before</em> consuming
 * (ADR-001): consuming first would need a way back that does not exist, and would briefly release
 * inventory the buyer is actively paying for.
 */
public enum HoldStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
