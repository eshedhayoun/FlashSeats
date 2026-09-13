package com.flashseats.queue.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * Everything {@code queue} can refuse, in one place.
 *
 * <p>These were three classes, one per message, none of them ever caught by type.
 *
 * <p>Public because {@code hold} raises {@link #admissionRequired} and {@link #admissionExpired}:
 * admission is queue's concept, and a caller inventing its own refusal for it would put the rule in
 * two modules.
 */
public final class QueueErrors {

    private QueueErrors() {}

    /** No admission session was presented. The caller has not been let into the sale. */
    public static FlashSeatsException admissionRequired() {
        return new FlashSeatsException(ErrorCode.ADMISSION_REQUIRED, "Join the queue to enter this sale.");
    }

    /**
     * The browse window ran out.
     *
     * <p>A low-stakes expiry, and the copy should say so: it costs the buyer their place in line, not
     * money. Nothing was reserved and nothing was charged.
     */
    public static FlashSeatsException admissionExpired() {
        return new FlashSeatsException(
                ErrorCode.ADMISSION_EXPIRED, "Your session ended. Rejoin the queue to try again.");
    }

    /** The pass failed verification, or had already been spent. */
    public static FlashSeatsException queuePassInvalid() {
        return new FlashSeatsException(
                ErrorCode.QUEUE_PASS_INVALID, "This entry pass is not valid. Please rejoin the queue.");
    }
}
