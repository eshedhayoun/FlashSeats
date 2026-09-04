package com.flashseats.flashseats.hold.dto.response;
import java.time.Instant;

import com.flashseats.flashseats.hold.model.HoldStatus;
/*could be change in the future based on the information we need represented here */
public record HoldStatusDTO(
    String holdToken,
    HoldStatus status,
    Instant expiresAt,
    long ttlRemainingSeconds
) {}
