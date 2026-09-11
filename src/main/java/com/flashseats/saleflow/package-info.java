/**
 * A read-only composition module: one endpoint that tells a client exactly where it is.
 *
 * <p>Without it, a tab reload at any point loses the entire journey — the client has no way to
 * discover that this session is admitted to the sale, holds two seats with ninety seconds left, and
 * has a payment in flight. Every production ticketing SPA calls exactly one state endpoint on mount,
 * and this is it (ADR-025).
 *
 * <p><strong>Why it is its own module.</strong> It cannot live in {@code queue}, because {@code
 * queue} would then need {@code HoldFacade} while {@code hold → queue} already exists — a cycle the
 * build rejects. A leaf that depends on many and is depended on by none is the standard answer, and
 * it costs nothing: no storage, no state, no lifecycle.
 *
 * <p><strong>Forbidden:</strong> any write, any storage, any business rule. If a decision is being
 * made here, it belongs in the module that owns the state.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sale Flow")
package com.flashseats.saleflow;
