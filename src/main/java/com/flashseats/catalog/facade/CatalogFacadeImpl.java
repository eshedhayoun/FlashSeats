package com.flashseats.catalog.facade;

import com.flashseats.catalog.service.CatalogService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin delegation to {@link CatalogService}. Package-private: other modules see only the interface.
 *
 * <p>There is deliberately no logic here. A facade that made decisions would be a second place where
 * catalog rules live, and the two would drift.
 */
@Component
class CatalogFacadeImpl implements CatalogFacade {

    private final CatalogService catalog;

    CatalogFacadeImpl(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public TierSummary getTierSummary(long eventId, long tierId) {
        return catalog.getTierSummary(eventId, tierId);
    }

    @Override
    public EventSummary getEventSummary(long eventId) {
        return catalog.getEventSummary(eventId);
    }

    @Override
    public EventWindowStatus getWindowStatus(long eventId) {
        return catalog.getWindowStatus(eventId);
    }

    @Override
    public List<Long> findOpenEventIds() {
        return catalog.findOpenEventIds();
    }

    @Override
    public List<Long> findManagedEventIds() {
        return catalog.findManagedEventIds();
    }

    @Override
    public int getRemainingForEvent(long eventId) {
        return catalog.getRemainingForEvent(eventId);
    }

    @Override
    public ReserveResult tryReserve(long eventId, long tierId, int quantity) {
        return catalog.tryReserve(eventId, tierId, quantity);
    }

    @Override
    public void restore(long eventId, long tierId, int quantity) {
        catalog.restore(eventId, tierId, quantity);
    }

    @Override
    public Map<Long, Integer> getTierCapacities(long eventId) {
        return catalog.getTierCapacities(eventId);
    }

    @Override
    public Map<Long, Integer> getLiveCounters(long eventId) {
        return catalog.getLiveCounters(eventId);
    }

    @Override
    public void applyRebuild(long eventId, Map<Long, Integer> remainingByTier) {
        catalog.applyRebuild(eventId, remainingByTier);
    }
}
