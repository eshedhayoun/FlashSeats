package com.flashseats.shared.ticket;

import java.time.Instant;
import java.util.List;

/**
 * Everything a ticket PDF needs, and nothing else: the renderer's parameter type, not a DTO passed
 * between modules (global standards §8). It is deliberately narrower than either caller's payload,
 * with no email, receipt token or amount, so the renderer cannot print a bearer capability.
 */
public record TicketDocument(
        String orderNumber, String eventTitle, String venueName, Instant eventStartTime, List<Seat> seats) {

    /** One line item: a tier, and how many people it admits. One page is rendered per seat. */
    public record Seat(String tierName, int quantity) {}
}
