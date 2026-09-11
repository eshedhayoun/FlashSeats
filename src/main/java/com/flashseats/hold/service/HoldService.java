package com.flashseats.hold.service;

import com.flashseats.catalog.exception.SaleNotOpenException;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.config.HoldProperties;
import com.flashseats.hold.event.TicketHeldEvent;
import com.flashseats.hold.event.TicketHoldSettledEvent;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.exception.HoldLimitExceededException;
import com.flashseats.hold.exception.HoldNotFoundException;
import com.flashseats.hold.exception.InsufficientStockException;
import com.flashseats.hold.exception.InventoryUnavailableException;
import com.flashseats.hold.exception.QuantityExceedsLimitException;
import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import com.flashseats.hold.model.TicketHold;
import com.flashseats.hold.repository.TicketHoldRepository;
import com.flashseats.queue.exception.AdmissionExpiredException;
import com.flashseats.queue.exception.AdmissionRequiredException;
import com.flashseats.queue.facade.QueueFacade;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hold lifecycle. Everything that can create, end or extend a reservation lives here.
 *
 * <p>Two rules govern every method below:
 *
 * <ol>
 *   <li>A reservation and the stock movement that justifies it are <strong>one transaction</strong>.
 *   <li>A hold leaves {@code ACTIVE} only via the settle-once claim, and only the winner of that
 *       claim touches stock.
 * </ol>
 */
@Slf4j
@Service
public class HoldService {

    /** The partial unique index from {@code V2__hold.sql} that caps a session at one live hold. */
    private static final String ONE_ACTIVE_HOLD_INDEX = "idx_holds_one_active_per_session";

    private final TicketHoldRepository holds;
    private final CatalogFacade catalog;
    private final QueueFacade queue;
    private final HoldProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HoldService(
            TicketHoldRepository holds,
            CatalogFacade catalog,
            QueueFacade queue,
            HoldProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.holds = holds;
        this.catalog = catalog;
        this.queue = queue;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * Reserves seats.
     *
     * <p><strong>The stock decrement and the hold row are one transaction, deliberately.</strong> If
     * the insert is rejected — most often by {@code idx_holds_one_active_per_session}, when a buyer
     * already holds seats for this event — the decrement rolls back with it. Splitting them would
     * leak the seats of every rejected attempt.
     *
     * <p>Note the two distinct failures when the reserve does not succeed. "Not enough seats" is a
     * {@code 409} that means try another tier; "no counter at all" is a {@code 503} fault. Treating
     * the second as the first would announce a sold-out sale to every buyer because a row was missing
     * (ADR-004).
     */
    @Transactional
    public TicketHold createHold(
            String sessionId, long eventId, long tierId, int quantity, String admissionToken) {

        requireAdmission(sessionId, eventId, admissionToken);

        TierSummary tier = catalog.getTierSummary(eventId, tierId);

        if (tier.windowStatus() != EventWindowStatus.OPEN) {
            throw new SaleNotOpenException(eventId, tier.windowStatus());
        }
        int maxQuantity = Math.min(properties.getMaxQuantity(), tier.maxPerOrder());
        if (quantity < 1 || quantity > maxQuantity) {
            throw new QuantityExceedsLimitException(quantity, maxQuantity);
        }

        if (!catalog.tryReserve(tierId, quantity)) {
            if (catalog.getRemaining(tierId) == CatalogFacade.COUNTER_UNAVAILABLE) {
                log.error("Inventory counter missing for tier {} during an open sale", tierId);
                throw new InventoryUnavailableException(tierId);
            }
            throw new InsufficientStockException(tierId, quantity);
        }

        TicketHold hold = new TicketHold(
                HoldTokens.generate(),
                sessionId,
                eventId,
                tierId,
                quantity,
                clock.instant().plusSeconds(properties.getTtlSeconds()));
        try {
            // Flushed here, not at commit, so the constraint speaks while we can still translate it.
            holds.saveAndFlush(hold);
        } catch (DataIntegrityViolationException violation) {
            if (!isOneActiveHoldPerSession(violation)) {
                // Some other constraint on this table. Reporting it as "you already hold seats"
                // would answer a question the buyer never asked and hide a real schema problem —
                // which is exactly what the quantity CHECK did before V6 relaxed it.
                throw violation;
            }
            throw new HoldLimitExceededException(eventId);
        }

        events.publishEvent(new TicketHeldEvent(
                hold.getHoldToken(), sessionId, eventId, tierId, quantity, clock.instant()));
        return hold;
    }

    // -------------------------------------------------------------------- read

    /**
     * The hold, if it is live and belongs to this session.
     *
     * @throws HoldNotFoundException if it does not exist or is another session's — {@code 404} for
     *     both, so hold tokens cannot be enumerated
     * @throws HoldExpiredException if it has been settled or its window has passed
     */
    @Transactional(readOnly = true)
    public TicketHold requireActiveHold(String holdToken, String sessionId) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .filter(h -> h.getUserSessionId().equals(sessionId))
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        if (!hold.isActive() || !clock.instant().isBefore(hold.getExpiresAt())) {
            throw new HoldExpiredException(holdToken, hold.getExpiresAt());
        }
        return hold;
    }

    @Transactional(readOnly = true)
    public Optional<TicketHold> findActiveHold(String sessionId, long eventId) {
        return holds.findByUserSessionIdAndEventIdAndStatus(sessionId, eventId, HoldStatus.ACTIVE)
                .filter(hold -> clock.instant().isBefore(hold.getExpiresAt()));
    }

    // ----------------------------------------------------------------- consume

    /**
     * Marks the hold as sold. <strong>Runs inside the caller's transaction and rolls back with
     * it</strong> ({@link Propagation#MANDATORY} enforces that rather than assuming it).
     *
     * <p>Stock is deliberately <em>not</em> restored: the seats were bought. If the caller's
     * transaction later rolls back, this claim rolls back too, the hold returns to {@code ACTIVE},
     * and it expires normally — which is exactly why the claim lives in SQL and not in Redis
     * (ADR-019).
     *
     * @throws HoldAlreadySettledException when the claim is lost, meaning the seats are no longer
     *     ours and the caller must abort and refund
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketHold consume(String holdToken) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        if (holds.settle(holdToken, HoldStatus.CONSUMED, SettleReason.CONSUMED, clock.instant()) != 1) {
            throw new HoldAlreadySettledException(holdToken);
        }
        return hold;
    }

    // ----------------------------------------------------------------- release

    /** Buyer-initiated cancellation. Their place in the sale is unaffected (ADR-020). */
    @Transactional
    public void release(String holdToken, String sessionId) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .filter(h -> h.getUserSessionId().equals(sessionId))
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        settleAndRestore(hold, HoldStatus.RELEASED, SettleReason.USER_CANCEL);
    }

    /** Used by {@code order} to hand seats back when a charge cannot be completed. */
    @Transactional
    public void releaseInternal(String holdToken, SettleReason reason) {
        holds.findByHoldToken(holdToken)
                .ifPresent(hold -> settleAndRestore(hold, HoldStatus.RELEASED, reason));
    }

    // ------------------------------------------------------------------ extend

    /**
     * Grants the one grace extension, if this hold has not already used it (ADR-030).
     *
     * <p>The budget is <strong>per hold, not per attempt</strong>. A retry after a decline finds
     * {@code extendedCount > 0} and simply gets the current expiry back — no error, no second
     * extension. Granting one per attempt would allow 300 + 3×120 = 660 s, blowing the 420 s ceiling
     * and making three deliberate declines a cheap way to squat on inventory.
     *
     * @throws HoldExpiredException if the hold is no longer {@code ACTIVE}. The caller
     *     <strong>must</strong> abort before charging: continuing would take money for seats a
     *     concurrent expiry has already given away (ADR-023).
     */
    @Transactional
    public Instant grantGrace(String holdToken) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        if (!hold.isActive()) {
            throw new HoldExpiredException(holdToken, hold.getExpiresAt());
        }
        if (hold.getExtendedCount() > 0) {
            return hold.getExpiresAt();
        }

        Instant ceiling = hold.getCreatedAt().plusSeconds(properties.getMaxTtlSeconds());
        Instant requested = hold.getExpiresAt().plusSeconds(properties.getGraceSeconds());
        Instant newExpiry = requested.isAfter(ceiling) ? ceiling : requested;

        if (holds.extendOnce(holdToken, newExpiry, clock.instant()) != 1) {
            // Settled between the read and the update — a concurrent expiry won.
            throw new HoldExpiredException(holdToken, hold.getExpiresAt());
        }
        return newExpiry;
    }

    // ------------------------------------------------------------------- sweep

    /**
     * Reclaims holds whose window has passed.
     *
     * <p>This — not any timer or notification — is what makes expiry <em>correct</em>. When Redis
     * arrives it will fire keyspace events that reclaim a hold faster, but those are at-most-once
     * pub/sub: a dropped connection loses one permanently. The sweeper has no such failure mode.
     *
     * <p>Safe on every replica simultaneously, because each row is taken by the settle-once claim.
     *
     * @return how many holds this replica actually reclaimed
     */
    @Transactional
    public int sweepExpired() {
        int reclaimed = 0;
        for (TicketHold hold :
                holds.findExpired(clock.instant(), Limit.of(properties.getSweeperBatchSize()))) {
            if (settleAndRestore(hold, HoldStatus.EXPIRED, SettleReason.SWEEPER)) {
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            log.info("Sweeper reclaimed {} expired hold(s)", reclaimed);
        }
        return reclaimed;
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Whether this violation is the one-live-hold-per-session index, and not some other constraint.
     *
     * <p>Matched on the index name because that is the only thing that identifies <em>which</em>
     * rule was broken. The message is searched as well as the structured constraint name: Hibernate
     * populates the latter for most dialects but not reliably for every wrapped cause, and guessing
     * wrong here means reporting the wrong error to a buyer.
     */
    private boolean isOneActiveHoldPerSession(DataIntegrityViolationException violation) {
        String constraint = violation.getMostSpecificCause().getMessage();
        return constraint != null && constraint.contains(ONE_ACTIVE_HOLD_INDEX);
    }

    /**
     * Requires a live admission session — <strong>not</strong> a queue pass, which was already spent
     * at {@code POST /queue/admit} (ADR-020).
     *
     * <p>The admission session deliberately survives this call. A buyer who releases these seats
     * keeps their place in the sale and can pick a different tier without re-queueing, which is the
     * entire reason the middle tier exists.
     */
    private void requireAdmission(String sessionId, long eventId, String admissionToken) {
        if (admissionToken == null || admissionToken.isBlank()) {
            throw new AdmissionRequiredException();
        }
        if (!queue.verifyAdmission(admissionToken, sessionId, eventId)) {
            // Signature was fine but the session is gone, or the token was never ours. Either way the
            // buyer's route back is the same: rejoin the queue.
            throw new AdmissionExpiredException();
        }
    }

    /**
     * Runs the settle-once claim and, only if this caller won it, returns the seats to stock.
     *
     * <p>Every ending that gives seats back funnels through here, which is what makes "restored
     * exactly once per hold" true by construction rather than by careful call-site discipline.
     */
    private boolean settleAndRestore(TicketHold hold, HoldStatus status, SettleReason reason) {
        boolean won = holds.settle(hold.getHoldToken(), status, reason, clock.instant()) == 1;
        if (won) {
            catalog.restore(hold.getTierId(), hold.getQuantity());
        }
        events.publishEvent(new TicketHoldSettledEvent(
                hold.getHoldToken(),
                hold.getEventId(),
                hold.getTierId(),
                hold.getQuantity(),
                status,
                reason,
                won,
                clock.instant()));
        return won;
    }
}
