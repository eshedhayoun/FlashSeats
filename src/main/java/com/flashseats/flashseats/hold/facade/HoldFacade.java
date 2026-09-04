package com.flashseats.flashseats.hold.facade;

import com.flashseats.flashseats.hold.dto.internal.HoldSummaryDTO;
import com.flashseats.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.flashseats.hold.exception.HoldNotFoundException;

public interface HoldFacade {
    /**
     * Atomically validates and consumes an active hold during checkout.
     * Marks the hold status as CONSUMED, cancels the Redis TTL timer,
     * and returns the reservation details.
     *
     * @param holdToken Cryptographic hold UUID string
     * @return HoldSummaryDTO Details of the consumed hold
     * @throws HoldNotFoundException if holdToken does not exist
     * @throws HoldExpiredException if hold is past TTL or already consumed
     */
    HoldSummaryDTO validateAndConsumeHold(String holdToken);

    /**
     * Manually releases a hold and restores stock immediately.
     * Used by payment/order modules upon transaction rejection.
     *
     * @param holdToken Cryptographic hold UUID string
     * @param reason Internal release reason (e.g., PAYMENT_FAILED, USER_CANCELED)
     */
    void releaseHold(String holdToken, String reason);

    /**
     * Verifies whether a valid active hold exists for a given user session.
     *
     * @param holdToken Cryptographic hold UUID string
     * @param userSessionId Unique guest session ID
     * @return true if hold is ACTIVE and unexpired, false otherwise
     */
    boolean isHoldActiveForSession(String holdToken, String userSessionId);
}
