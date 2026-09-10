package com.flashseats.catalog.service;

import com.flashseats.catalog.redis.CatalogRedisRepository;
import com.flashseats.catalog.repository.TierInventoryRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CatalogInventoryRebuildService {

    private final TierInventoryRepository inventory;
    private final CatalogRedisRepository redisStock;

    public CatalogInventoryRebuildService(
            TierInventoryRepository inventory,
            CatalogRedisRepository redisStock) {
        this.inventory = inventory;
        this.redisStock = redisStock;
    }

    public int rebuild(long eventId) {
        InventoryRebuildSnapshot snapshot = readAuthoritativeSnapshot(eventId);

        for (InventoryRebuildSnapshot.TierStock tier : snapshot.tiers()) {
            redisStock.setStock(
                    snapshot.eventId(),
                    tier.tierId(),
                    tier.remaining());
        }

        log.info(
                "Rebuilt Redis inventory for event {}: {} tier counters",
                eventId,
                snapshot.tiers().size());

        return snapshot.tiers().size();
    }

    @Transactional(readOnly = true)
    protected InventoryRebuildSnapshot readAuthoritativeSnapshot(long eventId) {
        List<InventoryRebuildSnapshot.TierStock> tiers =
                inventory.findAuthoritativeRemainingByEvent(eventId)
                        .stream()
                        .map(row -> new InventoryRebuildSnapshot.TierStock(
                                ((Number) row[0]).longValue(),
                                ((Number) row[1]).intValue()))
                        .toList();

        return new InventoryRebuildSnapshot(eventId, tiers);
    }
}