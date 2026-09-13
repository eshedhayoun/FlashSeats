/**
 * {@code bot}'s published contract. Everything else in the module is internal.
 *
 * <p>This is the module's <strong>only</strong> outward surface besides its servlet filters, and it
 * has exactly one method. {@code bot} depends on nothing but {@code shared}, so an inbound edge from
 * {@code queue} cannot make the graph cyclic.
 */
@org.springframework.modulith.NamedInterface("facade")
package com.flashseats.bot.facade;
