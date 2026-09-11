package com.flashseats.queue.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** The pass failed verification, or had already been spent. */
public class QueuePassInvalidException extends FlashSeatsException {

    public QueuePassInvalidException() {
        super(ErrorCode.QUEUE_PASS_INVALID, "This entry pass is not valid. Please rejoin the queue.");
    }
}
