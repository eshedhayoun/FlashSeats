/**
 * Gatekeeping — the first thing every request touches.
 *
 * <p>Issues the signed {@code fsid} cookie the rest of the system treats as identity, and enforces
 * rate limits before any business logic runs.
 *
 * <p><strong>Design stance:</strong> aggressive against sessions, conservative against IPs. During a
 * flash sale thousands of legitimate humans arrive at once, many behind the same carrier-grade NAT;
 * a tight per-IP limit would block them all during exactly the spike this system exists to serve
 * (ADR-011).
 *
 * <p>The filters here are servlet infrastructure, not facade callers. Identity reaches them as a
 * request attribute ({@link com.flashseats.shared.identity.SessionId#REQUEST_ATTRIBUTE}).
 *
 * <p>One module calls in: {@code queue}, on join, through {@link com.flashseats.bot.facade.BotFacade}.
 * That edge cannot make the graph cyclic because this module depends on nothing but {@code shared}.
 *
 * <p><strong>Verification fails open</strong> (ADR-011). A challenge provider that is unconfigured,
 * slow or broken lets the visitor through and is audited as degraded. The alternative is letting a
 * third party's bad afternoon close a sale that ten thousand people are waiting for — and it would
 * fail at peak load, because that is when the provider is busiest too.
 *
 * <p>Two things here run on <em>every</em> API request and therefore may never touch the database on
 * that path: the token buckets, which live in Redis, and the address rules, which live in a
 * TTL-bounded in-memory snapshot. A filter that queries to decide whether to shed load puts itself
 * inside the connection pool it exists to protect (ADR-051, ADR-055).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Bot Defence")
package com.flashseats.bot;
