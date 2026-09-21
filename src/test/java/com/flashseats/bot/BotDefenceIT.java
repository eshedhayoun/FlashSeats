package com.flashseats.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.bot.config.BotProperties;
import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.service.IpRuleService;
import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
/**
 * The defences that run before any business logic does.
 *
 * <p>Both halves of §10 S5. Session identity is free to mint, so the per-session bucket does not
 * constrain a determined attacker; the IP bucket is deliberately loose so carrier-grade NAT
 * populations are not blocked during exactly the spike this system exists to serve. A manual block
 * and a challenge score are what is left.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Bot defence refuses what it should and never closes a sale by itself")
@TestPropertySource(properties = {
        "flashseats.bot.session-bucket.capacity=5",
        "flashseats.bot.session-bucket.refill-per-second=1",
        "flashseats.bot.ip-bucket.capacity=10",
        "flashseats.bot.ip-bucket.refill-per-second=1"
})
class BotDefenceIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private IpRuleService ipRules;

    @Autowired
    private BotProperties botProperties;

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
    @DisplayName("The audit trail records the buyer's address, not the proxy's")
    void auditRecordsTheResolvedClientAddress() {
        // The test profile trusts nobody, so X-Forwarded-For is ignored and the peer address wins.
        // That is the assertion worth making either way: the audit row must carry whatever the RATE
        // LIMITER resolved, because resolving it a second way — getRemoteAddr() — is always the
        // proxy behind nginx, and every row in the deployment that matters would say 172.28.0.10.
        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "address check", null);

        new BuyerSession(port).get("/events/" + eventId, Map.of("X-Forwarded-For", "203.0.113.9"));

        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(fixture.botAuditAddresses()).containsOnly("127.0.0.1"));
    }

    @Test
    @Order(1)
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
    @Test 
    @DisplayName("The session bucket returns 429 when one session sends too many requests") 
    void sessionRateLimitIsEnforced(){
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        assertThat(buyer.cookieCount()).isEqualTo(1);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < botProperties.getSessionBucket().getCapacity() + 1; i++) {
            var response = buyer.get("/events/" + eventId);
            statuses.add(response.status());
        }
        assertThat(statuses).contains(429);
        var refused = buyer.get("/events/" + eventId); 
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(refused.json().get("retryable").asBoolean()).isTrue(); 
        assertThat(refused.json().get("retryAfterSeconds").asInt()).isPositive();
    }
    @Test
    @DisplayName("RATE_LIMITED responses include Retry-After so the client knows when to retry") 
    void rateLimitedResponseContainsRetryAfter(){
        BuyerSession buyer = new BuyerSession(port);
        for (int i = 0; i < botProperties.getSessionBucket().getCapacity() + 1; i++) {
            buyer.get("/events/" + eventId);
        }
        var refused = buyer.get("/events/" + eventId); 
        assertThat(refused.status()).isEqualTo(429); 
        assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED"); 
        int retryAfterSeconds = refused.json().get("retryAfterSeconds").asInt();
        assertThat(retryAfterSeconds).isPositive();
    }
    @Test
    @DisplayName("Many fresh sessions from one address eventually hit the shared IP backstop")
    void ipBackstopThrottlesManySessionsFromOneAddress() throws Exception {
        Long requests = botProperties.getIpBucket().getCapacity() * 4;

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();

            for (int i = 0; i < requests; i++) {
                tasks.add(() -> {
                    start.await();
                    BuyerSession buyer = new BuyerSession(port);
                    return buyer.get("/events/" + eventId).status();
                });
            }

            var futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (var future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).contains(429);
        }

        var refused = new BuyerSession(port).get("/events/" + eventId);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(refused.json().get("retryAfterSeconds").asInt()).isPositive();
    }
    @Test 
    @DisplayName("Sharing an address does not make two legitimate sessions immediately fail together") 
    void separateSessionsSharingIpGetIndependentSessionBuckets(){
        BuyerSession first = new BuyerSession(port); 
        BuyerSession second = new BuyerSession(port);
        for (int i = 0; i < 5; i++) {
            assertThat(first.get("/events/" + eventId).status()).isEqualTo(200); 
        }
        for (int i = 0; i < 5; i++) {
            assertThat(second.get("/events/" + eventId).status()).isEqualTo(200); 
        }
    }
    @Test
    @DisplayName("An ALLOW rule bypasses only the IP bucket, not the session bucket") 
    void allowRuleDoesNotDisableSessionRateLimit(){
        ipRules.upsert("127.0.0.1", IpRuleAction.ALLOW, "shared egress", null); 
        BuyerSession buyer = new BuyerSession(port); 
        List<Integer> statuses = new ArrayList<>();
        for(int i=0; i<25; i++){
            var response = buyer.get("/events/" + eventId);
            statuses.add(response.status());
        }
        assertThat(statuses).contains(429);
    }
    @Test 
    @DisplayName("X-Forwarded-For is ignored when the connecting peer is not trusted") 
    void forwardedAddressIsIgnoredFromUntrustedPeer(){
        ipRules.upsert("127.0.0.1", IpRuleAction.DENY, "real client address", null); 
        BuyerSession buyer = new BuyerSession(port);
         // A caller cannot bypass the rule by inventing a different address in X-Forwarded-For. 
        var refused = buyer.get( "/events/" + eventId, Map.of("X-Forwarded-For", "203.0.113.55"));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("IP_BLOCKED");
    }
    @Test
    @DisplayName("Bot operator endpoints require admin authentication")
    void botAdminEndpointsRequireAdminAuthentication() {
        BuyerSession anonymous = new BuyerSession(port);
        var refused = anonymous.get("/admin/bot/ip-rules");
        assertThat(refused.status()).isEqualTo(401);
        BuyerSession admin = new BuyerSession(port);
        var allowed = admin.get("/admin/bot/ip-rules",BuyerSession.basicAuth("admin", "admin"));
        assertThat(allowed.status()).isEqualTo(200);
    }
}
