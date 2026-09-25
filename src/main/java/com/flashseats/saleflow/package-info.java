/**
 * A read-only composition module: one endpoint that tells a reloaded client exactly where it is in
 * the journey (ADR-025). It is its own leaf because {@code queue} cannot depend on {@code hold}
 * without a cycle.
 *
 * <p><strong>Forbidden:</strong> any write, any storage, any business rule.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sale Flow")
package com.flashseats.saleflow;
