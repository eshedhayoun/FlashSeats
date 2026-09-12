package com.flashseats.shared.ticket;

import java.time.Instant;
import java.util.List;

/**
 * Everything a ticket PDF needs, and nothing else.
 *
 * <p><strong>This is the renderer's parameter type, not a DTO shared between two modules.</strong>
 * The distinction matters, because the kernel rules forbid the latter (standards §8): a record that
 * two modules pass to each other belongs in the callee's {@code facade} package. Nothing is passed
 * here. {@code notification} and {@code order} each map their own payload into this shape and call a
 * pure function; neither learns anything about the other, and the module graph is unchanged.
 *
 * <p>It is deliberately <em>narrower</em> than either caller's own type. There is no email address,
 * no receipt token, no amount and no currency — a ticket is what someone holds at a door, and a
 * renderer that cannot see a bearer capability cannot print one onto a page by accident.
 */
public record TicketDocument(
        String orderNumber, String eventTitle, String venueName, Instant eventStartTime, List<Seat> seats) {

    /** One line item: a tier, and how many people it admits. One page is rendered per seat. */
    public record Seat(String tierName, int quantity) {}
}
