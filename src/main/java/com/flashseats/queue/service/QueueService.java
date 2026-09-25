package com.flashseats.queue.service;

import com.flashseats.bot.facade.BotFacade;
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
    private final BotFacade bots;

    public QueueService(
            StringRedisTemplate redis,
            CatalogFacade catalog,
            QueueTokens tokens,
            QueueDrainRateTracker drainRate,
            QueueProperties properties,
            Clock clock,
            BotFacade bots) {
        this.redis = redis;
        this.catalog = catalog;
        this.tokens = tokens;
        this.drainRate = drainRate;
        this.properties = properties;
        this.clock = clock;
        this.bots = bots;
    }

    // -------------------------------------------------------------------- join

    /**
     * Puts a session in line with {@code ZADD NX}, never a plain {@code ZADD}, which would move a
     * refreshing buyer to the back (ADR-008). {@code NX} also makes a random draw safe (ADR-024): a
     * rejoin's fresh draw is discarded. A score derived from the session id would be grindable, because
     * ids are free to mint.
     */
    public QueueStatusResponse join(
            String sessionId, long eventId, String recaptchaToken, String clientAddress) {
        // The one place a challenge is worth its cost: join is the front of the line, cheap to
        // repeat, and session ids are free to mint (ADR-011). It fails open (ADR-055).
        bots.verifyHuman(sessionId, recaptchaToken, clientAddress);

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
        QueueKeys.expireWithSale(
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
     * Assembles a session's position in the sale. <strong>The order of the checks is the state
     * machine</strong> (ADR-036):
     *
     * <ol>
     *   <li>{@code CLOSED} first: the window outranks everything, or a closed sale's queue waits forever.
     *   <li>{@code ADMITTED}, then {@code PROMOTED}: most advanced first.
     *   <li>{@code EXHAUSTED} before {@code WAITING}: a buyer with no pass or admission in a sale with no
     *       stock is told so, and keeps their place in case stock returns (ADR-035).
     * </ol>
     *
     * <p>The window is a parameter so a caller sweeping many sessions resolves it once.
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
     * Everything this session's state depends on, in <strong>one</strong> Redis round trip; this is the
     * most-called path in the system. Reading eagerly does not change the answer: only {@link #decide}
     * is ordered.
     *
     * @param exhausted pre-resolved by a caller sweeping many sessions of one event, since it is per
     *     event, not per session
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
     * Exchanges a pass for an admission session, and <strong>revokes the pass here</strong>, which is
     * what makes it single-use: one promotion cannot mint unlimited holds (ADR-006, ADR-020).
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
     * Signature plus Redis key: the signature proves we minted it for this session and event, the key
     * proves it is unexpired and unrevoked. A missing token is refused here, the rule's only home.
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
