package com.flashseats.queue.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The browse window ran out.
 *
 * <p>A low-stakes expiry, and the copy should say so: it costs the buyer their place in line, not
 * money. Nothing was reserved and nothing was charged.
 */
public class AdmissionExpiredException extends FlashSeatsException {

    public AdmissionExpiredException() {
        super(ErrorCode.ADMISSION_EXPIRED, "Your session ended. Rejoin the queue to try again.");
    }
}
