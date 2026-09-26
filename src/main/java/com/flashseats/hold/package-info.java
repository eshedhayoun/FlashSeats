/**
 * Time-bound seat reservations: <strong>where overbooking is prevented</strong>. Two guarantees live
 * here and nowhere else:
 *
 * <ol>
 *   <li><strong>Seats cannot be sold twice.</strong> A reservation is one conditional statement.
 *   <li><strong>Reserved seats return exactly once</strong>, however the hold ends: every ending
 *       runs the same settle-once claim, and only its winner restores stock (ADR-019).
 * </ol>
 *
 * <p>{@code ticket_holds} is the authority. Redis carries only the expiry timer, a hint.
 *
 * <p><strong>Forbidden:</strong> processing payments, pricing, issuing queue passes, writing orders.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Hold")
package com.flashseats.hold;
