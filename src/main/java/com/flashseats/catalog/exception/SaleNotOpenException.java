package com.flashseats.catalog.exception;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The action requires an open sale window (ADR-016).
 *
 * <p>{@code UPCOMING} and {@code CLOSED} map to different codes on purpose: the SPA shows a
 * countdown for one and a sale-ended panel for the other.
 */
public class SaleNotOpenException extends FlashSeatsException {

    public SaleNotOpenException(long eventId, EventWindowStatus actual) {
        super(
                actual == EventWindowStatus.CLOSED ? ErrorCode.SALE_CLOSED : ErrorCode.SALE_NOT_OPEN,
                actual == EventWindowStatus.CLOSED
                        ? "Sales for this event have ended."
                        : "This sale has not started yet.");
    }
}
