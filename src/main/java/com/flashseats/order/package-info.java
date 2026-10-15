/**
 * The ACID ledger and <strong>the single checkout entry point</strong>: validate the hold, price
 * server-side, reserve an order row, charge, then in one transaction consume the hold, write the
 * ledger and enqueue fulfilment. It owns every compensation path.
 *
 * <p><strong>Charge first, consume second</strong> (ADR-001): the hold state machine has no
 * {@code CONSUMED → RELEASED}, so a hold is only destroyed by a transaction about to commit.
 *
 * <p><strong>Forbidden:</strong> touching stock counters, managing queue positions, rendering PDFs,
 * sending email, calling a payment provider directly. It owns no Redis keys.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Order")
package com.flashseats.order;
