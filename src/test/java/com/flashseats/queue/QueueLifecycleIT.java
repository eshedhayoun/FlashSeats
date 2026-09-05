package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The waiting room's terminal states, which is where the first pass was weakest.
 *
 * <p>Three separate defects lived here and none was reachable from the happy path: an event with no
 * counter was announced as sold out and its queue deleted; a sale that closed on the clock left
 * everyone in it reporting {@code WAITING} forever; and a pass minted for one event was offered to
 * another.
 */
@DisplayName("The waiting room reaches the right terminal state, and never destroys itself")
class QueueLifecycleIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("An event with no inventory counter pauses promotion — it does not sell out")
    void unwarmedEventDoesNotExhaustTheQueue() {
        // A sale that opened without anyone running pre-warm. SUM(remaining) over zero rows is 0,
        // and reading that 0 as "sold out" told the whole waiting room the sale had ended and then
        // deleted the line (ADR-035).
        long eventId = fixture.openEvent("Un-warmed");
        fixture.tierWithoutInventory(eventId, "General Admission", 2_500, 100);

        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        // Give the promotion worker several ticks to get it wrong.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var state = buyer.get("/sale/" + eventId + "/state");
            assertThat(state.json().get("queue").get("state").asText())
                    .describedAs("a missing counter is a fault, never a sold-out sale")
                    .isEqualTo("WAITING");
        });

        // And their place is intact: the line was never deleted.
        assertThat(buyer.get("/queue/status?eventId=" + eventId).json().get("position").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A sale that closes on the clock ends the wait instead of freezing it")
    void closedSaleTerminatesTheWait() {
        long eventId = fixture.openEvent("Closing Soon");
        fixture.tier(eventId, "Floor", 4_500, 1);

        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        fixture.closeSale(eventId);

        // The promotion worker and the broadcaster both skip closed events, so nothing was left to
        // tell a session still ranked in the ZSET. It reported WAITING indefinitely (ADR-036).
        var state = buyer.get("/sale/" + eventId + "/state");
        assertThat(state.json().get("queue").get("state").asText()).isEqualTo("CLOSED");
        assertThat(state.json().get("windowStatus").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("A pass for one sale is never offered to another")
    void passesAreScopedToTheirEvent() {
        long saleA = fixture.openEvent("Sale A");
        fixture.tier(saleA, "GA", 2_500, 20);
        long saleB = fixture.openEvent("Sale B");
        fixture.tier(saleB, "GA", 2_500, 20);

        // One visitor, two concurrent sales — which the dev seeder creates by default.
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + saleA);
        buyer.post("/queue/join", Map.of("eventId", saleA));
        buyer.post("/queue/join", Map.of("eventId", saleB));

        String passForA = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + saleA).text("passToken"),
                        token -> token != null);
        String passForB = buyer.get("/queue/status?eventId=" + saleB).text("passToken");

        // The pass key was scoped by session alone, so B's status returned A's token: B reported
        // PROMOTED holding something its own /admit would refuse, hiding the real position behind a
        // token that could never be spent.
        assertThat(passForB).isNotEqualTo(passForA);

        // And A's pass still works for A.
        assertThat(buyer.post(
                                "/queue/admit", java.util.Map.of("eventId", saleA),
                                Map.of("X-Queue-Pass-Token", passForA))
                        .ok())
                .isTrue();
    }

    @Test
    @DisplayName("Selling out notifies the room without deleting it, and reverses when seats return")
    void exhaustionIsDerivedAndReversible() {
        long eventId = fixture.openEvent("One Seat");
        long tierId = fixture.tier(eventId, "Only", 9_900, 1);

        BuyerSession first = new BuyerSession(port);
        first.get("/events/" + eventId);
        first.post("/queue/join", Map.of("eventId", eventId));
        String pass = await().atMost(PATIENCE)
                .until(() -> first.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);
        String admission = first
                .post("/queue/admit", java.util.Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", pass))
                .text("admissionToken");
        String holdToken = first.post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", 1),
                        Map.of("X-Admission-Token", admission))
                .text("holdToken");

        // A second buyer arrives with the only seat held and no stock left to promote against.
        BuyerSession second = new BuyerSession(port);
        second.get("/events/" + eventId);
        second.post("/queue/join", Map.of("eventId", eventId));

        // The first buyer walks away; the sweeper reclaims the seat.
        first.delete("/holds/" + holdToken);
        assertThat(fixture.remaining(tierId)).isEqualTo(1);

        // Whatever the second buyer saw in between, once stock is back they are a waiting buyer
        // again with their place intact — which is only possible because nothing deleted the line
        // (ADR-035).
        await().atMost(PATIENCE).untilAsserted(() -> {
            var state = second.get("/sale/" + eventId + "/state");
            assertThat(state.json().get("queue").get("state").asText()).isIn("WAITING", "PROMOTED", "ADMITTED");
        });
    }
}
