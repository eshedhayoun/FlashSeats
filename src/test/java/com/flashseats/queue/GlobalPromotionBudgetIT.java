package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.queue.config.QueueProperties;
import com.flashseats.queue.service.GlobalPromotionBudget;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    private long originalPromotionIntervalMs;
    private int originalGlobalAdmissionConnectionBudget;
    private int originalDatabaseConnectionsPerBuyer;

    @BeforeEach
    void reset() {
        fixture.reset();
        originalPromotionIntervalMs = properties.getPromotionIntervalMs();
        originalGlobalAdmissionConnectionBudget = properties.getGlobalAdmissionConnectionBudget();
        originalDatabaseConnectionsPerBuyer = properties.getDatabaseConnectionsPerBuyer();

        properties.setPromotionIntervalMs(60_000);
        properties.setGlobalAdmissionConnectionBudget(10);
        properties.setDatabaseConnectionsPerBuyer(2);
    }

    @AfterEach
    void restoreProperties() {
        properties.setPromotionIntervalMs(originalPromotionIntervalMs);
        properties.setGlobalAdmissionConnectionBudget(originalGlobalAdmissionConnectionBudget);
        properties.setDatabaseConnectionsPerBuyer(originalDatabaseConnectionsPerBuyer);
    }

    @Test
    @DisplayName("Every caller spends from the same per-tick Redis allowance")
    void claimsShareOnePerTickAllowance() {
        assertThat(budget.claim(4)).isEqualTo(4);
        assertThat(budget.claim(4)).isEqualTo(1);
        assertThat(budget.claim(1)).isZero();

        Set<String> keys = redis.keys("queue:promote:budget:*");
        assertThat(keys).hasSize(1);
        assertThat(redis.opsForValue().get(keys.iterator().next())).isEqualTo("5");
    }

    @Test
    @DisplayName("A caller can never claim more than one global tick budget")
    void singleClaimIsCappedAtTheTickBudget() {
        assertThat(budget.claim(99)).isEqualTo(5);
        assertThat(budget.claim(1)).isZero();
    }
}
