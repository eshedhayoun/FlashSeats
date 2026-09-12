package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.queue.config.QueueProperties;
import com.flashseats.queue.service.GlobalPromotionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

@DisplayName("The promotion budget is global across open sales")
class GlobalPromotionBudgetIT extends IntegrationTest {

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private GlobalPromotionBudget budget;

    @Autowired
    private QueueProperties properties;

    @Autowired
    private StringRedisTemplate redis;

    @LocalServerPort
    private int port;

    private long originalPromotionIntervalMs;
    private int originalGlobalAdmissionConnectionBudget;
    private int originalDatabaseConnectionsPerBuyer;

    @BeforeEach
    void reset() {
        fixture.reset();
        originalPromotionIntervalMs = properties.getPromotionIntervalMs();
        originalGlobalAdmissionConnectionBudget = properties.getGlobalAdmissionConnectionBudget();
        originalDatabaseConnectionsPerBuyer = properties.getDatabaseConnectionsPerBuyer();

        properties.setGlobalAdmissionConnectionBudget(10);
        properties.setDatabaseConnectionsPerBuyer(2);
    }

    @AfterEach
    void restoreProperties() {
        properties.setPromotionIntervalMs(originalPromotionIntervalMs);
        properties.setGlobalAdmissionConnectionBudget(originalGlobalAdmissionConnectionBudget);
        properties.setDatabaseConnectionsPerBuyer(originalDatabaseConnectionsPerBuyer);
        redis.delete("queue:budget");
    }

    @Test
    @DisplayName("Every caller spends from the same allowance, whichever sale it is promoting")
    void claimsShareOneAllowance() {
        properties.setPromotionIntervalMs(60_000);

        assertThat(budget.claim(4)).isEqualTo(4);
        assertThat(budget.claim(4)).isEqualTo(1);
        assertThat(budget.claim(1)).isZero();

        // One key, not one per sale: that is the whole of ADR-049.
        assertThat(redis.keys("queue:budget*")).containsExactly("queue:budget");
        assertThat(redis.opsForValue().get("queue:budget")).isEqualTo("5");
    }

    @Test
    @DisplayName("A single caller cannot claim more than one window's allowance")
    void oneClaimIsCappedAtTheWindow() {
        properties.setPromotionIntervalMs(60_000);

        assertThat(budget.claim(99)).isEqualTo(5);
        assertThat(budget.claim(1)).isZero();
    }

    /**
     * The window is anchored by the key's own TTL rather than by a clock-derived bucket, so replicas
     * do not have to agree about the time for the allowance to be one allowance.
     */
    @Test
    @DisplayName("The allowance comes back when the window expires")
    void theWindowRefills() {
        properties.setPromotionIntervalMs(200);

        assertThat(budget.claim(5)).isEqualTo(5);
        assertThat(budget.claim(1)).isZero();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(budget.claim(1)).isOne());
    }

    /**
     * The defect this replaces: the worker iterated open events in a fixed ascending order on every
     * replica, so the lowest event id claimed the whole allowance every tick and every other sale
     * stood still. Two sales, enough waiting buyers in each to exhaust the budget on its own, and
     * both must move.
     */
    @Test
    @DisplayName("No open sale is starved by another")
    void everySaleGetsPromoted() {
        long firstSale = fixture.openEvent("Budget Fest A");
        fixture.tier(firstSale, "GA", 2_500, 100);
        long secondSale = fixture.openEvent("Budget Fest B");
        fixture.tier(secondSale, "GA", 2_500, 100);

        List<BuyerSession> waiting = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            waiting.add(join(firstSale));
            waiting.add(join(secondSale));
        }

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(promotedCount(firstSale)).isPositive();
                    assertThat(promotedCount(secondSale)).isPositive();
                });

        assertThat(waiting).hasSize(24);
    }

    private BuyerSession join(long eventId) {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));
        return buyer;
    }

    private long promotedCount(long eventId) {
        Long live = redis.opsForZSet().zCard("queue:passes:" + eventId);
        return live == null ? 0 : live;
    }
}
