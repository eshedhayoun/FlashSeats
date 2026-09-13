package com.flashseats.bot.dto;

import com.flashseats.bot.model.BotAuditLog;
import com.flashseats.bot.model.BotOutcome;
import java.time.Instant;

/**
 * One refused or degraded request.
 *
 * <p>Carries the session id, which is an opaque identifier this system mints and not a person. It
 * is here because correlating a burst to one session is the first thing anyone does with this list.
 */
public record BotAuditResponse(
        String sessionId,
        String ipAddress,
        String path,
        BotOutcome outcome,
        String detail,
        Instant createdAt) {

    public static BotAuditResponse of(BotAuditLog entry) {
        return new BotAuditResponse(
                entry.getSessionId(),
                entry.getIpAddress(),
                entry.getPath(),
                entry.getOutcome(),
                entry.getDetail(),
                entry.getCreatedAt());
    }
}
