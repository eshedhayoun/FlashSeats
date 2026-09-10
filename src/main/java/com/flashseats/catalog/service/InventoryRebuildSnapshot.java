package com.flashseats.catalog.service;

import java.util.List;

public record InventoryRebuildSnapshot(long eventId,List<TierStock> tiers) {

    public record TierStock(long tierId,int remaining) {}
}