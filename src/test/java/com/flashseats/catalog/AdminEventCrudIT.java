package com.flashseats.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * An operator creates, lists and deletes sales (ADR-080).
 *
 * <p>What matters is not the CRUD but its edges: a created sale must be sellable the moment it opens
 * (published and pre-warmed), and a delete must never reach a sale a buyer could already be part of.
 */
@DisplayName("Operators create, list and delete sales")
class AdminEventCrudIT extends IntegrationTest {

    private static final Map<String, String> ADMIN = BuyerSession.basicAuth("admin", "admin");

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("A created sale is published, pre-warmed and on the public list")
    void createPublishesAndPrewarms() {
        BuyerSession.Response created = create(validSale());

        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.json();
        long eventId = body.get("eventId").asLong();
        assertThat(body.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(body.get("windowStatus").asString()).isEqualTo("UPCOMING");
        assertThat(body.get("prewarmed").asBoolean()).isTrue();
        assertThat(body.get("deletable").asBoolean()).isTrue();

        JsonNode tiers = body.get("tiers");
        assertThat(tiers).hasSize(2);
        for (JsonNode tier : tiers) {
            assertThat(tier.get("currency").asString()).isEqualTo("USD");
            assertThat(fixture.stockCounter(eventId, tier.get("tierId").asLong()))
                    .isEqualTo(tier.get("totalCapacity").asInt())
                    .isEqualTo(tier.get("remaining").asInt());
        }
        assertThat(fixture.hasVouch(eventId))
                .describedAs("pre-warm vouches, or the restart guard distrusts the sale at open")
                .isTrue();

        BuyerSession.Response publicList = new BuyerSession(port).get("/events");
        assertThat(publicList.rawBody()).contains("\"eventId\":" + eventId);
    }

    @Test
    @DisplayName("Refuses a sale that does not fit together, and stores nothing")
    void createRefusesInvalidSales() {
        Instant now = Instant.now();

        Map<String, Object> noTiers = validSale();
        noTiers.put("tiers", List.of());

        Map<String, Object> tooSoon = validSale();
        tooSoon.put("saleStartTime", now.plus(Duration.ofSeconds(30)).toString());

        Map<String, Object> endsBeforeStart = validSale();
        endsBeforeStart.put("saleEndTime", now.plus(Duration.ofMinutes(5)).toString());

        Map<String, Object> eventBeforeSale = validSale();
        eventBeforeSale.put("eventStartTime", now.plus(Duration.ofMinutes(5)).toString());

        Map<String, Object> zeroCapacity = validSale();
        zeroCapacity.put("tiers", List.of(tier("GA", 1_000, 0)));

        Map<String, Object> noPrice = validSale();
        noPrice.put("tiers", List.of(Map.of("tierName", "GA", "totalCapacity", 50, "maxPerOrder", 4)));

        Map<String, Object> belowStripeMinimum = validSale();
        belowStripeMinimum.put("tiers", List.of(tier("GA", 49, 50)));

        Map<String, Object> pastHoldLimit = validSale();
        pastHoldLimit.put("tiers", List.of(tier("GA", 1_000, 50, 7)));

        Map<String, Object> tooFarAhead = validSale();
        tooFarAhead.put("saleEndTime", now.plus(Duration.ofDays(6 * 365)).toString());

        for (Map<String, Object> invalid : List.of(
                noTiers, tooSoon, endsBeforeStart, eventBeforeSale, zeroCapacity, noPrice,
                belowStripeMinimum, pastHoldLimit, tooFarAhead)) {
            BuyerSession.Response response = create(invalid);
            assertThat(response.status()).describedAs(response.rawBody()).isEqualTo(400);
            assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(fixture.countEvents()).isZero();
    }

    @Test
    @DisplayName("The list shows every status, including ones the public never sees")
    void listShowsEveryStatus() {
        long cancelled = fixture.upcomingEvent("Called Off");
        long tierId = fixture.tier(cancelled, "GA", 1_000, 50);
        fixture.setEventStatus(cancelled, "CANCELLED");
        long open = fixture.openEvent("On Sale");
        fixture.tierWithoutCounter(open, "GA", 1_000, 50);

        JsonNode list = new BuyerSession(port).get("/admin/events", ADMIN).json();

        assertThat(list).hasSize(2);
        JsonNode first = byId(list, cancelled);
        assertThat(first.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(first.get("tiers").get(0).get("tierId").asLong()).isEqualTo(tierId);
        assertThat(first.get("tiers").get(0).get("remaining").asInt()).isEqualTo(50);

        JsonNode second = byId(list, open);
        assertThat(second.get("prewarmed").asBoolean()).isFalse();
        assertThat(second.get("tiers").get(0).get("remaining").isNull())
                .describedAs("no counter is unknown, never zero (ADR-040)")
                .isTrue();
        assertThat(second.get("deletable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A sale vouched for by another Redis instance is not ready until pre-warm runs again")
    void prewarmedMeansItWillSell() {
        long eventId = create(validSale()).json().get("eventId").asLong();
        fixture.forgeEarlierRedisInstance(eventId);

        assertThat(adminView(eventId).get("prewarmed").asBoolean())
                .describedAs("counters that survived a restart are distrusted at open (ADR-046)")
                .isFalse();

        new BuyerSession(port).post("/admin/events/" + eventId + "/prewarm", null, ADMIN);

        assertThat(adminView(eventId).get("prewarmed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Deleting a sale that has not opened removes its rows and its keys")
    void deleteRemovesAnUpcomingSale() {
        JsonNode created = create(validSale()).json();
        long eventId = created.get("eventId").asLong();
        long tierId = created.get("tiers").get(0).get("tierId").asLong();

        BuyerSession.Response deleted = new BuyerSession(port).delete("/admin/events/" + eventId, ADMIN);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(fixture.countEvents()).isZero();
        assertThat(fixture.countTiers(eventId)).isZero();
        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(-1);
        assertThat(fixture.hasVouch(eventId)).isFalse();
        assertThat(new BuyerSession(port).get("/events/" + eventId).status()).isEqualTo(404);
        assertThat(new BuyerSession(port).delete("/admin/events/" + eventId, ADMIN).status())
                .describedAs("a second delete finds nothing")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Refuses to delete a sale that is open or about to open, and changes nothing")
    void deleteRefusesALiveSale() {
        long eventId = fixture.openEvent("On Sale");
        long tierId = fixture.tier(eventId, "GA", 1_000, 50);

        BuyerSession.Response refused = new BuyerSession(port).delete("/admin/events/" + eventId, ADMIN);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("EVENT_NOT_DELETABLE");
        assertThat(fixture.countTiers(eventId))
                .describedAs("the refusal rolls the tier delete back with it")
                .isEqualTo(1);
        assertThat(fixture.stockCounter(eventId, tierId)).isEqualTo(50);
    }

    @Test
    @DisplayName("Every operation needs operator credentials")
    void requiresCredentials() {
        BuyerSession anonymous = new BuyerSession(port);

        assertThat(anonymous.get("/admin/events").errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
        assertThat(anonymous.post("/admin/events", validSale()).errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
        assertThat(anonymous.delete("/admin/events/1").errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
        assertThat(fixture.countEvents()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private BuyerSession.Response create(Map<String, Object> sale) {
        return new BuyerSession(port).post("/admin/events", sale, ADMIN);
    }

    private JsonNode adminView(long eventId) {
        return byId(new BuyerSession(port).get("/admin/events", ADMIN).json(), eventId);
    }

    private static Map<String, Object> validSale() {
        Instant saleStart = Instant.now().plus(Duration.ofMinutes(10));
        Map<String, Object> sale = new HashMap<>();
        sale.put("title", "Midnight Sessions");
        sale.put("description", "An evening of quiet music.");
        sale.put("venueName", "The Roundhouse, London");
        sale.put("eventStartTime", saleStart.plus(Duration.ofDays(7)).toString());
        sale.put("saleStartTime", saleStart.toString());
        sale.put("saleEndTime", saleStart.plus(Duration.ofHours(4)).toString());
        sale.put("tiers", List.of(tier("VIP", 12_000, 20), tier("General Admission", 4_500, 200)));
        return sale;
    }

    private static Map<String, Object> tier(String name, long priceCents, int capacity) {
        return tier(name, priceCents, capacity, 4);
    }

    private static Map<String, Object> tier(String name, long priceCents, int capacity, int maxPerOrder) {
        return Map.of("tierName", name, "priceCents", priceCents, "totalCapacity", capacity, "maxPerOrder", maxPerOrder);
    }

    private static JsonNode byId(JsonNode list, long eventId) {
        for (JsonNode event : list) {
            if (event.get("eventId").asLong() == eventId) {
                return event;
            }
        }
        throw new AssertionError("event " + eventId + " not listed");
    }
}
