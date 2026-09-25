package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.queue.service.QueueChannelMessage;
import com.flashseats.queue.service.QueueKeys;
import com.flashseats.queue.service.QueueReplayService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * What a reconnecting client is handed, and what is never written down to hand it.
 *
 * <p>The replay log shipped with no test at all, and both of the defects it shipped with were the
 * kind a test would have caught on the first run (ADR-058).
 */
@DisplayName("A reconnect is replayed from broadcasts, never from a capability")
class QueueReplayIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private QueueReplayService replay;

    @Autowired
    private StringRedisTemplate redis;

    private long eventId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Replay Test");
        fixture.tier(eventId, "Floor", 4_500, 10);
    }

    @Test
    @DisplayName("A promotion is fanned out but never retained — the log holds no pass token")
    void promotionIsNeverWrittenToTheReplayLog() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        // The capability exists, and it lives in exactly one place: its own 120 s key.
        assertThat(passToken).isNotBlank();

        var retained = redis.opsForZSet().range(QueueKeys.replay(eventId), 0, -1);
        assertThat(retained == null ? "" : String.join("\n", retained))
                .describedAs(
                        "the replay log is one key per EVENT and outlives the pass by hours; a "
                                + "single-use capability filed there is readable long after it is spent")
                .doesNotContain(passToken)
                .doesNotContain("passToken");
    }

    @Test
    @DisplayName("Broadcast frames are retained in order and replayed after a Last-Event-ID")
    void broadcastsAreReplayedFromTheSequence() {
        replay.publishAndFanOut(
                eventId, QueueChannelMessage.toAll("tier-availability", Map.of("tiers", "first")));
        replay.publishAndFanOut(
                eventId, QueueChannelMessage.toAll("tier-availability", Map.of("tiers", "second")));

        var all = replay.after(eventId, "0");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isLessThan(all.get(1).id());

        // Everything after the first frame is exactly the second.
        var missed = replay.after(eventId, String.valueOf(all.get(0).id()));
        assertThat(missed).hasSize(1);
        assertThat(missed.get(0).data()).containsEntry("tiers", "second");

        // And a client that is already current is handed nothing.
        assertThat(replay.after(eventId, String.valueOf(all.get(1).id()))).isEmpty();
    }

    @Test
    @DisplayName("A session-targeted frame is delivered without consuming a sequence")
    void sessionFramesDoNotAdvanceTheSequence() {
        replay.publishAndFanOut(
                eventId, QueueChannelMessage.toAll("tier-availability", Map.of("tiers", "first")));

        Long afterBroadcast =
                Long.valueOf(redis.opsForValue().get(QueueKeys.replaySequence(eventId)));

        replay.publishAndFanOut(
                eventId, QueueChannelMessage.promotion("some-session", "a-pass-token", 120));

        assertThat(Long.valueOf(redis.opsForValue().get(QueueKeys.replaySequence(eventId))))
                .describedAs(
                        "an unretained frame must not burn a sequence number, or a reconnect asks "
                                + "for frames that were never written and is told it is current")
                .isEqualTo(afterBroadcast);
    }
}
