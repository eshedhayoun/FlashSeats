package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * No such order, or the caller may not see it.
 *
 * <p>Both are {@code 404}. An order number is short and guessable, so distinguishing "exists but not
 * yours" would turn it into an enumeration oracle over buyers' email addresses (ADR-010).
 */
public class OrderNotFoundException extends FlashSeatsException {

    public OrderNotFoundException(String orderNumber) {
        super(ErrorCode.ORDER_NOT_FOUND, "No order found.");
    }
}
