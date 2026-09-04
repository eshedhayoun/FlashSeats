package com.flashseats.flashseats.hold.dto.internal;
import java.time.Instant;
/*could be change in the future based on the information we need represented here */

public record HoldSummaryDTO(
    String holdToken,
    String userSessionId,
    Long eventId,
    Long tierId,
    Integer quantity,
    Instant expiresAt
) {}
