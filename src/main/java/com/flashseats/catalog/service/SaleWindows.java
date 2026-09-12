package com.flashseats.catalog.service;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.EventStatus;
import java.time.Instant;

/**
 * Derives {@link EventWindowStatus} from an event and the server's clock.
 *
 * <p>One place, because four call sites depend on the answer — the landing page, the queue join
 * gate, the hold gate and the checkout gate — and a second implementation that rounded a boundary
 * differently would open a sale to one endpoint and not another.
 *
 * <p>It takes an {@link EventRow} rather than the entity so that the answer is computed from a
 * snapshot on every call. <strong>The status is never cached</strong>: it flips on clock movement
 * alone, with no write to evict on, so a stored copy is stale with nothing to notice it (ADR-051).
 */
public final class SaleWindows {

    private SaleWindows() {}

    public static EventWindowStatus statusOf(EventRow event, Instant now) {
        if (event.status() != EventStatus.PUBLISHED || !now.isBefore(event.saleEndTime())) {
            return EventWindowStatus.CLOSED;
        }
        return now.isBefore(event.saleStartTime())
                ? EventWindowStatus.UPCOMING
                : EventWindowStatus.OPEN;
    }
}
