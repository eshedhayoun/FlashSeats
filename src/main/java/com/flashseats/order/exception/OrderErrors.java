package com.flashseats.order.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import com.flashseats.order.model.OrderStatus;
import java.time.Instant;

/** The refusals {@code order} raises (ADR-057, ADR-063). */
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
     * A resend was asked for, but the outbox payload has passed
     * {@code flashseats.outbox.purge-after-days}. It is not reconstructed from the current catalog,
     * which would print the event as it is now, not as it was sold. {@code 410 Gone}, not {@code 404}:
     * the order exists, its message aged out.
     */
    public static FlashSeatsException notificationPayloadUnavailable(String orderNumber) {
        return new FlashSeatsException(
                ErrorCode.NOTIFICATION_PAYLOAD_UNAVAILABLE,
                "No stored message remains for order " + orderNumber + "; it has passed the outbox"
                        + " retention window and cannot be replayed.");
    }

    /**
     * The charge settled, the seats could not be delivered, and the money was refunded (ADR-012).
     * Never reported as an expired hold: that message promises nothing was charged, which would be
     * false here.
     */
    public static FlashSeatsException refunded() {
        return new FlashSeatsException(
                        ErrorCode.ORDER_REFUNDED,
                        "Your seats were taken before payment completed. You have been refunded in full.")
                .with("retryable", false);
    }

    /**
     * The caller owns this order, and it has no ticket (ADR-050). Only a {@code CONFIRMED} order
     * has one: a PDF for anything else is a forgery this system printed itself. {@code PENDING} may
     * still confirm, so it alone is {@code retryable}.
     */
    public static FlashSeatsException ticketNotAvailable(OrderStatus status) {
        return new FlashSeatsException(
                        ErrorCode.TICKET_NOT_AVAILABLE,
                        status == OrderStatus.PENDING
                                ? "This order is still being completed. Your ticket will be ready shortly."
                                : "There is no ticket for this order.")
                .with("orderStatus", status.name())
                .with("retryable", status == OrderStatus.PENDING);
    }
}
