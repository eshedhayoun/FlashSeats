package com.flashseats.hold.service;

import com.flashseats.catalog.exception.CatalogErrors;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.config.HoldProperties;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldErrors;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.exception.HoldNotFoundException;
import com.flashseats.hold.exception.InsufficientStockException;
import com.flashseats.hold.exception.InventoryUnavailableException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import com.flashseats.hold.model.TicketHold;
import com.flashseats.hold.repository.TicketHoldRepository;
import com.flashseats.queue.exception.QueueErrors;
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
 * The hold lifecycle: everything that creates, ends or extends a reservation. Two rules:
 *
 * <ol>
 *   <li>A reservation and the stock movement that justifies it are ordered and compensated together.
 *   <li>A hold leaves {@code ACTIVE} only via the settle-once claim, and only its winner touches stock.
 * </ol>
 *
 * <p>This class implements {@link HoldFacade}; those methods are grouped at the bottom and return
 * {@link HoldSummary} records, while the ones above return the entity for this module's own use.
 */
@Slf4j
@Service
public class HoldService implements HoldFacade {

    /** The partial unique index from {@code V2__hold.sql} that caps a session at one live hold. */
    private static final String ONE_ACTIVE_HOLD_INDEX = "idx_holds_one_active_per_session";

    private final TicketHoldRepository holds;
    private final CatalogFacade catalog;
    private final QueueFacade queue;
    private final HoldProperties properties;
    private final ApplicationEventPublisher events;
    private final HoldTimers timers;
    private final Clock clock;

    public HoldService(
            TicketHoldRepository holds,
            CatalogFacade catalog,
            QueueFacade queue,
            HoldProperties properties,
            ApplicationEventPublisher events,
            HoldTimers timers,
            Clock clock) {
        this.holds = holds;
        this.catalog = catalog;
        this.queue = queue;
        this.properties = properties;
        this.events = events;
        this.timers = timers;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * Reserves seats. The Redis decrement is <strong>outside any transaction</strong>, because Redis
     * cannot roll back (ADR-023, ADR-046). It is ordered first and compensated only on a constraint
     * rejection, the one certain failure. An ambiguous commit failure is left to drift and a rebuild,
     * since returning seats that may still be held would oversell (invariant 12).
     *
     * <p>"Not enough seats" ({@code 409}) and "no counter" ({@code 503}) stay distinct; the second is
     * never sold out (ADR-004).
     */
    public TicketHold createHold(
            String sessionId, long eventId, long tierId, int quantity, String admissionToken) {

        requireAdmission(sessionId, eventId, admissionToken);

        TierSummary tier = catalog.getTierSummary(eventId, tierId);

        if (tier.windowStatus() != EventWindowStatus.OPEN) {
            throw CatalogErrors.saleNotOpen(eventId, tier.windowStatus());
        }
        int maxQuantity = Math.min(properties.getMaxQuantity(), tier.maxPerOrder());
        if (quantity < 1 || quantity > maxQuantity) {
            throw HoldErrors.quantityExceedsLimit(quantity, maxQuantity);
        }

        switch (catalog.tryReserve(eventId, tierId, quantity)) {
            case COUNTER_MISSING -> throw new InventoryUnavailableException(tierId);
            case INSUFFICIENT -> throw new InsufficientStockException(tierId, quantity);
            case RESERVED -> {
                /* carry on: the seats are ours until the row below says otherwise */
            }
        }

        TicketHold hold = new TicketHold(
                HoldTokens.generate(),
                sessionId,
                eventId,
                tierId,
                quantity,
                clock.instant().plusSeconds(properties.getTtlSeconds()));
        try {
            // Flushed here, not at commit, so the constraint speaks while we can still translate it
            // — and while its verdict is still unambiguous enough to compensate on.
            holds.saveAndFlush(hold);
        } catch (DataIntegrityViolationException violation) {
            catalog.restore(eventId, tierId, quantity);
            if (!isOneActiveHoldPerSession(violation)) {
                // Some other constraint on this table. Reporting it as "you already hold seats"
                // would answer a question the buyer never asked and hide a real schema problem —
                // which is exactly what the quantity CHECK did before V6 relaxed it.
                throw violation;
            }
            throw HoldErrors.holdLimitExceeded(eventId);
        }

        events.publishEvent(new TicketHeldEvent(
                hold.getHoldToken(),
                sessionId,
                eventId,
                tierId,
                quantity,
                hold.getExpiresAt(),
                clock.instant()));
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

    // ----------------------------------------------------------------- release

    /** Buyer-initiated cancellation. Their place in the sale is unaffected (ADR-020). */
    @Transactional
    public void release(String holdToken, String sessionId) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .filter(h -> h.getUserSessionId().equals(sessionId))
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        settleAndRestore(hold, HoldStatus.RELEASED, SettleReason.USER_CANCEL);
    }

    // ------------------------------------------------------------------ extend

    /**
     * Grants the one grace extension, if unused (ADR-030). The budget is <strong>per hold, not per
     * attempt</strong>: a retry gets the current expiry back, because one per attempt would make
     * declines a way to squat on seats.
     *
     * @throws HoldExpiredException if the hold is no longer {@code ACTIVE}. The caller
     *     <strong>must</strong> abort before charging (ADR-023).
     */
    @Override
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
     * Reclaims holds whose window has passed. This, not the expiry listener, is what makes expiry
     * <em>correct</em>, because keyspace events are at-most-once. Safe on every replica: each row is
     * taken by the settle-once claim.
     *
     * @return how many holds this replica reclaimed
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

    /**
     * Reclaims one hold whose {@code hold:{token}} timer fired. <strong>The timer is a hint, never an
     * authority</strong> (ADR-048). The row is re-read and only a genuinely expired hold is reclaimed,
     * because {@code grantGrace} moves expiry in PostgreSQL, and AOF, eviction or a flush can all
     * disagree with the row. A hold that is still alive gets its timer re-armed. Exactly one replica
     * wins via the settle-once claim.
     *
     * @return true if this replica won the claim and the seats are coming back
     */
    @Transactional
    public boolean reclaimExpired(String holdToken) {
        TicketHold hold = holds.findByHoldToken(holdToken).orElse(null);
        if (hold == null || !hold.isActive()) {
            // Already consumed, released or swept. The ordinary outcome on two of three replicas.
            return false;
        }
        if (hold.getExpiresAt().isAfter(clock.instant())) {
            timers.arm(holdToken, hold.getExpiresAt());
            log.debug("Timer for hold {} fired early; re-armed to {}", holdToken, hold.getExpiresAt());
            return false;
        }
        return settleAndRestore(hold, HoldStatus.EXPIRED, SettleReason.TTL);
    }

    // ----------------------------------------------------------- reconciliation

    /**
     * Seats currently held on a tier, for the stock invariant and the rebuild.
     *
     * <p>Includes holds already past their expiry: they are still {@code ACTIVE}, they still own
     * their seats, and the sweeper will hand those seats back. Excluding them would let a rebuild
     * and the sweeper each account for the same seats.
     */
    @Override
    @Transactional(readOnly = true)
    public int sumActiveQuantityForTier(long tierId) {
        return holds.sumActiveQuantityForTier(tierId);
    }

    // ------------------------------------------------- HoldFacade: the contract
    //
    // Everything below is what other modules may call. The methods above return the JPA entity and
    // are this module's own; these return records, because handing an entity across a boundary
    // leaks a lazy-loading proxy and a persistence mapping into a module that must know neither
    // (global standards §5).

    /**
     * {@inheritDoc}
     *
     * <p>Annotated even though {@link #requireActiveHold} is: this calls it on {@code this}, and
     * Spring's proxy does not intercept self-invocation, so without it the read runs with no transaction.
     */
    @Override
    @Transactional(readOnly = true)
    public HoldSummary getActiveHold(String holdToken, String userSessionId) {
        return toSummary(requireActiveHold(holdToken, userSessionId));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Optional<HoldSummary> findActiveHold(String userSessionId, long eventId) {
        return holds.findByUserSessionIdAndEventIdAndStatus(userSessionId, eventId, HoldStatus.ACTIVE)
                .filter(hold -> clock.instant().isBefore(hold.getExpiresAt()))
                .map(HoldService::toSummary);
    }

    /**
     * Marks the hold as sold, inside the caller's transaction ({@link Propagation#MANDATORY}). Stock is
     * not restored: the seats were bought. If the caller rolls back, so does this claim (ADR-019).
     *
     * @throws HoldAlreadySettledException when the claim is lost: the seats are gone, so the caller
     *     must abort and refund
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HoldSummary consumeHold(String holdToken) {
        TicketHold hold = holds.findByHoldToken(holdToken)
                .orElseThrow(() -> new HoldNotFoundException(holdToken));

        if (holds.settle(holdToken, HoldStatus.CONSUMED, SettleReason.CONSUMED, clock.instant()) != 1) {
            throw new HoldAlreadySettledException(holdToken);
        }
        return toSummary(hold);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A consumed hold is the one ending that does <em>not</em> publish
     * {@code TicketHoldSettledEvent} — there are no seats to give back — so this is the only thing
     * that clears its timer.
     */
    @Override
    public void discardTimer(String holdToken) {
        timers.disarm(holdToken);
    }

    // ----------------------------------------------------------------- helpers

    /** The one place a hold entity becomes the record other modules see. */
    private static HoldSummary toSummary(TicketHold hold) {
        return new HoldSummary(
                hold.getHoldToken(),
                hold.getUserSessionId(),
                hold.getEventId(),
                hold.getTierId(),
                hold.getQuantity(),
                hold.getExpiresAt(),
                hold.getCreatedAt());
    }

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
            throw QueueErrors.admissionRequired();
        }
        if (!queue.verifyAdmission(admissionToken, sessionId, eventId)) {
            // Signature was fine but the session is gone, or the token was never ours. Either way the
            // buyer's route back is the same: rejoin the queue.
            throw QueueErrors.admissionExpired();
        }
    }

    /**
     * Runs the settle-once claim and, if this caller won, arranges for the seats to come back. Every
     * ending funnels through here, so "restored exactly once" holds by construction. The restore runs
     * after commit ({@link HoldPostCommitTasks}); inline, one failure in a sweep batch would return seats
     * for holds that roll back to {@code ACTIVE} (ADR-046).
     */
    private boolean settleAndRestore(TicketHold hold, HoldStatus status, SettleReason reason) {
        boolean won = holds.settle(hold.getHoldToken(), status, reason, clock.instant()) == 1;
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
