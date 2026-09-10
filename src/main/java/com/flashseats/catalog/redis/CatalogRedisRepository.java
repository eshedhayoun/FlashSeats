package com.flashseats.catalog.redis;
import java.util.OptionalInt;

/**
 * Redis-backed inventory operations owned by the catalog module.
 *
 * <p>This repository only deals with the Redis representation of inventory.
 * PostgreSQL remains the authoritative source used for rebuild/reconciliation.
 */
public interface CatalogRedisRepository {
    OptionalInt getStock(long eventId, long tierId);
    boolean initializeIfAbsent(long eventId, long tierId, int totalCapacity);
    void setStock(long eventId, long tierId, int remaining);
}
