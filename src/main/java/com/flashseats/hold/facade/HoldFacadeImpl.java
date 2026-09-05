package com.flashseats.hold.facade;

import com.flashseats.hold.model.SettleReason;
import com.flashseats.hold.model.TicketHold;
import com.flashseats.hold.service.HoldService;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link HoldService}, plus entity-to-record mapping. Package-private. */
@Component
class HoldFacadeImpl implements HoldFacade {

    private final HoldService holds;

    HoldFacadeImpl(HoldService holds) {
        this.holds = holds;
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
        // No timer to discard while PostgreSQL carries expiry alone. The method exists now so the
        // AFTER_COMMIT call site in `order` is written once and never has to move.
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
