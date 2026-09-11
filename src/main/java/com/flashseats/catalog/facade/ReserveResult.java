package com.flashseats.catalog.facade;

/**
 * What happened when seats were taken from a tier.
 *
 * <p>An enum rather than a {@code boolean} because "no" has two meanings and they are not
 * interchangeable. {@link #INSUFFICIENT} is a fact about a working sale — tell the buyer to try
 * another tier. {@link #COUNTER_MISSING} means the system cannot read its own inventory, which is a
 * {@code 503} and an alarm. Answering the second with the first is ADR-004's failure: it announces a
 * sold-out sale to everyone because a key went missing.
 *
 * <p>The distinction is settled inside the reserve script, atomically. It used to be recovered
 * afterwards by re-reading the counter, which raced — a restore landing in between turned a fault
 * into "sold out".
 */
public enum ReserveResult {

    /** The seats are yours; record the hold that justifies them. */
    RESERVED,

    /** The counter was readable and too low. Genuinely sold out. */
    INSUFFICIENT,

    /** There is no counter. A fault to alarm on and rebuild, never "sold out". */
    COUNTER_MISSING
}
