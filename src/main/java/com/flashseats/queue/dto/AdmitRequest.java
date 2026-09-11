package com.flashseats.queue.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A request to exchange a queue pass for an admission session.
 *
 * <p>The pass itself travels in the {@code X-Queue-Pass-Token} header, not here: it is a capability,
 * and keeping it out of the body keeps it out of anything that logs request payloads.
 *
 * <p>Note what is absent, as everywhere: {@code userSessionId}. Identity comes from the signed
 * {@code fsid} cookie alone (ADR-010).
 */
public record AdmitRequest(@NotNull Long eventId) {}
