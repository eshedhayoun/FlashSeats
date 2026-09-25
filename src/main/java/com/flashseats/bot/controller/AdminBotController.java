package com.flashseats.bot.controller;

import com.flashseats.bot.dto.BotAuditResponse;
import com.flashseats.bot.dto.IpRuleRequest;
import com.flashseats.bot.dto.IpRuleResponse;
import com.flashseats.bot.service.BotAuditService;
import com.flashseats.bot.service.IpRuleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manual override, and the record of what was refused (ADR-043). A change applies cluster-wide
 * within {@code ip-rule-cache-ttl-ms} (10 s): the serving replica drops its snapshot at once, and the
 * others when theirs expires. Reading {@code ip_rules} per request instead would put the rate
 * limiter inside the pool it protects (ADR-055).
 *
 * <p>Guarded by {@code ROLE_ADMIN} in {@link com.flashseats.app.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin/bot")
public class AdminBotController {

    private static final int MAX_PAGE_SIZE = 200;

    private final IpRuleService ipRules;
    private final BotAuditService audit;

    public AdminBotController(IpRuleService ipRules, BotAuditService audit) {
        this.ipRules = ipRules;
        this.audit = audit;
    }

    @GetMapping("/ip-rules")
    public List<IpRuleResponse> rules() {
        return ipRules.all().stream().map(IpRuleResponse::of).toList();
    }

    /** Upsert by address, so re-blocking a known address is not an error an operator has to handle. */
    @PostMapping("/ip-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public IpRuleResponse upsert(@Valid @RequestBody IpRuleRequest request) {
        return IpRuleResponse.of(ipRules.upsert(
                request.ipAddress(), request.action(), request.reason(), request.expiresAt()));
    }

    /** Idempotent: removing a rule that is not there is the state the caller asked for. */
    @DeleteMapping("/ip-rules/{ipAddress}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String ipAddress) {
        ipRules.remove(ipAddress);
    }

    /**
     * What was refused, newest first.
     *
     * <p>Paged, with the size capped rather than trusted. This endpoint matters most during an
     * attack, which is exactly when the result set is largest — an unbounded {@code size} would let
     * one operator request pull the whole table into memory mid-incident.
     */
    @GetMapping("/audit")
    public Map<String, Object> auditTrail(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {

        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        var entries = audit.recent(Math.max(0, page), bounded);

        return Map.of(
                "page", Math.max(0, page),
                "size", bounded,
                "total", entries.getTotalElements(),
                "entries", entries.getContent().stream().map(BotAuditResponse::of).toList());
    }
}
