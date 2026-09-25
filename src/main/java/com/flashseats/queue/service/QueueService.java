package com.flashseats.queue.service;

import com.flashseats.catalog.exception.CatalogErrors;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.config.QueueOrdering;
import com.flashseats.queue.config.QueueProperties;
import com.flashseats.queue.dto.AdmitResponse;
import com.flashseats.queue.dto.QueueStatusResponse;
import com.flashseats.queue.exception.QueueErrors;
import com.flashseats.queue.facade.QueueFacade;
import com.flashseats.queue.facade.QueuePhase;
import com.flashseats.queue.facade.QueueState;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Joining, position, and the pass-for-admission exchange.
 *
 * <p>This class <em>is</em> {@link QueueFacade}. Other modules see only that interface, because this
 * package is internal to the module and they may not name it. There is no separate delegating
 * implementation: one existed, held a single null check, and only added a hop between the contract
 * and the code that honours it.
 */
@Slf4j
@Service
public class QueueService implements QueueFacade {

    private final StringRedisTemplate redis;
    private final CatalogFacade catalog;
    private final QueueTokens tokens;
    private final QueueDrainRateTracker drainRate;
    private final QueueProperties properties;
    private final Clock clock;

    public QueueService(
            StringRedisTemplate redis,
            CatalogFacade catalog,
            QueueTokens tokens,
            QueueDrainRateTracker drainRate,
            QueueProperties properties,
            Clock clock) {
        this.redis = redis;
        this.catalog = catalog;
        this.tokens = tokens;
        this.drainRate = drainRate;
        this.properties = properties;
        this.clock = clock;
    }

    // -------------------------------------------------------------------- join

    /**
     * Puts a session in line.
     *
     * <p><strong>{@code ZADD NX}, never a plain {@code ZADD}.</strong> A plain add <em>updates</em>
     * an existing member's score, so a page refresh or a double-click on "Join" would reset the
     * arrival time and send the buyer to the <em>back</em> of the line — the exact opposite of the
     * fairness the queue exists to provide (ADR-008). Rejoining is therefore idempotent, and that is
     * a feature.
     *
     * <p><strong>{@code ZADD NX} is also what makes a random draw safe</strong> (ADR-024). A fresh
     * draw is taken on every attempt and the second one is simply discarded, so rejoining keeps the
     * place the first join won. Deriving the score from the session id instead would be idempotent
     * too — and grindable: session ids cost nothing to mint, so a bot would generate candidates
     * offline until it held a low draw and walk to the front deterministically, which is the exact
     * advantage a random draw exists to remove.
     */
    public QueueStatusResponse join(String sessionId, long eventId) {
        EventSummary event = catalog.getEventSummary(eventId);
        if (event.windowStatus() != EventWindowStatus.OPEN) {
            throw CatalogErrors.saleNotOpen(eventId, event.windowStatus());
        }

        redis.opsForZSet()
                .addIfAbsent(
                        QueueKeys.waiting(eventId),
                        sessionId,
                        queueScore());
        expireWithSale(QueueKeys.waiting(eventId), event.saleEndTime());

        return status(sessionId, eventId, event.windowStatus());
    }

    /**
     * The ZSET score, which is the ordering (ADR-024).
     *
     * <p>{@code FIFO} is arrival epoch-millis: intuitive, explicable, and decided by whoever has the
     * lowest network latency. {@code RANDOM} is a draw bounded at 2^53 so it stays exactly
     * representable as the double a ZSET score is — anything larger would collide after rounding and
     * hand two buyers the same position.
     */
    private double queueScore() {
        if (properties.getOrdering() == QueueOrdering.RANDOM) {
            return ThreadLocalRandom.current().nextLong(1L << 53);
        }
        return (double) clock.instant().toEpochMilli();
    }

    private void expireWithSale(String key, Instant saleEndTime) {
        QueueKeyLifetimes.expireWithSale(
                redis, key, clock.instant(), saleEndTime, properties.getKeyRetentionAfterSaleSeconds());
    }

    // ------------------------------------------------------------------ status

    public QueueStatusResponse status(String sessionId, long eventId) {
        return status(sessionId, eventId, catalog.getWindowStatus(eventId));
    }

    private QueueStatusResponse status(String sessionId, long eventId, EventWindowStatus window) {
        QueueState state = getQueueState(sessionId, eventId, window);
        Integer position = state.position();

        return new QueueStatusResponse(
                state.phase(),
                position,
                position == null ? null : Math.max(0, position - 1),
                state.estWaitSeconds(),
                state.passToken(),
                state.admissionExpiresAt(),
                clock.instant());
    }

    @Override
    public QueueState getQueueState(String sessionId, long eventId) {
        return getQueueState(sessionId, eventId, catalog.getWindowStatus(eventId));
    }

    boolean isExhausted(long eventId) {
        return Boolean.TRUE.equals(redis.hasKey(QueueKeys.exhausted(eventId)));
    }

    /**
     * Seconds left on this session's promotion pass, or {@code null} when it holds none.
     *
     * <p>Deliberately not folded into the pipelined read behind {@link #getQueueState}. That read
     * serves {@code GET /queue/status}, which is the single largest consumer of the cluster's CPU at
     * roughly 90,000 calls per replica per run; this answer is wanted only when a buyer reconnects
     * already promoted, so it costs one round trip on a rare path rather than a command on the
     * hottest one.
     */
    public Long passTimeToLiveSeconds(String sessionId, long eventId) {
        Long remaining = redis.getExpire(QueueKeys.pass(eventId, sessionId), TimeUnit.SECONDS);
        return remaining == null || remaining < 0 ? null : remaining;
    }

    /**
     * Assembles a session's whole position in the sale.
     *
     * <p><strong>The order of these checks is the state machine</strong> (ADR-036):
     *
     * <ol>
     *   <li><strong>{@code CLOSED} first.</strong> The window outranks everything. Checking it last
     *       meant a buyer still ranked in the ZSET when the sale ended kept reporting
     *       {@code WAITING} forever — and because the promotion worker and the broadcaster both
     *       iterate only <em>open</em> events, nothing was left to tell them otherwise. The waiting
     *       room simply froze.
     *   <li><strong>{@code ADMITTED}, then {@code PROMOTED}.</strong> Most-advanced-first: an
     *       admitted buyer is admitted even if a stale pass is lying around, and a promoted buyer is
     *       promoted even though they have left the ZSET.
     *   <li><strong>{@code EXHAUSTED} before {@code WAITING}.</strong> A buyer holding a pass or an
     *       admission still has a claim worth spending — someone may release seats — but a buyer
     *       with neither, in a sale with no stock, should be told so. They stay in the ZSET, so if
     *       stock returns the marker is cleared and their place is exactly where they left it.
     * </ol>
     *
     * <p>The window arrives as a parameter so a caller iterating many sessions of one event resolves
     * it once rather than once per session.
     */
    QueueState getQueueState(String sessionId, long eventId, EventWindowStatus window) {
        return getQueueState(sessionId, eventId, window, null);
    }

    QueueState getQueueState(String sessionId, long eventId, EventWindowStatus window, Boolean exhausted) {
        // Before the read, not after: the window outranks every other state, so a closed sale costs
        // no Redis at all. Reading first and discarding it would spend a round trip per poll for the
        // hour a finished sale's clients keep polling.
        if (window == EventWindowStatus.CLOSED) {
            return new QueueState(QueuePhase.CLOSED, null, null, null, null);
        }
        return decide(read(sessionId, eventId, exhausted), eventId);
    }

    /**
     * Everything this session's state depends on, in <strong>one</strong> Redis round trip.
     *
     * <p>The reads used to be sequential — admission, its TTL, the pass, the exhausted marker, the
     * rank — and this is the most-called path in the system by two orders of magnitude: a 300-VU
     * five-sale run polls {@code /queue/status} <strong>132,000 times</strong> against 1,700
     * checkouts. Four round trips there is four times the encode, decode and socket work of one, on
     * the request that dominates the cluster's CPU.
     *
     * <p><strong>Reading eagerly does not change the answer.</strong> None of the reads decides what
     * to read next; only {@link #decide} is ordered, and it still applies exactly the state machine
     * its own javadoc describes. The cost is fetching a few values a short-circuit would have
     * skipped, which inside one pipeline is far cheaper than the round trips it removes.
     *
     * @param exhausted pre-resolved by a caller sweeping many sessions of one event — it is per
     *     event, not per session, so the broadcaster resolves it once and this skips it
     */
    private Snapshot read(String sessionId, long eventId, Boolean exhausted) {
        String admissionKey = QueueKeys.admission(eventId, sessionId);
        String passKey = QueueKeys.pass(eventId, sessionId);
        String waitingKey = QueueKeys.waiting(eventId);
        String exhaustedKey = QueueKeys.exhausted(eventId);

        // The byte-level API rather than a StringRedisConnection cast: inside a pipeline the
        // connection is a proxy, and the cast throws ClassCastException at runtime while compiling
        // perfectly. Replies come back through the template's own String serializer.
        List<Object> replies = redis.executePipelined((RedisCallback<Object>) connection -> {
            connection.stringCommands().get(utf8(admissionKey));
            connection.keyCommands().ttl(utf8(admissionKey));
            connection.stringCommands().get(utf8(passKey));
            connection.zSetCommands().zRank(utf8(waitingKey), utf8(sessionId));
            if (exhausted == null) {
                connection.keyCommands().exists(utf8(exhaustedKey));
            }
            return null;
        });

        int expected = exhausted == null ? 5 : 4;
        if (replies.size() != expected) {
            // Positional reads are only safe while the positions are known. Adding a command above
            // without shifting the indices below would otherwise read a neighbour's value and answer
            // confidently with the wrong phase.
            throw new IllegalStateException(
                    "Expected " + expected + " pipelined replies, got " + replies.size());
        }

        return new Snapshot(
                (String) replies.get(0),
                (Long) replies.get(1),
                (String) replies.get(2),
                (Long) replies.get(3),
                exhausted != null ? exhausted : truthy(replies.get(4)));
    }

    private QueueState decide(Snapshot snapshot, long eventId) {
        if (snapshot.admissionToken() != null) {
            Long ttl = snapshot.admissionTtlSeconds();
            Instant expiresAt = ttl != null && ttl > 0 ? clock.instant().plusSeconds(ttl) : null;
            return new QueueState(QueuePhase.ADMITTED, null, null, expiresAt, null);
        }

        if (snapshot.passToken() != null) {
            return new QueueState(QueuePhase.PROMOTED, null, null, null, snapshot.passToken());
        }

        if (snapshot.exhausted()) {
            return new QueueState(QueuePhase.EXHAUSTED, null, null, null, null);
        }

        if (snapshot.rank() != null) {
            int position = snapshot.rank().intValue() + 1;
            OptionalDouble estimate = drainRate.estimateSeconds(eventId, position);
            Integer estWaitSeconds = estimate.isPresent() ? (int) Math.ceil(estimate.getAsDouble()) : null;
            return new QueueState(QueuePhase.WAITING, position, estWaitSeconds, null, null);
        }

        return QueueState.notJoined();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code EXISTS} is a Redis integer; a driver may hand it back as {@code Boolean} or as a number.
     *
     * <p>{@code Boolean.TRUE.equals(1L)} is {@code false}, so taking the narrow view would make an
     * exhausted sale report {@code WAITING} for ever — silently, and on the one path this design is
     * most careful about (ADR-035, ADR-040). Accept either shape rather than depend on which.
     */
    private static boolean truthy(Object reply) {
        if (reply instanceof Boolean value) {
            return value;
        }
        return reply instanceof Number value && value.longValue() > 0;
    }

    /** One session's raw queue state, as Redis answered it. */
    private record Snapshot(
            String admissionToken,
            Long admissionTtlSeconds,
            String passToken,
            Long rank,
            boolean exhausted) {}

    // ------------------------------------------------------------------- admit

    /**
     * Exchanges a pass for an admission session, and <strong>revokes the pass here</strong>.
     *
     * <p>Spending the pass at this moment — rather than at hold creation — is what makes it truly
     * single-use. In an earlier design nothing ever revoked it, so one promoted session could mint
     * unlimited holds and drain a tier by itself (ADR-006, ADR-020).
     */
    public AdmitResponse admit(String sessionId, long eventId, String passToken) {
        String stored = redis.opsForValue().get(QueueKeys.pass(eventId, sessionId));
        if (stored == null
                || !stored.equals(passToken)
                || !tokens.isValidPass(passToken, eventId, sessionId)) {
            throw QueueErrors.queuePassInvalid();
        }

        String admissionToken = tokens.mintAdmission(eventId, sessionId);
        Instant expiresAt = clock.instant().plusSeconds(properties.getAdmissionTtlSeconds());

        redis.opsForValue()
                .set(
                        QueueKeys.admission(eventId, sessionId),
                        admissionToken,
                        Duration.ofSeconds(properties.getAdmissionTtlSeconds()));
        redis.opsForZSet()
                .add(QueueKeys.admissions(eventId), sessionId, (double) expiresAt.toEpochMilli());

        redis.delete(QueueKeys.pass(eventId, sessionId));
        redis.opsForZSet().remove(QueueKeys.passes(eventId), sessionId);

        log.debug("Session {} admitted to event {}", sessionId, eventId);
        return new AdmitResponse(admissionToken, expiresAt, clock.instant());
    }

    // -------------------------------------------------------------- admission

    /**
     * Two checks, both required: the signature proves the token was minted by us for this session and
     * this event; the Redis key proves it has not since expired or been revoked.
     *
     * <p>A missing token is answered here rather than by the caller. {@code hold} used to receive a
     * null and hand it straight back, which meant the one rule this method exists to enforce had a
     * second, silent home in another module.
     */
    @Override
    public boolean verifyAdmission(String admissionToken, String sessionId, long eventId) {
        if (admissionToken == null || !tokens.isValidAdmission(admissionToken, eventId, sessionId)) {
            return false;
        }
        String stored = redis.opsForValue().get(QueueKeys.admission(eventId, sessionId));
        return stored != null && stored.equals(admissionToken);
    }

    /** Called once an order is confirmed: the buyer has what they came for. */
    @Override
    public void revokeAdmission(String sessionId, long eventId) {
        redis.delete(QueueKeys.admission(eventId, sessionId));
        redis.opsForZSet().remove(QueueKeys.admissions(eventId), sessionId);
    }
}
