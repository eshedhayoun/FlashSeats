package com.flashseats.catalog.facade;

/**
 * What happened when seats were taken from a tier. "No" has two meanings: {@link #INSUFFICIENT}
 * (try another tier) and {@link #COUNTER_MISSING} (we cannot read our inventory: {@code 503} and an
 * alarm). Confusing them announces a sold-out sale because a key is missing (ADR-004). The reserve
 * script decides between them atomically.
 */
public enum ReserveResult {

    /** The seats are yours; record the hold that justifies them. */
    RESERVED,

    /** The counter was readable and too low. Genuinely sold out. */
    INSUFFICIENT,

    /** There is no counter. A fault to alarm on and rebuild, never "sold out". */
    COUNTER_MISSING
}
