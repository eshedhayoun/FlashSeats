package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The tier genuinely does not have enough seats left.
 *
 * <p>Distinct from {@link InventoryUnavailableException}, and the distinction matters: this one
 * means "pick another tier", that one means "we cannot see our own inventory". Collapsing them would
 * tell thousands of buyers the sale ended when a counter was merely missing (ADR-004).
 */
public class InsufficientStockException extends FlashSeatsException {

    public InsufficientStockException(long tierId, int requested) {
        super(
                ErrorCode.INSUFFICIENT_STOCK,
                "Only fewer than " + requested + " seats remain in this tier. Try another tier.");
        with("retryable", false);
    }
}
