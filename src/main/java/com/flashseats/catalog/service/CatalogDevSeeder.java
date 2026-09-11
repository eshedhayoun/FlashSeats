package com.flashseats.catalog.service;

import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.model.TierInventory;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import com.flashseats.catalog.repository.TierInventoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demonstrable sale on the {@code dev} profile so the app is walkable the moment it starts.
 *
 * <p>Two events, on purpose:
 *
 * <ul>
 *   <li><strong>An OPEN sale</strong> with inventory written directly. Pre-warm cannot be used here:
 *       it refuses on anything but an {@code UPCOMING} window (ADR-004), so a demo event that is
 *       open on startup would otherwise have no counters and every hold would fail.
 *   <li><strong>An UPCOMING sale</strong> with no inventory, so the countdown and the admin pre-warm
 *       path stay demonstrable.
 * </ul>
 *
 * <p>Runs only when the database is empty, so a restart never duplicates or resets a sale in
 * progress.
 */
@Slf4j
@Component
@Profile("dev")
public class CatalogDevSeeder implements ApplicationRunner {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final TierInventoryRepository inventory;
    private final Clock clock;

    public CatalogDevSeeder(
            EventRepository events,
            TicketTierRepository tiers,
            TierInventoryRepository inventory,
            Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (events.count() > 0) {
            return;
        }
        Instant now = clock.instant();

        Event live = saveEvent(
                "Aurora Fest 2026",
                "Three stages, one night, under the northern lights.",
                "Riverside Arena",
                now.plus(Duration.ofDays(60)),
                now.minus(Duration.ofMinutes(1)), // already open, so the demo starts immediately
                now.plus(Duration.ofHours(8)));
        seedTier(live, "VIP", 7_500, 50, 6);
        seedTier(live, "Floor", 4_500, 150, 6);
        seedTier(live, "General Admission", 2_500, 500, 6);

        Event upcoming = saveEvent(
                "Midnight Sessions",
                "An intimate late set. Sale opens shortly.",
                "The Vault",
                now.plus(Duration.ofDays(90)),
                now.plus(Duration.ofMinutes(30)), // still UPCOMING, so pre-warm is demonstrable
                now.plus(Duration.ofDays(2)));
        // Deliberately no inventory row: POST /api/v1/admin/events/{id}/prewarm creates it.
        tier(upcoming, "General Admission", 3_000, 200, 4);

        log.info(
                "Seeded dev catalog: event {} is OPEN with 700 seats, event {} is UPCOMING and un-warmed",
                live.getId(),
                upcoming.getId());
    }

    private Event saveEvent(
            String title,
            String description,
            String venue,
            Instant eventStart,
            Instant saleStart,
            Instant saleEnd) {
        Event event = new Event();
        event.setTitle(title);
        event.setDescription(description);
        event.setVenueName(venue);
        event.setEventStartTime(eventStart);
        event.setSaleStartTime(saleStart);
        event.setSaleEndTime(saleEnd);
        event.setStatus(EventStatus.PUBLISHED);
        return events.save(event);
    }

    private TicketTier tier(Event event, String name, long priceCents, int capacity, int maxPerOrder) {
        TicketTier tier = new TicketTier();
        tier.setEventId(event.getId());
        tier.setTierName(name);
        tier.setPriceCents(priceCents);
        tier.setCurrency("USD");
        tier.setTotalCapacity(capacity);
        tier.setMaxPerOrder(maxPerOrder);
        return tiers.save(tier);
    }

    private void seedTier(Event event, String name, long priceCents, int capacity, int maxPerOrder) {
        TicketTier tier = tier(event, name, priceCents, capacity, maxPerOrder);
        inventory.save(new TierInventory(tier.getId(), event.getId(), capacity));
    }
}
