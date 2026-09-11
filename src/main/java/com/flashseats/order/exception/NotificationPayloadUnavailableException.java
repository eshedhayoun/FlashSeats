package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * A resend was asked for, but the message it would replay no longer exists.
 *
 * <p>{@code outbox_events} keeps payloads for {@code flashseats.outbox.purge-after-days} and the
 * nightly purge has removed this one. The ticket is not recoverable by replay: the snapshot it was
 * rendered from is gone, and rebuilding one from the current catalog would produce a ticket for the
 * event as it is <em>now</em> rather than as it was sold.
 *
 * <p>{@code 410 Gone} rather than {@code 404}, and the distinction is the useful part of the answer:
 * the order existed and its message existed, they have simply aged out. A {@code 404} would send an
 * operator looking for a typo in the order number.
 */
public class NotificationPayloadUnavailableException extends FlashSeatsException {

    public NotificationPayloadUnavailableException(String orderNumber) {
        super(
                ErrorCode.NOTIFICATION_PAYLOAD_UNAVAILABLE,
                "No stored message remains for order " + orderNumber + "; it has passed the outbox"
                        + " retention window and cannot be replayed.");
    }
}
