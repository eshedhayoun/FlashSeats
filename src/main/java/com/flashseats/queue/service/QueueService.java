package com.flashseats.queue.service;

import com.flashseats.catalog.exception.SaleNotOpenException;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.config.QueueProperties;
import com.flashseats.queue.dto.AdmitResponse;
import com.flashseats.queue.dto.QueueStatusResponse;
import com.flashseats.queue.exception.QueuePassInvalidException;
import com.flashseats.queue.facade.QueuePhase;
import com.flashseats.queue.facade.QueueState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Joining, position, and the pass-for-admission exchange. */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

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
     */
    public QueueStatusResponse join(String sessionId, long eventId) {
        EventSummary event = catalog.getEventSummary(eventId);
        if (event.windowStatus() != EventWindowStatus.OPEN) {
            throw new SaleNotOpenException(eventId, event.windowStatus());
        }

        redis.opsForZSet()
                .addIfAbsent(
                        QueueKeys.waiting(eventId),
                        sessionId,
                        (double) clock.instant().toEpochMilli());
        expireWithSale(QueueKeys.waiting(eventId), event.saleEndTime());
        touchHeartbeat(sessionId);

        return status(sessionId, eventId, event.windowStatus());
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
        touchHeartbeat(sessionId);
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

    public QueueState getQueueState(String sessionId, long eventId) {
        return getQueueState(sessionId, eventId, catalog.getWindowStatus(eventId));
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
        if (window == EventWindowStatus.CLOSED) {
            return new QueueState(QueuePhase.CLOSED, null, null, null, null);
        }

        String admissionToken = redis.opsForValue().get(QueueKeys.admission(eventId, sessionId));
        if (admissionToken != null) {
            Long ttl = redis.getExpire(QueueKeys.admission(eventId, sessionId));
            Instant expiresAt = ttl != null && ttl > 0 ? clock.instant().plusSeconds(ttl) : null;
            return new QueueState(QueuePhase.ADMITTED, null, null, expiresAt, null);
        }

        String passToken = redis.opsForValue().get(QueueKeys.pass(eventId, sessionId));
        if (passToken != null) {
            return new QueueState(QueuePhase.PROMOTED, null, null, null, passToken);
        }

        if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.exhausted(eventId)))) {
            return new QueueState(QueuePhase.EXHAUSTED, null, null, null, null);
        }

        Long rank = redis.opsForZSet().rank(QueueKeys.waiting(eventId), sessionId);
        if (rank != null) {
            int position = rank.intValue() + 1;
            OptionalDouble estimate = drainRate.estimateSeconds(eventId, position);
            Integer estWaitSeconds = estimate.isPresent() ? (int) Math.ceil(estimate.getAsDouble()) : null;
            return new QueueState(QueuePhase.WAITING, position, estWaitSeconds, null, null);
        }

        return QueueState.notJoined();
    }

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
            throw new QueuePassInvalidException();
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
     */
    public boolean hasLiveAdmission(String admissionToken, String sessionId, long eventId) {
        if (!tokens.isValidAdmission(admissionToken, eventId, sessionId)) {
            return false;
        }
        String stored = redis.opsForValue().get(QueueKeys.admission(eventId, sessionId));
        return stored != null && stored.equals(admissionToken);
    }

    /** Called once an order is confirmed: the buyer has what they came for. */
    public void revokeAdmission(String sessionId, long eventId) {
        redis.delete(QueueKeys.admission(eventId, sessionId));
        redis.opsForZSet().remove(QueueKeys.admissions(eventId), sessionId);
    }

    // ---------------------------------------------------------------- internal

    /**
     * Refreshes the liveness marker. Advisory only — it feeds an abandonment metric and is never
     * consulted before promoting or removing anyone (ADR-026).
     */
    void touchHeartbeat(String sessionId) {
        redis.opsForValue()
                .set(
                        QueueKeys.heartbeat(sessionId),
                        "1",
                        Duration.ofSeconds(properties.getHeartbeatTtlSeconds()));
    }
}
