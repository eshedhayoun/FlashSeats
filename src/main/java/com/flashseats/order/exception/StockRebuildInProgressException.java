package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * Another rebuild already holds this event's lock (ADR-004).
 *
 * <p>Refusing is the point. Two rebuilds reading a ledger that is moving under them can each compute
 * a defensible number and write the other's away — a recovery that makes the fault worse.
 */
public class StockRebuildInProgressException extends FlashSeatsException {

    public StockRebuildInProgressException(long eventId) {
        super(
                ErrorCode.STOCK_REBUILD_IN_PROGRESS,
                "A stock rebuild is already running for event " + eventId + ". Retry shortly.");
    }
}
