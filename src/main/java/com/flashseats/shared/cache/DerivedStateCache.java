package com.flashseats.shared.cache;

/**
 * In-process state derived from a database, which any caller may drop at any time.
 * <strong>Nothing behind this interface is authoritative</strong>: dropping every entry costs at
 * most a re-read. It is the seam {@code SaleFixture.reset()} clears between tests, so caching stays
 * on in the test profile (ADR-051).
 */
public interface DerivedStateCache {

    /** Drops everything held. Never throws; never loses anything that matters. */
    void invalidateAll();
}
