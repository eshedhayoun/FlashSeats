package com.flashseats.catalog.service;

import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.facade.EventWindowStatus;
import java.time.Instant;

/**
 * Derives {@link EventWindowStatus} from an event and the server's clock.
 *
 * <p>One place, because four call sites depend on the answer — the landing page, the queue join
 * gate, the hold gate and the checkout gate — and a second implementation that rounded a boundary
 * differently would open a sale to one endpoint and not another.
 */
public final class SaleWindows {

    private SaleWindows() {}

    public static EventWindowStatus statusOf(Event event, Instant now) {
        if (event.getStatus() != EventStatus.PUBLISHED || !now.isBefore(event.getSaleEndTime())) {
            return EventWindowStatus.CLOSED;
        }
        return now.isBefore(event.getSaleStartTime())
                ? EventWindowStatus.UPCOMING
                : EventWindowStatus.OPEN;
    }
}
