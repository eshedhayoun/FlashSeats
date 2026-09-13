package com.flashseats.bot.dto;

import com.flashseats.bot.model.IpRule;
import com.flashseats.bot.model.IpRuleAction;
import java.time.Instant;

/** One rule as an operator sees it. */
public record IpRuleResponse(
        String ipAddress, IpRuleAction action, String reason, Instant createdAt, Instant expiresAt) {

    public static IpRuleResponse of(IpRule rule) {
        return new IpRuleResponse(
                rule.getIpAddress(),
                rule.getAction(),
                rule.getReason(),
                rule.getCreatedAt(),
                rule.getExpiresAt());
    }
}
