/**
 * The virtual waiting room.
 *
 * <p><strong>The queue does not prevent overbooking — {@code hold} does.</strong> It exists so the
 * thousands who will not get tickets do not all hit checkout at once, and so they find out quickly
 * rather than slowly. That framing has consequences: admission is bounded by real remaining capacity
 * (otherwise the queue is just a slower way to deliver a {@code 409}), and the module is
 * correctness-neutral enough to be skipped entirely without risking a single oversold seat.
 *
 * <p>Three timers nest here (ADR-020): a 120 s single-use <em>pass</em> proves you left the queue; a
 * 600 s <em>admission session</em> means you are inside the sale and may browse, change your mind,
 * reload the tab, or release a hold without losing your place; the 300 s <em>hold</em> belongs to
 * another module.
 *
 * <p><strong>Forbidden:</strong> reading inventory tables, creating holds, processing payments,
 * writing orders. Zero PostgreSQL state — nothing here matters after the sale ends.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Queue")
package com.flashseats.queue;
