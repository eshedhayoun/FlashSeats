package com.flashseats.catalog.controller;

import com.flashseats.catalog.dto.AdminEventResponse;
import com.flashseats.catalog.dto.CreateEventRequest;
import com.flashseats.catalog.service.CatalogService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints. Guarded by {@code ROLE_ADMIN} in
 * {@link com.flashseats.app.SecurityConfig} — "admin only" is an enforced role here,
 * not a comment.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminCatalogController {

    private final CatalogService catalog;

    public AdminCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** Every event in any status, with exact live stock (ADR-080). */
    @GetMapping
    public List<AdminEventResponse> list() {
        return catalog.listEventsForAdmin();
    }

    /** Creates, publishes and pre-warms a sale. There is no update: delete and recreate (ADR-080). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminEventResponse create(@Valid @RequestBody CreateEventRequest request) {
        return catalog.createEvent(request);
    }

    /** Deletes a sale more than a minute from opening; later, pause it instead (ADR-080). */
    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long eventId) {
        catalog.deleteEvent(eventId);
    }

    /** Seeds inventory ahead of a sale. Refuses unless the window is still {@code UPCOMING}. */
    @PostMapping("/{eventId}/prewarm")
    public Map<String, Object> prewarm(@PathVariable long eventId) {
        return Map.of("eventId", eventId, "tiersSeeded", catalog.prewarm(eventId));
    }

    /**
     * Halts a live sale: nobody is promoted and no hold is created, while the line keeps forming and a
     * buyer already holding seats can still pay (ADR-066). Nothing is destroyed: positions, passes,
     * admissions and stock stay, so {@code /resume} puts everyone back where they were. Idempotent.
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
