package com.flashseats.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.service.IpRuleService;
import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The defences that run before any business logic does.
 *
 * <p>Both halves of §10 S5. Session identity is free to mint, so the per-session bucket does not
 * constrain a determined attacker; the IP bucket is deliberately loose so carrier-grade NAT
 * populations are not blocked during exactly the spike this system exists to serve. A manual block
 * and a challenge score are what is left.
 */
@DisplayName("Bot defence refuses what it should and never closes a sale by itself")
class BotDefenceIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private IpRuleService ipRules;

    private long eventId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        ipRules.all().forEach(rule -> ipRules.remove(rule.getIpAddress()));
        eventId = fixture.openEvent("Defence Fest");
        fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("Join works with no challenge provider configured — verification fails OPEN")
    void joinSucceedsWithNoProvider() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);

        // No secret is set in the test profile, and no token is sent. Both are the "we cannot
        // verify" case, and the documented behaviour is to let the buyer in (ADR-011). A sale that
        // closed because a third party was unreachable would be the worse failure by far.
        var joined = buyer.post("/queue/join", Map.of("eventId", eventId));

        assertThat(joined.status()).isEqualTo(202);
        // A real place in the line, not merely a 202 that swallowed the request.
        assertThat(joined.text("phase")).isNotNull();
    }

    @Test
    @DisplayName("A blocked address is refused with IP_BLOCKED, before anything else runs")
    void deniedAddressIsRefused() {
        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "integration test", null);

        BuyerSession blocked = new BuyerSession(port);
        var refused = blocked.get("/events/" + eventId);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("IP_BLOCKED");
        // Nothing to gain from retrying a standing operator decision.
        assertThat(refused.json().get("retryable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Removing a rule lets the address back in, without a restart")
    void removingARuleTakesEffect() {
        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "integration test", null);
        assertThat(new BuyerSession(port).get("/events/" + eventId).status()).isEqualTo(403);

        ipRules.remove("127.0.0.1");

        // The replica that served the change drops its snapshot immediately; the others follow when
        // theirs expires. There is no restart in that sentence, which is the point — the previous
        // answer to an address flooding a sale was to change a property and restart three replicas,
        // during the sale.
        assertThat(new BuyerSession(port).get("/events/" + eventId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("An expired rule stops applying at its expiry, not at the cache's")
    void expiredRulesStopApplying() {
        // Already lapsed when written. The snapshot still holds it, so if expiry were evaluated at
        // load time rather than at read time this would refuse — a rule outliving its own deadline
        // for as long as the TTL runs (ADR-051's "never cache a value derived from the clock").
        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "already over", Instant.now().minusSeconds(60));

        assertThat(new BuyerSession(port).get("/events/" + eventId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("A refusal is written to the audit trail, and a success is not")
    void onlyRefusalsAreAudited() {
        new BuyerSession(port).get("/events/" + eventId);

        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "audit check", null);
        new BuyerSession(port).get("/events/" + eventId);

        // Asynchronous and best-effort: the request path must never wait on evidence.
        await().atMost(PATIENCE).untilAsserted(() -> {
            var entries = fixture.botAuditOutcomes();
            assertThat(entries).containsOnly("IP_BLOCKED");
        });
    }
}
