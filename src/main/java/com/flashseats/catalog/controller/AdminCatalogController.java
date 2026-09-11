package com.flashseats.catalog.controller;

import com.flashseats.catalog.service.CatalogService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints. Guarded by {@code ROLE_ADMIN} in
 * {@link com.flashseats.flashseats.config.SecurityConfig} — "admin only" is an enforced role here,
 * not a comment.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminCatalogController {

    private final CatalogService catalog;

    public AdminCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** Seeds inventory ahead of a sale. Refuses unless the window is still {@code UPCOMING}. */
    @PostMapping("/{eventId}/prewarm")
    public Map<String, Object> prewarm(@PathVariable long eventId) {
        return Map.of("eventId", eventId, "tiersSeeded", catalog.prewarm(eventId));
    }

    /**
     * Halts a live sale.
     *
     * <p>Every gate closes immediately — the queue admits nobody, no hold can be taken, no checkout
     * starts — because a paused event is not {@code PUBLISHED} and {@code SaleWindows} already reads
     * the window as {@code CLOSED}.
     *
     * <p><strong>Nothing is destroyed.</strong> The waiting room keeps every position, live passes
     * and admissions run out their own clocks, and stock stays exactly where it is, so
     * {@code /resume} puts every buyer back where they were. Idempotent.
     */
    @PostMapping("/{eventId}/pause")
    public Map<String, Object> pause(@PathVariable long eventId) {
        return Map.of("eventId", eventId, "status", catalog.setPaused(eventId, true));
    }

    /** Puts a paused sale back on sale. Idempotent. */
    @PostMapping("/{eventId}/resume")
    public Map<String, Object> resume(@PathVariable long eventId) {
        return Map.of("eventId", eventId, "status", catalog.setPaused(eventId, false));
    }
}
