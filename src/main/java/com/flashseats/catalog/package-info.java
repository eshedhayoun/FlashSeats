/**
 * Event metadata, sale windows, and <strong>inventory ownership</strong>. Serves the browse reads and
 * the server clock for the countdown (ADR-016), and owns {@code catalog:stock:{e}:{t}}, the live count.
 * PostgreSQL keeps no copy (ADR-046). Only this module moves the counter: {@code hold} reserves
 * and restores through {@link com.flashseats.catalog.facade.CatalogFacade}.
 *
 * <p><strong>Forbidden:</strong> creating holds, processing payments, managing queue positions,
 * writing orders.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catalog")
package com.flashseats.catalog;
