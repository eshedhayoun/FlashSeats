package com.flashseats.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Pre-warm is how a counter comes to exist, and the window check is what stops it resurrecting sold
 * tickets (ADR-004).
 *
 * <p>Both halves matter. Without the first, an open sale has no counters and every hold is a
 * {@code 503}; without the second, running pre-warm on a live sale would restore the entire
 * inventory from {@code total_capacity} — the highest-severity defect the design review found.
 */
@DisplayName("Pre-warm seeds the live counter, and only before the sale opens")
class CatalogPrewarmIT extends IntegrationTest {

    private static final Map<String, String> ADMIN = Map.of(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(UTF_8)));

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("An upcoming tier gains a counter holding its full capacity")
    void prewarmSeedsTheCounter() {
        long eventId = fixture.upcomingEvent("Midnight Sessions");
        long tierId = fixture.tierWithoutInventory(eventId, "General Admission", 3_000, 200);

        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(-1);

        BuyerSession.Response response = prewarm(eventId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.number("tiersSeeded")).isEqualTo(1);
        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(200);
    }

    @Test
    @DisplayName("Running it twice seeds nothing the second time")
    void prewarmIsIdempotent() {
        long eventId = fixture.upcomingEvent("Midnight Sessions");
        long tierId = fixture.tierWithoutInventory(eventId, "General Admission", 3_000, 200);

        prewarm(eventId);
        BuyerSession.Response second = prewarm(eventId);

        assertThat(second.number("tiersSeeded"))
                .describedAs("a repeated pre-warm must be a no-op, not a reseed")
                .isZero();
        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(200);
    }

    @Test
    @DisplayName("An open sale is refused, and gains no counter")
    void prewarmRefusesAnOpenSale() {
        // The counter is deliberately absent, so a successful reseed would be visible. On a real
        // open sale it would not be: it would silently put every sold ticket back on the shelf.
        long eventId = fixture.openEvent("Aurora Fest");
        long tierId = fixture.tierWithoutInventory(eventId, "VIP", 7_500, 50);

        BuyerSession.Response response = prewarm(eventId);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo("PREWARM_WINDOW_CLOSED");
        assertThat(fixture.stockCounter(eventId, tierId))
                .describedAs("a refused pre-warm must leave Redis untouched")
                .isEqualTo(-1);
    }

    private BuyerSession.Response prewarm(long eventId) {
        return new BuyerSession(port).post("/admin/events/" + eventId + "/prewarm", null, ADMIN);
    }
}
