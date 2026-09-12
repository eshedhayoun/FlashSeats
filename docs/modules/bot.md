# Module: `bot`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.bot` · **Storage:** Redis · **Depends on:** `shared`

---

## 1. Scope

**Rate limiting, and nothing else.** One servlet filter, running before everything else.

`filter` is a **package inside this module**, not a module of its own.

The `fsid` cookie used to be minted here too. It moved to `shared/identity` in Pass 7, along with
`flashseats.bot.session-secret` → `flashseats.session.secret`: identity is what every module's
authorisation rests on, and it had no business living in the abuse-defence module with its
mint/verify half a package away from its type/resolve half. The environment variable is unchanged.

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

## 4. Identity — not here any more

The `fsid` cookie, its signing secret and the filter that issues it moved to
[`shared`](shared.md) in Pass 7. This module **reads** the resolved session id to key its primary
bucket and owns none of it.

That matters for one reason worth stating: the session bucket is the primary rate-limit control, and
a visitor who drops their cookie mints a fresh one. The IP bucket is the backstop for exactly that,
which is why `X-Forwarded-For` may only be believed from a trusted peer (ADR-039) — without both
halves, ADR-011's design enforces nothing.

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
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
