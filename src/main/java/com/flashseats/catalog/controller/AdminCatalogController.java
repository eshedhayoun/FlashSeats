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
}
