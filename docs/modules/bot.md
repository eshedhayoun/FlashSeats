# Module: `bot`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.bot` · **Storage:** Redis + PostgreSQL · **Depends on:** `shared`

---

## 1. Scope

**Abuse defence: rate limits, an operator's address list, a challenge on join, and the record of
what was refused.** One servlet filter running before everything else, plus one facade method.

`filter` is a **package inside this module**, not a module of its own.

One module calls in — `queue`, on join. That edge cannot make the graph cyclic, because this module
depends on nothing but `shared`.

The `fsid` cookie used to be minted here too. It moved to `shared/identity` in Pass 7, along with
`flashseats.bot.session-secret` → `flashseats.session.secret`: identity is what every module's
authorisation rests on, and it had no business living in the abuse-defence module with its
mint/verify half a package away from its type/resolve half. The environment variable is unchanged.

---

## 2. What it owns

| Redis key | TTL | Purpose |
| :--- | :--- | :--- |
| `bot:rate:session:{sid}` | rolling | the primary rate-limit bucket |
| `bot:rate:ip:{address}` | rolling | a coarse flood backstop |
| `bot:verified:{sid}` | 900 s | one challenge verification, remembered for the session |

| PostgreSQL | Contents |
| :--- | :--- |
| `ip_rules` | address, `ALLOW`/`DENY`, reason, optional expiry — the operator's manual override |
| `bot_audit_logs` | session, address, path, outcome, detail — **non-allowed outcomes only** |

**Redis-backed, not in-memory.** In-memory buckets across three replicas silently triple every
configured limit — the kind of bug that only appears under the exact load the limits exist to control.

**`ip_rules` is never read on the request path.** It is held as a TTL-bounded in-memory snapshot; a
filter that queries the database to decide whether to shed load puts itself inside the connection
pool it exists to protect, queued behind the buyers it is shielding (ADR-051, ADR-055). **The TTL is
the cross-replica invalidation** — an operator's call evicts one replica, the others follow within
`ip-rule-cache-ttl-ms` (10 s).

Three further rules govern what the snapshot does when it is **not** working (ADR-056), and all three
are non-blocking, because a lock on this path pins carrier threads: a failed reload **stamps the
attempt like a success**, so an unreachable database is asked once per window rather than once per
request; **one reload is in flight at a time**, so a TTL boundary is not a pool spike; and an **empty
result is cached** like any other, because "no rules" is this table's normal state.

**`bot_audit_logs` has no `ALLOWED` outcome, and must not gain one.** A row per allowed request is a
write per request during exactly the traffic this system is built for, and it would make the table
unreadable for the purpose it exists to serve. Writes are asynchronous on a bounded queue that
**drops rather than blocks**: the path that records a refusal is a path an attacker controls the rate
of, and evidence is not worth an outage.

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

The SSE stream is **counted once, not exempted**. An exempt endpoint is an unmetered one — and so is
the provider's payment webhook, which is metered like everything else.

An `ALLOW` rule skips **the IP bucket only**, never the session bucket. It exists for a known shared
egress — an office, a partner's proxy — where hundreds of real buyers share one address and the coarse
backstop would throttle all of them. It is not a statement that the traffic is trusted.

---

## 3a. Verification fails open, and that is the decision

`POST /queue/join` is the one place a challenge is worth its cost: it is the front of the line, it is
cheap to repeat, and a session id costs nothing to mint — so ADR-011's per-session bucket does not
constrain a determined attacker at all. Everything after join is already gated by a queue pass and an
admission the server issued.

**Only a score the provider actively returns below `min-score` refuses a request.** An unconfigured
secret, a missing token, a timeout, a non-2xx and a malformed body all **allow** it (ADR-011,
ADR-055). Letting a third party's outage close a sale that ten thousand people are waiting for would
be the far worse failure — and it would arrive at peak load, because that is when the provider is
busiest too.

Every degraded verification is audited. Failing open is invisible from the outside, and "our bot
defence was off for three hours" must not be something anyone learns afterwards from an absence.

The provider timeouts (1 s connect, 2 s read) are **correctness settings, not tuning**. This call sits
on the join path; a default-timeout client there turns a provider slowdown into a sale-length outage —
reintroducing the exact failure that failing open exists to prevent, through the client implementing
it.

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

## 4a. What it exposes

| Method | Path | Auth |
| :--- | :--- | :--- |
| `GET` | `/api/v1/admin/bot/ip-rules` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/bot/ip-rules` | `ROLE_ADMIN` — upsert by address |
| `DELETE` | `/api/v1/admin/bot/ip-rules/{ipAddress}` | `ROLE_ADMIN` — idempotent |
| `GET` | `/api/v1/admin/bot/audit` | `ROLE_ADMIN` — paged, newest first, size capped |

**Facade:** `verifyHuman(sessionId, recaptchaToken, ipAddress)`. Called only by `queue`, on join.

Until Stage 2 there was no operator surface here at all, so an address flooding a sale could be
answered only by changing a property and restarting three replicas — during the sale. ADR-043 calls
the operator surface a correctness dependency; this is the bot half of it.

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
| **The challenge provider is unconfigured by default** | `flashseats.bot.recaptcha.secret` is blank in `dev`, `test` and a clean checkout, which means verification is **off** and every join is allowed. That is deliberate — the stack must run from a clean checkout — but it means S5's compensating control is only real where someone sets the secret |
| **No CIDR ranges, in `ip_rules` or in trusted proxies** | Both are exact addresses. Blocking a `/24` means twenty-four rows. A range parser is a place for a subtle bug to hide in the one check that decides whether the rate limiter can be bypassed, and that trade is still the right one |
| **A rule takes up to `ip-rule-cache-ttl-ms` to reach every replica** | 10 s. Inherent to not querying per request, and stated rather than hidden |
| **No rate-limit metrics** | Rejections are now in `bot_audit_logs` and still not in a counter. A dashboard cannot answer "are we shedding load right now?" without a query |
| **The audit trail has no retention policy** | Nothing prunes `bot_audit_logs`. Bounded in practice by only recording refusals, unbounded in principle |

---

## 6. What it must never do

- Let identity arrive from a request body, query parameter or custom header.
- Refuse a request because the challenge provider was unreachable. **Fail open** (ADR-011).
- Read `ip_rules` from the database on the request path.
- Write an audit row for an allowed request.
- Let an `ALLOW` rule exempt anything but the IP bucket.
- Trust `X-Forwarded-For` from an untrusted peer.
- Exempt an endpoint from counting.
- Tighten the IP bucket into the primary control.
- Hold in-memory buckets.
