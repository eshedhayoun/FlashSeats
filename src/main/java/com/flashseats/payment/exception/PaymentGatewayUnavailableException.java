package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The provider could not be reached.
 *
 * <p><strong>The buyer's seats are retained.</strong> A gateway outage is our problem, not theirs,
 * and destroying a reservation because a third party is slow would be the wrong trade every time.
 */
public class PaymentGatewayUnavailableException extends FlashSeatsException {

    public PaymentGatewayUnavailableException(String reason) {
        super(
                ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "The payment provider is having trouble. Your seats are still held — please retry.");
        with("retryable", true);
        with("retryAfterSeconds", 5);
    }
}
