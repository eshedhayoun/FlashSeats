package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The inventory counter for this tier is <strong>missing</strong> — a fault, not a sold-out sale.
 *
 * <p>This is the single most important distinction in the system (ADR-004). A missing counter means
 * inventory state has been lost and must be rebuilt from the ledger; it never means the tier is
 * gone. The client must render "having trouble, retrying", never "sold out".
 */
public class InventoryUnavailableException extends FlashSeatsException {

    public InventoryUnavailableException(long tierId) {
        super(
                ErrorCode.INVENTORY_UNAVAILABLE,
                "Availability is temporarily unreadable. Nothing was reserved — please retry.");
        with("retryable", true);
        with("retryAfterSeconds", 2);
    }
}
