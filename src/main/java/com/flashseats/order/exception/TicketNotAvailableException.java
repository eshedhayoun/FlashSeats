package com.flashseats.order.exception;

import com.flashseats.order.model.OrderStatus;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The caller owns this order, and it has no ticket (ADR-050).
 *
 * <p><strong>Only a {@code CONFIRMED} order has a ticket.</strong> Rendering one for any other
 * status would produce a document indistinguishable from a real ticket for a purchase that did not
 * complete — a forgery this system printed itself. A {@code REFUNDED} order is the sharp case: the
 * money has gone back, so a PDF that still admits someone at a door is worse than no PDF at all.
 *
 * <p>{@code retryable} carries the difference the buyer actually needs. {@code PENDING} is in flight
 * and may yet confirm (ADR-034), so waiting is the right advice; every other status is terminal and
 * waiting is not.
 */
public class TicketNotAvailableException extends FlashSeatsException {

    public TicketNotAvailableException(String orderNumber, OrderStatus status) {
        super(
                ErrorCode.TICKET_NOT_AVAILABLE,
                status == OrderStatus.PENDING
                        ? "This order is still being completed. Your ticket will be ready shortly."
                        : "There is no ticket for this order.");
        with("orderStatus", status.name());
        with("retryable", status == OrderStatus.PENDING);
    }
}
