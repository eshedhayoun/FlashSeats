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
                requested == 1
                        ? "Those seats just sold. Pick another tier."
                        : "There aren't " + requested + " seats left in this tier. Try fewer seats or another tier.");
        with("retryable", false);
    }
}
