package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * A charge for this hold is already in flight.
 *
 * <p>{@code 409}, never a wait: blocking a request until a concurrent duplicate finishes ties up a
 * connection and tells the client nothing useful. The client polls the sale state instead (global
 * standards §3).
 */
public class DuplicatePaymentException extends FlashSeatsException {

    public DuplicatePaymentException(String holdToken) {
        super(ErrorCode.DUPLICATE_PAYMENT, "A payment for this reservation is already being processed.");
        with("retryable", false);
    }
}
