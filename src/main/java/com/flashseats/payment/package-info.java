/**
 * The payment gateway boundary: charging and refunding behind one interface, idempotency, and a
 * durable transaction ledger.
 *
 * <p><strong>It calls no facade at all</strong> (ADR-005). The webhook already runs
 * {@code payment → order} as an event, so any synchronous edge out of here would close a cycle.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment")
package com.flashseats.payment;
