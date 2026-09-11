package com.flashseats.order.controller;

import com.flashseats.order.service.StockReconciliationService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recovery from a lost or wrong stock counter — ADR-004's only legal one.
 *
 * <p>It is served from {@code order} rather than {@code catalog} because the ledger it rebuilds from
 * spans three modules' tables and {@code order} is the only one permitted to read all of them. The
 * endpoint path still names the event, because that is what an operator is thinking about.
 *
 * <p>Guarded by {@code ROLE_ADMIN} in
 * {@link com.flashseats.flashseats.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminStockController {

    private final StockReconciliationService reconciliation;

    public AdminStockController(StockReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * Recomputes every tier's counter from {@code capacity − confirmed − held} and writes it.
     *
     * <p>Takes a couple of seconds by design: it reads the ledger twice so a reservation that has
     * decremented Redis but not yet committed its hold row cannot be counted as a free seat.
     */
    @PostMapping("/{eventId}/rebuild-stock")
    public Map<String, Object> rebuildStock(@PathVariable long eventId) {
        return Map.of("eventId", eventId, "remainingByTier", reconciliation.rebuild(eventId));
    }
}
