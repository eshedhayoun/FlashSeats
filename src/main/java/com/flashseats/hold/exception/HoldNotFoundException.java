package com.flashseats.hold.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * No such hold, or it is not this session's.
 *
 * <p>Both cases return {@code 404} deliberately: a {@code 403} for "exists but not yours" would let
 * anyone enumerate valid hold tokens.
 *
 * <p><strong>A class rather than a {@link HoldErrors} factory, because it is caught by type.</strong>
 * {@code PaymentSettlementService} catches it together with {@link HoldExpiredException} and
 * {@link HoldAlreadySettledException} as the three ways a webhook settlement can find the seats gone,
 * and refunds. That set is the reason all three are types: a settlement arriving for a reservation
 * that no longer exists must end in the buyer's money coming back, and a `catch` is how that is
 * spelled.
 *
 * <p>It briefly was a factory. Nothing caught it at the time, and within the week the webhook
 * receiver did — which is the rule working rather than failing, but also a reminder that "nothing
 * catches this" is a statement about today.
 */
public class HoldNotFoundException extends FlashSeatsException {

    public HoldNotFoundException(String holdToken) {
        super(ErrorCode.HOLD_NOT_FOUND, "No reservation found for this session.");
    }
}
