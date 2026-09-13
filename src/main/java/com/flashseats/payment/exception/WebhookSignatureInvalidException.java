package com.flashseats.payment.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The webhook body did not carry a signature this deployment's secret produces.
 *
 * <p>The webhook endpoint is unauthenticated by necessity — the provider cannot hold a session — so
 * the signature is the <strong>only</strong> thing separating a real settlement from anyone who can
 * reach the port and knows an order number. A request that fails it is refused before its contents
 * are read as anything but bytes.
 *
 * <p>{@code 400}, not {@code 401}: the provider retries non-2xx, and a caller who cannot sign will
 * not do better on the fourth attempt. {@code retryable} is false for the same reason.
 */
public class WebhookSignatureInvalidException extends FlashSeatsException {

    public WebhookSignatureInvalidException(Throwable cause) {
        super(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Webhook signature verification failed.", cause);
        with("retryable", false);
    }
}
