# Module: `bot`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.bot` · **Storage:** Redis · **Depends on:** `shared`

---

## 1. Scope

Two servlet filters that run before everything else: one issues and verifies the signed `fsid`
cookie, one enforces rate limits.

`filter` is a **package inside this module**, not a module of its own.

---

## 2. What it owns

| Redis key | Purpose |
| :--- | :--- |
| `bot:rate:session:{sid}` | the primary rate-limit bucket |
| `bot:rate:ip:{address}` | a coarse flood backstop |

**Redis-backed, not in-memory.** In-memory buckets across three replicas silently triple every
configured limit — the kind of bug that only appears under the exact load the limits exist to control.

---

## 3. The two asymmetries that matter

**Session-first, IP as a backstop** (ADR-011). This is the opposite of the obvious design, and
deliberately so: a flash sale means thousands of legitimate humans arriving at once, many sharing one
carrier-grade NAT or corporate gateway. A tight per-IP limit blocks all of them during precisely the
spike this system exists to serve.

| Bucket | Capacity | Refill |
| :--- | :--- | :--- |
| session | 20 | 10/s |
| IP | 300 | 150/s |

**`X-Forwarded-For` is believed only from a trusted peer** (ADR-039), and the trusted set is
**empty by default — trust nobody**. Trusting the header unconditionally let any caller mint unlimited
fresh IP buckets by rotating a fake address; combined with a session bucket that is free to mint by
dropping a cookie, that left the backstop enforcing nothing at all.

The set holds **exact addresses, not CIDR ranges**. It is the handful of load balancers in front of
the app, and a range parser is a place for a subtle bug to hide in the one check that decides whether
the rate limiter can be bypassed.

The SSE stream is **counted once, not exempted**. An exempt endpoint is an unmetered one.

---

## 4. Identity

The `fsid` cookie is `base64url(uuid).base64url(HMAC-SHA256(uuid, secret))`, `HttpOnly`, with the
signed bytes length-prefixed by a `kind` so a token of one kind never verifies as another (ADR-039).

**A tampered cookie is replaced, not rejected.** The visitor did nothing an error page would help
with, and a hard failure on a corrupted cookie strands them with no way to recover.

Identity reaches a handler **only** as a resolved `SessionId` parameter. There is no path by which a
body field, query parameter or custom header can supply one (ADR-010).

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
| **Identity lives in the wrong module** | The cookie filter, the `"fsid"` kind and the signing secret (`flashseats.bot.session-secret`) are here; the `SessionId` type and its argument resolver are in `shared`. The two are coupled by a request-attribute string constant. `bot` is abuse defence and identity is not abuse defence — **the filter should move to `shared/identity` and the property to `flashseats.session.*`**. Specified, not built |
| **No challenge provider** | reCAPTCHA appears in older diagrams. **It does not exist**, no property for it exists, and no code references it. Stage 2 |
| **No IP reputation, no audit log, no admin surface** | Earlier drafts specified all three. None is built, and none is required by anything |
| **No rate-limit metrics** | Rejections are invisible except in logs |

---

## 6. What it must never do

- Let identity arrive from a request body, query parameter or custom header.
- Trust `X-Forwarded-For` from an untrusted peer.
- Exempt an endpoint from counting.
- Tighten the IP bucket into the primary control.
- Hold in-memory buckets.
