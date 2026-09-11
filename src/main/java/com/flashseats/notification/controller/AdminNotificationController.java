package com.flashseats.notification.controller;

import com.flashseats.notification.dto.DeadLetterResponse;
import com.flashseats.notification.service.NotificationLogService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What did not get delivered.
 *
 * <p>This is half of the capability ADR-029 and ADR-038 have been assuming. ADR-029 sends
 * deterministic failures straight to the dead-letter queue <em>with no retries</em> — correct, since
 * a render that fails once fails identically three times and only delays the queue — but correct
 * <strong>only if someone can find them</strong>. ADR-038 then made a dead-lettered claim
 * re-claimable specifically so a replay would send. Until now nothing could list them and nothing
 * could trigger that replay, which made the DLQ a black hole with a paid buyer's ticket in it.
 *
 * <p>The other half — actually resending — lives in {@code order}, and that is not an inconsistency:
 * a resend needs the original <em>payload</em>, and this module does not have it. See
 * {@code AdminResendController}.
 *
 * <p>Guarded by {@code ROLE_ADMIN} in {@link com.flashseats.flashseats.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationLogService notifications;

    public AdminNotificationController(NotificationLogService notifications) {
        this.notifications = notifications;
    }

    /**
     * The dead letters, newest first.
     *
     * <p>Paged, and the size is capped rather than trusted. The moment this endpoint matters most is
     * a broker or mail outage, which is precisely when the result set is largest — an unbounded
     * {@code size} would let one operator request pull the whole table into memory during an
     * incident.
     */
    @GetMapping("/dlq")
    public Map<String, Object> deadLetters(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {

        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        List<DeadLetterResponse> entries = notifications.deadLetters(Math.max(0, page), bounded);

        return Map.of(
                "page", Math.max(0, page),
                "size", bounded,
                "total", notifications.deadLetterCount(),
                "entries", entries);
    }
}
