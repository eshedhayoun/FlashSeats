package com.flashseats.catalog.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * Pre-warm was attempted on a sale that is no longer {@code UPCOMING}.
 *
 * <p>This refusal is a safety interlock, not a formality: seeding inventory from
 * {@code total_capacity} once a sale is open would silently resurrect every ticket already sold
 * (ADR-004). Recovery on an open sale is an explicit rebuild from the ledger, never a reseed.
 */
public class PrewarmWindowClosedException extends FlashSeatsException {

    public PrewarmWindowClosedException(long eventId) {
        super(
                ErrorCode.PREWARM_WINDOW_CLOSED,
                "Event " + eventId + " is not UPCOMING; pre-warm would overwrite live inventory.");
    }
}
