/**
 * Event metadata, sale windows, and <strong>inventory ownership</strong>.
 *
 * <p>Serves the browse reads, publishes the server clock the pre-sale countdown runs against
 * (ADR-016), and owns the one number that decides whether a sale oversells.
 *
 * <p><strong>Inventory ownership, precisely.</strong> This module owns {@code tier_inventory} and
 * (from Phase 2) {@code catalog:stock:{eventId}:{tierId}}. {@code hold} is the only other module
 * permitted to move that number, and only through {@link
 * com.flashseats.catalog.facade.CatalogFacade#tryReserve} / {@code restore}, because a decrement and
 * the reservation that justifies it must be one atomic operation. That exception is deliberate,
 * narrow, and the only shared state in the system.
 *
 * <p><strong>Forbidden:</strong> creating holds, processing payments, managing queue positions,
 * writing orders.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catalog")
package com.flashseats.catalog;
