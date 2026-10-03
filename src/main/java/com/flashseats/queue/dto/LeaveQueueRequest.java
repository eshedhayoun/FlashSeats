package com.flashseats.queue.dto;

import jakarta.validation.constraints.NotNull;

/** A request to step out of the waiting room. Identity comes from the {@code fsid} cookie (ADR-010). */
public record LeaveQueueRequest(@NotNull Long eventId) {}
