package com.flashseats.hold.facade;

import com.flashseats.hold.model.SettleReason;
import com.flashseats.hold.model.TicketHold;
import com.flashseats.hold.service.HoldService;
import com.flashseats.hold.service.HoldTimers;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link HoldService}, plus entity-to-record mapping. Package-private. */
@Component
class HoldFacadeImpl implements HoldFacade {

    private final HoldService holds;
    private final HoldTimers timers;

    HoldFacadeImpl(HoldService holds, HoldTimers timers) {
        this.holds = holds;
        this.timers = timers;
    }

    @Override
    public HoldSummary getActiveHold(String holdToken, String userSessionId) {
        return toSummary(holds.requireActiveHold(holdToken, userSessionId));
    }

    @Override
    public Optional<HoldSummary> findActiveHold(String userSessionId, long eventId) {
        return holds.findActiveHold(userSessionId, eventId).map(HoldFacadeImpl::toSummary);
    }

    @Override
    public HoldSummary consumeHold(String holdToken) {
        return toSummary(holds.consume(holdToken));
    }

    @Override
    public void releaseHold(String holdToken, HoldReleaseReason reason) {
        holds.releaseInternal(holdToken, toSettleReason(reason));
    }

    @Override
    public Instant grantGrace(String holdToken) {
        return holds.grantGrace(holdToken);
    }

    @Override
    public void discardTimer(String holdToken) {
        // A consumed hold is the one ending that does NOT publish TicketHoldSettledEvent — there are
        // no seats to give back — so this is the only thing that clears its timer. Skipping it would
        // still be correct: the key would expire on its own and the listener would find the hold
        // already CONSUMED and do nothing. It is swept up here so the key does not sit around
        // announcing an expiry nobody needs.
        timers.disarm(holdToken);
    }

    @Override
    public int sumActiveQuantityForTier(long tierId) {
        return holds.sumActiveQuantityForTier(tierId);
    }

    /** The public reason, mapped onto the internal one the ledger stores. */
    private static SettleReason toSettleReason(HoldReleaseReason reason) {
        return switch (reason) {
            case USER_CANCEL -> SettleReason.USER_CANCEL;
            case ORDER_ABORT -> SettleReason.ORDER_ABORT;
        };
    }

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
}
