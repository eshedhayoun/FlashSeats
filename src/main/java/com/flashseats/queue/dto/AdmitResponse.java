package com.flashseats.queue.dto;

import java.time.Instant;

/**
 * The admission session, handed over in exchange for a pass.
 *
 * <p>The client sends {@code admissionToken} as {@code X-Admission-Token} when reserving seats. It
 * outlives any single hold, which is what lets a buyer release seats and pick a different tier
 * without returning to the back of the queue (ADR-020).
 */
public record AdmitResponse(String admissionToken, Instant expiresAt, Instant serverTime) {}
