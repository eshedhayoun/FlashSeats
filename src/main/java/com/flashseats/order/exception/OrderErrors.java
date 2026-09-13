package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import java.time.Instant;

/**
 * The refusals {@code order} raises that carry no branching of their own.
 *
 * <p>Two of this module's failures are still classes: {@link OrderRefundedException}, because it is
 * the one answer that must never be mistaken for an expired hold — that message promises nothing was
 * charged, and here it would be false — and {@link TicketNotAvailableException}, which branches both
 * its wording and its {@code retryable} flag on the order's status.
 */
public final class OrderErrors {

    private OrderErrors() {}

    /**
     * No such order, or the caller may not see it.
     *
     * <p>Both are {@code 404}. An order number is short and guessable, so distinguishing "exists but
     * not yours" would turn it into an enumeration oracle over buyers' email addresses (ADR-010).
     */
    public static FlashSeatsException orderNotFound(String orderNumber) {
        return new FlashSeatsException(ErrorCode.ORDER_NOT_FOUND, "No order found.");
    }

    /** Past the post-close checkout grace (ADR-016). */
    public static FlashSeatsException checkoutWindowClosed() {
        return new FlashSeatsException(
                ErrorCode.CHECKOUT_WINDOW_CLOSED, "Sales for this event have closed.");
    }

    /**
     * Too little of the reservation is left to complete a charge safely (ADR-030).
     *
     * <p>Refusing here is kinder than trying. Starting a charge that cannot finish inside the window
     * risks taking money for seats that expire mid-transaction — the exact situation the refund path
     * exists to clean up, and better avoided than compensated.
     */
    public static FlashSeatsException insufficientTimeRemaining(Instant expiresAt) {
        return new FlashSeatsException(
                        ErrorCode.INSUFFICIENT_TIME_REMAINING,
                        "There is not enough time left on your reservation to complete this safely.")
                .with("retryable", false)
                .with("expiresAt", expiresAt);
    }

    /**
     * Another rebuild already holds this event's lock (ADR-004).
     *
     * <p>Refusing is the point. Two rebuilds reading a ledger that is moving under them can each
     * compute a defensible number and write the other's away — a recovery that makes the fault worse.
     */
    public static FlashSeatsException stockRebuildInProgress(long eventId) {
        return new FlashSeatsException(
                ErrorCode.STOCK_REBUILD_IN_PROGRESS,
                "A stock rebuild is already running for event " + eventId + ". Retry shortly.");
    }

    /**
     * A resend was asked for, but the message it would replay no longer exists.
     *
     * <p>{@code outbox_events} keeps payloads for {@code flashseats.outbox.purge-after-days} and the
     * nightly purge has removed this one. The ticket is not recoverable by replay: the snapshot it was
     * rendered from is gone, and rebuilding one from the current catalog would produce a ticket for
     * the event as it is <em>now</em> rather than as it was sold.
     *
     * <p>{@code 410 Gone} rather than {@code 404}, and the distinction is the useful part of the
     * answer: the order existed and its message existed, they have simply aged out. A {@code 404}
     * would send an operator looking for a typo in the order number.
     */
    public static FlashSeatsException notificationPayloadUnavailable(String orderNumber) {
        return new FlashSeatsException(
                ErrorCode.NOTIFICATION_PAYLOAD_UNAVAILABLE,
                "No stored message remains for order " + orderNumber + "; it has passed the outbox"
                        + " retention window and cannot be replayed.");
    }
}
