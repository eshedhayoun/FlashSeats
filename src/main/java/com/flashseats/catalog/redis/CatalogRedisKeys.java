package com.flashseats.catalog.redis;

public final class CatalogRedisKeys {
    private static final String STOCK_PREFIX = "catalog:stock:";

    private CatalogRedisKeys() {}

    public static String stock(long eventId, long tierId) {
        return STOCK_PREFIX + eventId + ":" + tierId;
    }
}
