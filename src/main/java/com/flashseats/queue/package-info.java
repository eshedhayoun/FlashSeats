/**
 * The virtual waiting room. <strong>It does not prevent overbooking; {@code hold} does.</strong> It
 * spreads the spike and tells the thousands who will miss out quickly. Admission is bounded by real
 * remaining capacity.
 *
 * <p>Three timers nest (ADR-020): a 120 s single-use pass, a 600 s admission session, and the
 * 300 s hold, which belongs to {@code hold}.
 *
 * <p><strong>Forbidden:</strong> reading inventory tables, creating holds, processing payments,
 * writing orders. No PostgreSQL state.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Queue")
package com.flashseats.queue;
