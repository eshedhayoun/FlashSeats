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
 * <p>The filters here are servlet infrastructure, not facade callers — no other module references a
 * type in this package. Identity reaches them as a request attribute
 * ({@link com.flashseats.shared.identity.SessionId#REQUEST_ATTRIBUTE}).
 *
 * <p>MVP scope: cookie identity and rate limits. reCAPTCHA, {@code ip_rules} and audit logging are
 * deferred — this module writes no tables.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Bot Defence")
package com.flashseats.bot;
