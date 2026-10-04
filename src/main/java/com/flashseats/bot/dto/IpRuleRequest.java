package com.flashseats.bot.dto;

import com.flashseats.bot.model.IpRuleAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * An operator adding or replacing a rule.
 *
 * <p>{@code expiresAt} is nullable and that means permanent — deliberately the awkward option to
 * choose by accident, because most abuse is a burst from one address during one sale and a rule with
 * no expiry is one somebody has to remember to remove.
 */
public record IpRuleRequest(
        @NotBlank @Size(max = 45) String ipAddress,
        @NotNull IpRuleAction action,
        @Size(max = 255) String reason,
        Instant expiresAt) {}
