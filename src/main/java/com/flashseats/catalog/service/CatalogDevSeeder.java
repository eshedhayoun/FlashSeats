package com.flashseats.catalog.service;

import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a demonstrable sale on the {@code dev} profile so the app is walkable the moment it starts.
 *
 * <p>Three events, each earning its place:
 *
 * <ul>
 *   <li><strong>An OPEN sale</strong> with its counters written directly. Pre-warm cannot be used
 *       here: it refuses on anything but an {@code UPCOMING} window (ADR-004), so a demo event that
 *       is open on startup would otherwise have no counters and every hold would answer 503.
 *   <li><strong>A second OPEN sale</strong>, also counter-seeded, so two independent frontend
 *       journeys are walkable at once — which is what rule 5 of {@code FE_SPEC} (concurrent sales)
 *       needs in order to be exercised by hand at all.
 *   <li><strong>An UPCOMING sale with no counter</strong>, which is the only way to reach two things.
 *       {@code POST /admin/events/&#123;id&#125;/prewarm} refuses any other window, so without an
 *       {@code UPCOMING} event on dev it cannot be demonstrated or tried; and the client's countdown
 *       view has nothing to render. Both were lost when this seeder briefly made every event
 *       {@code OPEN}, and neither failed a test, because nothing tests a development affordance.
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
    private final StockCounterRepository stock;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CatalogDevSeeder(
            EventRepository events,
            TicketTierRepository tiers,
            StockCounterRepository stock,
            TransactionTemplate transactions,
            Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.stock = stock;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (events.count() > 0) {
            return;
        }
        // The events and tiers commit first; their counters follow outside the transaction,
        // because a Redis write cannot roll back with it (ADR-023).
        List<StockSeed> counters = transactions.execute(status -> seedDatabase());
        counters.forEach(seed -> stock.seedIfAbsent(seed.eventId(), seed.tierId(), seed.capacity()));
    }

    /** One tier's live counter, seeded after the rows that justify it are committed. */
    private record StockSeed(long eventId, long tierId, int capacity) {}

    private List<StockSeed> seedDatabase() {
        Instant now = clock.instant();
        LocalDate octoberThirtieth = LocalDate.of(
                Year.from(now.atZone(ZoneOffset.UTC)).getValue(), Month.OCTOBER, 30);
        if (octoberThirtieth.atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(now)) {
            octoberThirtieth = octoberThirtieth.plusYears(1);
        }
        Instant eventDate = octoberThirtieth.atTime(20, 0).toInstant(ZoneOffset.UTC);
        Instant saleEnd = octoberThirtieth.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Event live = saveEvent(
                "Aurora Fest 2026",
                "Three stages, one night, under the northern lights.",
                "Riverside Arena",
                eventDate,
                now.minusSeconds(1), // already open, so the demo starts immediately
                saleEnd);
        List<StockSeed> counters = new ArrayList<>();
        counters.add(seedTier(live, "VIP", 7_500, 50, 6));
        counters.add(seedTier(live, "Floor", 4_500, 150, 6));
        counters.add(seedTier(live, "General Admission", 2_500, 500, 6));

        Event secondLive = saveEvent(
                "Midnight Sessions",
                "An intimate late set, on sale now.",
                "The Vault",
                eventDate,
                now.minusSeconds(1),
                saleEnd);
        counters.add(seedTier(secondLive, "General Admission", 3_000, 200, 4));

        Event upcoming = saveEvent(
                "מופע חצות 🎵",
                "A sale that has not opened yet, for the countdown and for pre-warm.",
                "היכל התרבות",
                eventDate,
                now.plus(Duration.ofHours(2)),
                saleEnd);
        // Deliberately NO counter. POST /api/v1/admin/events/{id}/prewarm creates it, and that
        // endpoint refuses any window but UPCOMING — so this event is the only place on the dev
        // profile where ADR-004's seeding path can be exercised at all.
        tier(upcoming, "General Admission", 3_000, 200, 4);

        log.info(
                "Seeded dev catalog: event {} OPEN with 700 seats, event {} OPEN with 200 seats,"
                        + " event {} UPCOMING and un-warmed",
                live.getId(),
                secondLive.getId(),
                upcoming.getId());
        return counters;
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

    private StockSeed seedTier(
            Event event, String name, long priceCents, int capacity, int maxPerOrder) {
        TicketTier tier = tier(event, name, priceCents, capacity, maxPerOrder);
        return new StockSeed(event.getId(), tier.getId(), capacity);
    }
}
