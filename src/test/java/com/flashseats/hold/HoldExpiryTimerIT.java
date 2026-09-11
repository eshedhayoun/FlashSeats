package com.flashseats.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.service.HoldService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code hold:{token}} timer, and the rule that makes it safe: <strong>it accelerates expiry, it
 * never decides it</strong>.
 *
 * <p>The listener itself — one broadcast expiry reaching three replicas and restoring seats exactly
 * once — is proven against the real cluster by {@code docker/scripts/hold-expiry-check.sh}, because
 * that claim is about replicas and a single JVM cannot make it. What is proven here is everything
 * the listener delegates to, which is where the damage would actually be done:
 * {@link HoldService#reclaimExpired} is idempotent, refuses to settle a hold that is still alive,
 * and returns seats once no matter how many callers arrive.
 */
@DisplayName("The hold expiry timer accelerates expiry without ever deciding it")
class HoldExpiryTimerIT extends IntegrationTest {

    private static final int CAPACITY = 20;

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private HoldService holds;

    @Autowired
    private HoldFacade holdFacade;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TransactionTemplate transactions;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Timer Test");
        tierId = fixture.tier(eventId, "Floor", 4_500, CAPACITY);
    }

    @Test
    @DisplayName("Creating a hold arms a timer that expires with it")
    void holdArmsItsTimer() {
        String holdToken = newHold(2);

        // Armed after the commit, so it may lag the response by a moment.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redis.hasKey(timerKey(holdToken))).isTrue());

        Long ttl = redis.getExpire(timerKey(holdToken));
        assertThat(ttl).isNotNull().isPositive();
        // The TTL tracks the hold's own expiry, never a fixed constant — grantGrace moves it.
        assertThat(ttl).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("Consuming a hold discards its timer, so nothing announces an expiry nobody needs")
    void consumingAHoldDisarmsItsTimer() {
        String holdToken = newHold(2);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redis.hasKey(timerKey(holdToken))).isTrue());

        transactions.executeWithoutResult(tx -> holdFacade.consumeHold(holdToken));
        holdFacade.discardTimer(holdToken);

        assertThat(redis.hasKey(timerKey(holdToken))).isFalse();
    }

    @Test
    @DisplayName("A timer that fires while its hold is still alive re-arms instead of settling it")
    void anEarlyTimerNeverSettlesALiveHold() {
        String holdToken = newHold(3);
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 3);

        // Exactly the grantGrace case: PostgreSQL says the hold runs until later, but the key is
        // gone. Treating the key as authoritative here would hand back seats a buyer is being
        // charged for — the reason this method re-reads the row instead of trusting the event.
        redis.delete(timerKey(holdToken));

        assertThat(holds.reclaimExpired(holdToken)).isFalse();

        assertThat(fixture.holdStatus(holdToken)).isEqualTo("ACTIVE");
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 3);
        // ...and the hold keeps its fast path rather than silently falling back to the sweeper.
        assertThat(redis.hasKey(timerKey(holdToken))).isTrue();
    }

    @Test
    @DisplayName("Three replicas receiving one broadcast expiry restore the seats once, not three times")
    void aBroadcastExpiryRestoresExactlyOnce() {
        String holdToken = newHold(4);
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY - 4);
        fixture.expireHold(holdToken);

        // Keyspace expiry is pub/sub: every replica gets the event and every replica calls this.
        // Only the settle-once claim stops the seats being returned once per replica — which is how
        // a naive listener triples a tier's inventory.
        boolean first = holds.reclaimExpired(holdToken);
        boolean second = holds.reclaimExpired(holdToken);
        boolean third = holds.reclaimExpired(holdToken);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(third).isFalse();

        assertThat(fixture.holdStatus(holdToken)).isEqualTo("EXPIRED");
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
        assertThat(fixture.stockInvariantHolds(tierId)).isTrue();
    }

    @Test
    @DisplayName("An expiry for a hold that never existed is ignored, not an error")
    void anUnknownTokenIsHarmless() {
        assertThat(holds.reclaimExpired("hold-that-never-was")).isFalse();
        assertThat(fixture.remaining(tierId)).isEqualTo(CAPACITY);
    }

    private static String timerKey(String holdToken) {
        return "hold:" + holdToken;
    }

    /** The full ADR-020 path: browse, queue, wait for the pass, exchange it, then reserve. */
    private String newHold(int quantity) {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(Duration.ofSeconds(15))
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        String admissionToken = buyer
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");

        return buyer
                .post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken))
                .text("holdToken");
    }
}
