package com.flashseats.catalog.service;

/**
 * One event's metadata changed in PostgreSQL, so the cached snapshot of it is wrong.
 *
 * <p>Published inside the writing transaction and consumed {@code AFTER_COMMIT}. Evicting inline
 * would leave a window in which a concurrent reader re-caches the row the transaction is about to
 * change — and since the entry it writes would then be a fresh one, the operator's pause could
 * silently fail to take effect on the very replica that served the call (ADR-051).
 */
record EventMetadataChanged(long eventId) {}
