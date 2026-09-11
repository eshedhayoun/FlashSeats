package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** Past the post-close checkout grace (ADR-016). */
public class CheckoutWindowClosedException extends FlashSeatsException {

    public CheckoutWindowClosedException() {
        super(ErrorCode.CHECKOUT_WINDOW_CLOSED, "Sales for this event have closed.");
    }
}
