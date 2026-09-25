package com.flashseats.catalog.service;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import java.time.Instant;

/**
 * An immutable snapshot of one {@code events} row.
 *
 * <p><strong>A record, not the entity.</strong> {@code Event} is a mutable Lombok bean, and a cache
 * that handed the same instance to every request thread would be sharing mutable state across the
 * whole cluster's traffic. This carries only the columns something reads.
 *
 * <p>It deliberately stores <em>no window status</em>: {@link #windowStatus} derives it from this row
 * and the clock on every call, because a stored copy goes stale the moment the clock moves past a
 * boundary, with no write to evict on (ADR-051).
 */
public record EventRow(
        long id,
        String title,
        String description,
        String venueName,
        Instant eventStartTime,
        Instant saleStartTime,
        Instant saleEndTime,
        EventStatus status) {

    /**
     * The sale window, derived from the clock on every call and never cached (ADR-051). There is one
     * implementation because four gates depend on it (landing page, queue join, hold, checkout), and
     * a second one that rounded a boundary differently would open a sale to one and not another.
     */
    public EventWindowStatus windowStatus(Instant now) {
        if (status != EventStatus.PUBLISHED || !now.isBefore(saleEndTime)) {
            return EventWindowStatus.CLOSED;
        }
        return now.isBefore(saleStartTime) ? EventWindowStatus.UPCOMING : EventWindowStatus.OPEN;
    }

    /** The one place an entity becomes a snapshot, cached or not. */
    static EventRow of(Event event) {
        return new EventRow(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenueName(),
                event.getEventStartTime(),
                event.getSaleStartTime(),
                event.getSaleEndTime(),
                event.getStatus());
    }
}
