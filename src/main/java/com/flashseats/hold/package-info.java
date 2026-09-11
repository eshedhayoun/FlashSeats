/**
 * Time-bound seat reservations — <strong>this is where overbooking is prevented</strong>.
 *
 * <p>The queue shapes traffic; {@code hold} enforces correctness. Two guarantees live here and
 * nowhere else:
 *
 * <ol>
 *   <li><strong>Seats cannot be sold twice.</strong> A reservation is one conditional statement whose
 *       precondition sits in its {@code WHERE} clause, so the database serialises concurrent buyers.
 *   <li><strong>Reserved seats return to the pool exactly once</strong>, however the reservation ends
 *       — bought, cancelled, expired or swept. Every ending runs the same settle-once claim, and only
 *       the caller that affects one row restores the stock (ADR-019).
 * </ol>
 *
 * <p>{@code ticket_holds} is the authority. When Redis arrives it will carry the expiry timer and
 * nothing else — if every Redis key vanished, this table would still describe the truth.
 *
 * <p><strong>Forbidden:</strong> processing payments, pricing, issuing queue passes, writing orders.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Hold")
package com.flashseats.hold;
