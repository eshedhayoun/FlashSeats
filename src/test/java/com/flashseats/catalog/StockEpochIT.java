package com.flashseats.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.ReserveResult;
import com.flashseats.catalog.service.StockEpoch;
import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The one way counters can go wrong that no ordering of operations can prevent.
 *
 * <p>Every other failure in this design is arranged to lose seats rather than duplicate them. A
 * Redis restart is the exception: AOF is {@code appendfsync everysec}, so the server comes back
 * having forgotten about a second of decrements while PostgreSQL still holds every hold row that
 * paid for them. The counters read <strong>high</strong>, and seats are sold twice.
 *
 * <p>The damage happens inside Redis, so it cannot be ordered away — it can only be noticed. These
 * tests are about noticing it and refusing to sell until someone repairs it.
 */
@DisplayName("A Redis restart stops the sale rather than overselling it")
class StockEpochIT extends IntegrationTest {

    private static final int CAPACITY = 10;

    private static final Map<String, String> ADMIN = Map.of(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(UTF_8)));

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private CatalogFacade catalog;

    @Autowired
    private StockEpoch epoch;

    private final List<Long> createdEvents = new ArrayList<>();

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        createdEvents.clear();
        eventId = openEvent("Epoch Test");
        tierId = fixture.tier(eventId, "Floor", 4_500, CAPACITY);
        // reset() flushed the key the guard vouches with, so re-establish a healthy baseline.
        epoch.check();
    }

    /**
     * Hands every event back trusted.
     *
     * <p>Distrust outlives the database: it is held in a bean shared by the whole run, while
     * {@code reset()} restarts the identity sequence — so the next class's "event 1" is this
     * class's, and would inherit a verdict about counters that no longer exist. Uses the same
     * {@code trust} a rebuild calls rather than a test-only hatch.
     */
    @AfterEach
    void releaseDistrust() {
        createdEvents.forEach(epoch::trust);
    }

    private long openEvent(String title) {
        long id = fixture.openEvent(title);
        createdEvents.add(id);
        return id;
    }

    @Test
    @DisplayName("Before anything goes wrong, selling works normally")
    void aHealthyServerSells() {
        assertThat(catalog.tryReserve(eventId, tierId, 1)).isEqualTo(ReserveResult.RESERVED);
    }

    @Test
    @DisplayName("A restarted server stops the sale, and says so as a fault rather than a sell-out")
    void aRestartRefusesToSell() {
        fixture.forgeEarlierRedisInstance();
        epoch.check();

        assertThat(catalog.tryReserve(eventId, tierId, 1))
                .describedAs(
                        "these counters may have rolled back; selling from them oversells, and"
                                + " INSUFFICIENT would tell buyers the sale had ended instead")
                .isEqualTo(ReserveResult.COUNTER_MISSING);
        assertThat(fixture.stockCounter(eventId, tierId))
                .describedAs("nothing is destroyed: the counter is refused, not deleted")
                .isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("Rebuilding the event brings that sale back, and only that sale")
    void rebuildRestoresSelling() {
        long otherEvent = openEvent("Untouched");
        long otherTier = fixture.tier(otherEvent, "Floor", 4_500, CAPACITY);

        fixture.forgeEarlierRedisInstance();
        epoch.check();
        assertThat(catalog.tryReserve(eventId, tierId, 1)).isEqualTo(ReserveResult.COUNTER_MISSING);

        BuyerSession.Response rebuilt =
                new BuyerSession(port).post("/admin/events/" + eventId + "/rebuild-stock", null, ADMIN);
        assertThat(rebuilt.status()).isEqualTo(200);

        assertThat(catalog.tryReserve(eventId, tierId, 1)).isEqualTo(ReserveResult.RESERVED);
        assertThat(catalog.tryReserve(otherEvent, otherTier, 1))
                .describedAs("repairing one sale must not vouch for another that is still doubtful")
                .isEqualTo(ReserveResult.COUNTER_MISSING);
    }

    @Test
    @DisplayName("A sale created after the restart is never in doubt")
    void aLaterSaleIsUnaffected() {
        fixture.forgeEarlierRedisInstance();
        epoch.check();

        // Its counters were written after the restart, so there is nothing they could have lost.
        long laterEvent = openEvent("Opened Afterwards");
        long laterTier = fixture.tier(laterEvent, "Floor", 4_500, CAPACITY);

        assertThat(catalog.tryReserve(laterEvent, laterTier, 1)).isEqualTo(ReserveResult.RESERVED);
    }
}
