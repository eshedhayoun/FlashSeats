package com.flashseats.catalog.service;

import com.flashseats.catalog.dto.CreateEventRequest;
import com.flashseats.catalog.exception.CatalogErrors;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL halves of creating and deleting an event. Their own bean so each commits before
 * {@link CatalogService} touches Redis: a counter written inside the transaction would survive its
 * rollback (ADR-023, §11 rule 4).
 */
@Component
class EventStore {

    private static final String CURRENCY = "USD";

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final ApplicationEventPublisher publisher;

    EventStore(EventRepository events, TicketTierRepository tiers, ApplicationEventPublisher publisher) {
        this.events = events;
        this.tiers = tiers;
        this.publisher = publisher;
    }

    /** The event, published, and its tiers. Returns the new event id. */
    @Transactional
    public long insert(CreateEventRequest request) {
        Event event = new Event();
        event.setTitle(request.title().strip());
        event.setDescription(
                request.description() == null || request.description().isBlank()
                        ? null
                        : request.description().strip());
        event.setVenueName(request.venueName().strip());
        event.setEventStartTime(request.eventStartTime());
        event.setSaleStartTime(request.saleStartTime());
        event.setSaleEndTime(request.saleEndTime());
        event.setStatus(EventStatus.PUBLISHED);
        long eventId = events.save(event).getId();

        for (CreateEventRequest.Tier requested : request.tiers()) {
            TicketTier tier = new TicketTier();
            tier.setEventId(eventId);
            tier.setTierName(requested.tierName().strip());
            tier.setPriceCents(requested.priceCents());
            tier.setCurrency(CURRENCY);
            tier.setTotalCapacity(requested.totalCapacity());
            tier.setMaxPerOrder(requested.maxPerOrder());
            tiers.save(tier);
        }
        publisher.publishEvent(new EventMetadataChanged(eventId));
        return eventId;
    }

    /**
     * Deletes the event and its tiers if its sale is still more than {@link EventRow#DELETE_CUTOFF}
     * away. The guard is the {@code DELETE}'s own condition, so a concurrent delete or a clock edge
     * cannot slip between a check and the write; a refusal rolls the tier delete back with it.
     *
     * @return the deleted tier ids, whose counters the caller removes after commit
     */
    @Transactional
    public List<Long> delete(long eventId, Instant now) {
        List<Long> tierIds = tiers.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(TicketTier::getId)
                .toList();
        tiers.deleteByEventId(eventId);
        if (events.deleteIfSaleStartsAfter(eventId, now.plus(EventRow.DELETE_CUTOFF)) == 0) {
            throw events.existsById(eventId)
                    ? CatalogErrors.eventNotDeletable(eventId)
                    : CatalogErrors.eventNotFound(eventId);
        }
        publisher.publishEvent(new EventMetadataChanged(eventId));
        return tierIds;
    }
}
