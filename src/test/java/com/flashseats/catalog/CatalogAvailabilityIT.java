package com.flashseats.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the public browse read says about inventory it cannot see (ADR-040).
 *
 * <p>ADR-004 and ADR-035 both say the same thing in different places: a missing counter is a fault,
 * never a sold-out tier. {@code HoldService} honoured it and {@code PromotionWorker} was taught to,
 * but the landing page — the one surface every visitor sees, before anything else — clamped the
 * fault code to zero and published {@code SOLD_OUT}.
 */
@DisplayName("Availability distinguishes 'nothing left' from 'nothing known'")
class CatalogAvailabilityIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("A tier with no counter reads UNKNOWN, never SOLD_OUT")
    void unwarmedTierIsUnknownNotSoldOut() {
        long eventId = fixture.upcomingEvent("Midnight Sessions");
        fixture.tierWithoutInventory(eventId, "General Admission", 3_000, 200);

        String availability = new BuyerSession(port)
                .get("/events/" + eventId)
                .json()
                .get("tiers")
                .get(0)
                .get("availability")
                .asText();

        assertThat(availability)
                .describedAs(
                        "an un-warmed tier announced itself sold out to every visitor, on a sale "
                                + "that had not even opened yet")
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("A tier that genuinely sold out still reads SOLD_OUT")
    void exhaustedTierIsStillSoldOut() {
        // The fix must not blunt the real signal: zero remaining is a fact, and the buyer needs it.
        long eventId = fixture.openEvent("Aurora Fest");
        long tierId = fixture.tier(eventId, "VIP", 7_500, 1);

        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        fixture.drainTier(tierId);

        assertThat(buyer.get("/events/" + eventId).json().get("tiers").get(0).get("availability").asText())
                .isEqualTo("SOLD_OUT");
    }

    @Test
    @DisplayName("A stocked tier reads as a bucket, never as a count")
    void stockedTierReportsABucket() {
        long eventId = fixture.openEvent("Aurora Fest");
        fixture.tier(eventId, "General Admission", 2_500, 500);

        assertThat(new BuyerSession(port)
                        .get("/events/" + eventId)
                        .json()
                        .get("tiers")
                        .get(0)
                        .get("availability")
                        .asText())
                .isEqualTo("PLENTY");
    }
}
