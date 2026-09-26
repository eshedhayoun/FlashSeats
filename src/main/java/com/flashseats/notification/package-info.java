/**
 * PDF tickets and email delivery, entirely off the checkout path. It calls no facade: the outbox
 * payload is a complete snapshot (ADR-015). Idempotency is {@code UNIQUE(order_number, kind)} with
 * insert-then-send, never a preceding {@code SELECT}.
 *
 * <p><strong>Forbidden:</strong> reading inventory, changing order status, validating payments,
 * managing queue positions.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.flashseats.notification;
