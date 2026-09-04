package com.flashseats.queue.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** No admission session was presented. The caller has not been let into the sale. */
public class AdmissionRequiredException extends FlashSeatsException {

    public AdmissionRequiredException() {
        super(ErrorCode.ADMISSION_REQUIRED, "Join the queue to enter this sale.");
    }
}
