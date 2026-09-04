package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The charge settled, the seats could not be delivered, and the money has been refunded (ADR-012).
 *
 * <p>Deliberately <em>not</em> reported as an expired hold. That message promises "nothing was
 * charged", which would be false here — and a buyer who sees a charge on their statement after being
 * told nothing happened loses trust that is hard to win back.
 */
public class OrderRefundedException extends FlashSeatsException {

    public OrderRefundedException(String orderNumber) {
        super(
                ErrorCode.ORDER_REFUNDED,
                "Your seats were taken before payment completed. You have been refunded in full.");
        with("retryable", false);
    }
}
