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
 * What did not get delivered: the listing that makes ADR-029's no-retry dead-letter queue and
 * ADR-038's re-claimable rows usable. Resending lives in {@code order} ({@code AdminOrderController}),
 * because a resend needs the original payload and only the outbox has it.
 *
 * <p>Guarded by {@code ROLE_ADMIN} in {@link com.flashseats.app.SecurityConfig}.
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
