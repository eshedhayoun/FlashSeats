package com.flashseats.saleflow.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.facade.OrderFacade;
import com.flashseats.queue.facade.QueueFacade;
import com.flashseats.queue.facade.QueueState;
import com.flashseats.saleflow.dto.SaleStateResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps four facade reads into one payload. Makes <strong>no decisions</strong>.
 *
 * <p>Any conditional logic here beyond null-handling would be a business rule that had leaked out of
 * the module that owns it. The only judgement this class exercises is which failures are survivable.
 *
 * <p>It fails <strong>soft per section</strong>: if the queue read throws, {@code queue} comes back
 * null with a {@code partial} marker and the rest still renders. The exception is {@code catalog} —
 * without a window status and a server clock there is nothing meaningful to draw, so that failure
 * surfaces.
 */
@Service
public class SaleStateAssembler {

    private static final Logger log = LoggerFactory.getLogger(SaleStateAssembler.class);

    private final CatalogFacade catalog;
    private final QueueFacade queue;
    private final HoldFacade holds;
    private final OrderFacade orders;
    private final Clock clock;

    public SaleStateAssembler(
            CatalogFacade catalog,
            QueueFacade queue,
            HoldFacade holds,
            OrderFacade orders,
            Clock clock) {
        this.catalog = catalog;
        this.queue = queue;
        this.holds = holds;
        this.orders = orders;
        this.clock = clock;
    }

    public SaleStateResponse assemble(String sessionId, long eventId) {
        // Not guarded: an unreadable catalog means there is no page to render.
        EventSummary event = catalog.getEventSummary(eventId);

        List<String> partial = new ArrayList<>(3);
        Instant now = clock.instant();

        SaleStateResponse.QueueSection queueSection =
                read("queue", partial, () -> toQueueSection(queue.getQueueState(sessionId, eventId)));

        SaleStateResponse.HoldSection holdSection = read(
                "hold",
                partial,
                () -> holds.findActiveHold(sessionId, eventId)
                        .map(hold -> toHoldSection(hold, now))
                        .orElse(null));

        SaleStateResponse.OrderSection orderSection = read(
                "order",
                partial,
                () -> orders.findLatestOrder(sessionId, eventId)
                        .map(order -> new SaleStateResponse.OrderSection(
                                order.orderNumber(), order.status()))
                        .orElse(null));

        return new SaleStateResponse(
                eventId,
                event.windowStatus(),
                now,
                queueSection,
                holdSection,
                orderSection,
                List.copyOf(partial));
    }

    private SaleStateResponse.QueueSection toQueueSection(QueueState state) {
        return new SaleStateResponse.QueueSection(
                state.phase(),
                state.position(),
                state.estWaitSeconds(),
                state.admissionExpiresAt(),
                state.passToken());
    }

    private SaleStateResponse.HoldSection toHoldSection(HoldSummary hold, Instant now) {
        return new SaleStateResponse.HoldSection(
                hold.holdToken(),
                hold.tierId(),
                hold.quantity(),
                hold.expiresAt(),
                Math.max(0, Duration.between(now, hold.expiresAt()).toSeconds()));
    }

    /** Runs one sub-read, recording rather than propagating a failure. */
    private <T> T read(String section, List<String> partial, ThrowingSupplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException failure) {
            log.warn("Sale-state section '{}' unavailable", section, failure);
            partial.add(section);
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
