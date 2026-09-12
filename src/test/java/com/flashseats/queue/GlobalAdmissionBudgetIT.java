package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.queue.config.QueueProperties;
import com.flashseats.queue.service.GlobalAdmissionBudget;
import com.flashseats.queue.service.QueueKeys;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

class GlobalAdmissionBudgetIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GlobalAdmissionBudget budget;

    @Autowired
    private QueueProperties properties;

    private int originalBudget;

    /** Clears PostgreSQL and Redis so each budget scenario starts without prior reservations. */
    @BeforeEach
    void reset() {
        fixture.reset();
        originalBudget = properties.getGlobalAdmissionBudget();
        properties.setGlobalAdmissionBudget(2);
    }

    /** Verifies that two concurrent sales draw from one cluster-wide admission budget. */
    @Test
    void promotionsAcrossConcurrentSalesRespectOneGlobalBudget() {
        long saleA = fixture.openEvent("Budget Sale A");
        fixture.tier(saleA, "General", 2_500, 100);
        long saleB = fixture.openEvent("Budget Sale B");
        fixture.tier(saleB, "General", 2_500, 100);

        try {
            for (int i = 0; i < 4; i++) {
                BuyerSession buyerA = new BuyerSession(port);
                buyerA.get("/events/" + saleA);
                buyerA.post("/queue/join", Map.of("eventId", saleA));

                BuyerSession buyerB = new BuyerSession(port);
                buyerB.get("/events/" + saleB);
                buyerB.post("/queue/join", Map.of("eventId", saleB));
            }

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(budget.liveReservations()).isLessThanOrEqualTo(2);
                Long rawCount = redis.opsForZSet().zCard(QueueKeys.globalAdmissionBudget());
                assertThat(rawCount).isLessThanOrEqualTo(2);
            });
        } finally {
            properties.setGlobalAdmissionBudget(originalBudget);
        }
    }
}
