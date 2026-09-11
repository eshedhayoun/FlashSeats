package com.flashseats.order;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.order.service.StockReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The recovery ADR-004 names, and the alarm that tells anyone to run it.
 *
 * <p>Every "fails toward under-counting, detected and repaired" claim in the Redis design rests on
 * these two pieces. Without the gauge nobody learns a counter has drifted; without the rebuild the
 * documented recovery from the system's worst failure is editing the database by hand.
 */
@DisplayName("A lost or wrong counter is rebuilt from the ledger, and drift is visible")
class StockRebuildIT extends IntegrationTest {

    private static final int CAPACITY = 20;
    private static final int HELD = 3;

    private static final Map<String, String> ADMIN = Map.of(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(UTF_8)));

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private StockReconciliationService reconciliation;

    @Autowired
    private MeterRegistry meters;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Rebuild Test");
        tierId = fixture.tier(eventId, "Floor", 4_500, CAPACITY);
    }

    @Test
    @DisplayName("A counter that never existed is rebuilt from capacity minus what is sold and held")
    void rebuildCreatesAMissingCounter() {
        holdSeats();
        fixture.loseStockCounter(eventId, tierId);

        assertThat(fixture.stockCounter(eventId, tierId))
                .describedAs("the fault being recovered from: no counter at all")
                .isEqualTo(-1);

        rebuild();

        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(CAPACITY - HELD);
    }

    @Test
    @DisplayName("A counter left too high by a Redis restart is corrected down")
    void rebuildCorrectsAnOvercount() {
        holdSeats();
        // What an AOF replay looks like: the DECRBY was lost, the hold row was not.
        fixture.setStockCounter(eventId, tierId, CAPACITY);

        rebuild();

        assertThat(fixture.stockCounter(eventId, tierId))
                .describedAs("seats that are held must not also be on sale")
                .isEqualTo(CAPACITY - HELD);
    }

    @Test
    @DisplayName("Seats lost to an under-count come back")
    void rebuildRecoversAnUndercount() {
        // The case the whole design leans on. Every Redis mutation is ordered to fail toward
        // under-counting rather than overbooking, which is only an acceptable trade because those
        // seats are recoverable. This is where that is proven.
        holdSeats();
        fixture.setStockCounter(eventId, tierId, 1);

        rebuild();

        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(CAPACITY - HELD);
        assertThat(fixture.ledgerRemaining(tierId))
                .describedAs("the ledger's last-known-good copy is refreshed too")
                .isEqualTo(CAPACITY - HELD);
    }

    @Test
    @DisplayName("Drift reads zero when the counter agrees with the ledger")
    void driftIsZeroWhenTheyAgree() {
        holdSeats();
        rebuild();

        reconciliation.measureDrift();

        assertThat(gauge("flashseats.stock.drift")).isZero();
        assertThat(gauge("flashseats.stock.counters.missing")).isZero();
    }

    @Test
    @DisplayName("Drift reports the gap, and a missing counter is counted separately")
    void driftReportsDisagreement() {
        holdSeats();
        fixture.setStockCounter(eventId, tierId, CAPACITY);

        reconciliation.measureDrift();

        assertThat(gauge("flashseats.stock.drift"))
                .describedAs("three seats are held but the counter still offers them")
                .isEqualTo(HELD);

        // A missing counter is a different, louder fault: it cannot be expressed as a number of
        // seats, so folding it into the drift gauge would report zero for a total loss.
        fixture.reset();
        long freshEvent = fixture.openEvent("Unwarmed");
        fixture.tierWithoutInventory(freshEvent, "Floor", 4_500, CAPACITY);
        reconciliation.measureDrift();

        assertThat(gauge("flashseats.stock.counters.missing")).isEqualTo(1);
        assertThat(gauge("flashseats.stock.drift")).isZero();
    }

    private int gauge(String name) {
        return (int) meters.get(name).gauge().value();
    }

    private void rebuild() {
        BuyerSession.Response response =
                new BuyerSession(port).post("/admin/events/" + eventId + "/rebuild-stock", null, ADMIN);
        assertThat(response.status()).isEqualTo(200);
    }

    /** Takes {@value #HELD} seats through the real journey, so the ledger holds a genuine hold. */
    private void holdSeats() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(Duration.ofSeconds(15))
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        String admissionToken = buyer.post(
                        "/queue/admit",
                        Map.of("eventId", eventId),
                        Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");

        BuyerSession.Response hold = buyer.post(
                "/holds",
                Map.of("eventId", eventId, "tierId", tierId, "quantity", HELD),
                Map.of("X-Admission-Token", admissionToken));
        assertThat(hold.status()).isEqualTo(201);
    }
}
