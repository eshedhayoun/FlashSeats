package com.flashseats.shared.cache;

/**
 * In-process state derived from a database, which any caller may drop at any time.
 *
 * <p>Implementing this is a claim, not a convenience: <strong>nothing here is authoritative.</strong>
 * Dropping every entry must cost at most a re-read. A cache that would lose information — or that
 * something reads as truth — does not belong behind this interface.
 *
 * <p>It exists because "wipe the world" is a real operation. The integration fixture truncates every
 * table with {@code RESTART IDENTITY} between test methods, so ids come back as {@code 1} and a
 * surviving entry describes the previous test's sale. Without one seam to clear, the only other way
 * to keep the suite honest is to disable caching in tests — which leaves the configuration that
 * actually runs in production with no coverage at all.
 *
 * <p>Lives in {@code shared} because the alternative is a test fixture importing a module's
 * {@code service} package, which is the boundary violation {@code ApplicationModules.verify()}
 * exists to catch — and it does not care that the caller is a test.
 */
public interface DerivedStateCache {

    /** Drops everything held. Never throws; never loses anything that matters. */
    void invalidateAll();
}
