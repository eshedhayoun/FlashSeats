/**
 * PDF tickets and email delivery, entirely off the checkout request path.
 *
 * <p><strong>This module calls no other module's facade</strong>, and it does not need to: the
 * outbox payload is a complete, self-contained snapshot carrying the event's title, venue, date and
 * every line item (ADR-015). That is what lets fulfilment be genuinely asynchronous rather than a
 * background task that still reaches back into the request path's data.
 *
 * <p>Idempotency is a database constraint, not a check. The consumer inserts its log row
 * <em>before</em> rendering anything and lets {@code UNIQUE(order_number, kind)} stop a duplicate — a
 * preceding {@code SELECT} is a race that two workers both pass, and the result is a buyer with two
 * tickets.
 *
 * <p><strong>Forbidden:</strong> reading inventory, changing order status, validating payments,
 * managing queue positions.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.flashseats.notification;
