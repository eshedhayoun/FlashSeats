package com.flashseats.bot.dto;

import com.flashseats.bot.model.IpRuleAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * An operator adding or replacing a rule.
 *
 * <p>{@code expiresAt} is nullable and that means permanent — deliberately the awkward option to
 * choose by accident, because most abuse is a burst from one address during one sale and a rule with
 * no expiry is one somebody has to remember to remove.
 */
public record IpRuleRequest(
        @NotBlank String ipAddress,
        @NotNull IpRuleAction action,
        String reason,
        Instant expiresAt) {}
