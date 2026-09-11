package com.flashseats.hold.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A request to reserve seats.
 *
 * <p>Note what is <strong>not</strong> here: {@code userSessionId}. Identity comes from the signed
 * {@code fsid} cookie and nowhere else — accepting it in the body would let any client act as any
 * other buyer (ADR-010).
 *
 * <p>The upper bound on {@code quantity} is not annotated, because it is not a constant: it is
 * {@code min(6, tier.maxPerOrder)} and only known once the tier is loaded.
 */
public record CreateHoldRequest(
        @NotNull Long eventId, @NotNull Long tierId, @Min(1) int quantity) {}
