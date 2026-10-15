/**
 * Rate limiting and bot defence, the first thing every API request touches. Identity itself
 * ({@code fsid}) is {@code shared}'s.
 *
 * <p>Aggressive against sessions, conservative against IPs: thousands of legitimate buyers share
 * carrier NAT during a spike (ADR-011). {@code queue} calls in on join through
 * {@link com.flashseats.bot.facade.BotFacade}; this module depends on nothing but {@code shared}.
 *
 * <p><strong>Verification fails open</strong> (ADR-011, ADR-055). The per-request path never touches
 * the database: buckets live in Redis and address rules in a TTL snapshot, because a filter that
 * queries to shed load sits inside the pool it protects (ADR-051).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Bot Defence")
package com.flashseats.bot;
