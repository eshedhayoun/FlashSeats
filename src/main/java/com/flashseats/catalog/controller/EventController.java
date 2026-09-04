package com.flashseats.catalog.controller;

import com.flashseats.catalog.dto.EventDetailResponse;
import com.flashseats.catalog.dto.EventListItemResponse;
import com.flashseats.catalog.service.CatalogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public browse endpoints — the first thing a visitor touches. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final CatalogService catalog;

    public EventController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<EventListItemResponse> list() {
        return catalog.listEvents();
    }

    /**
     * The landing page. Also the request on which the {@code bot} filter mints this visitor's
     * {@code fsid} cookie, so identity exists before the sale opens.
     */
    @GetMapping("/{eventId}")
    public EventDetailResponse detail(@PathVariable long eventId) {
        return catalog.getEventDetail(eventId);
    }
}
