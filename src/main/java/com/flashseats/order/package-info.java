/**
 * The ACID ledger and <strong>the single checkout entry point</strong>.
 *
 * <p>There is exactly one place in the system where a purchase happens, and it is here. This module
 * validates the hold, prices the purchase server-side, reserves a durable order row, drives the
 * charge, and — in one transaction — consumes the hold, writes the ledger and enqueues fulfilment.
 * It also owns every compensation path.
 *
 * <p>The sequence is not arbitrary (ADR-001). <strong>Charge first, consume second.</strong>
 * Consuming before charging would need a {@code CONSUMED → RELEASED} transition the hold state
 * machine forbids, and would briefly release inventory the buyer is actively paying for. Charging
 * first means a hold is only ever destroyed by a transaction that is about to commit.
 *
 * <p><strong>Forbidden:</strong> touching stock counters, managing queue positions, rendering PDFs or
 * sending email, calling a payment provider directly. This module owns no Redis keys of any kind.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Order")
package com.flashseats.order;
