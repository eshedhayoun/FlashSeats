# Architecture Decision Record

> Every decision below resolves a contradiction, correctness bug, or gap found in the first-pass
> design review. Each entry states the decision, the reason, and what it replaces. When a module
> spec and this document disagree, **this document wins** and the module spec is stale.

---

## ADR-001 — `order` orchestrates checkout; charge first, consume the hold second

**Decision.** There is exactly one checkout entry point: `POST /api/v1/orders/checkout`. The `order`
module drives the sequence:

1. Validate the hold is `ACTIVE` and owned by this session (`HoldFacade.getActiveHold`).
2. Price the order **server-side** from `CatalogFacade.getTierSummary()`.
3. Insert (or reuse) an `orders` row with `status = PENDING` and `hold_token` **UNIQUE**.
4. Charge via `PaymentFacade.authorize(...)`.
5. On success, in **one** SQL transaction: `HoldFacade.consumeHold()`, flip the order to
   `CONFIRMED`, insert `order_items`, insert the `outbox_events` row.
6. On decline, the order stays `FAILED` and **the hold stays `ACTIVE`** so the user can retry.
7. If step 5 fails after a successful charge, auto-refund and mark the order `REFUNDED`.

**Why.** The original docs specified two mutually exclusive flows — an order-orchestrated one in the
`order` spec and a payment-orchestrated one (`PaymentSucceededEvent` → `order`) in the end-to-end
doc. They also disagreed on whether the hold is consumed before or after the charge.

Consuming before charging requires a `CONSUMED → RELEASED` transition that the hold state machine
does not permit, and it briefly releases inventory that the buyer is actively paying for. Charging
first and consuming inside the commit means a hold is only ever destroyed by a transaction that is
about to succeed.

**Replaces.** The `PaymentSucceededEvent → order` synchronous path. That event no longer exists on
the happy path (see ADR-005).

---

## ADR-002 — `UNIQUE(hold_token)` on `orders` is the single-use guard

**Decision.** `orders` gains `hold_token VARCHAR(64) NOT NULL UNIQUE`, plus
`payment_transaction_ref` and `stripe_payment_intent_id`. Checkout is *find-or-create* by
`hold_token`:

| Existing row state | Behaviour |
| :--- | :--- |
| none | insert `PENDING`, proceed |
| `PENDING` | `409` — a charge is already in flight |
| `FAILED` | reset to `PENDING`, retry on the **same** `order_number` |
| `CONFIRMED` | `200` — return the existing receipt (idempotent replay) |
| `REFUNDED` | `409` — terminal |

**Why.** The strongest possible overbooking guard is a database constraint, and it was missing. It
also gives the Stripe webhook something durable to correlate against, and it makes double-click
protection free.

---

## ADR-003 — "Settle-once claim" is the universal stock-restoration primitive

> ⚠️ **SUPERSEDED by [ADR-019](#adr-019--one-claim-in-postgresql--supersedes-adr-003).** The
> principle — restore exactly once, by whoever wins an atomic claim — still holds. The *mechanism*
> changed: the claim moved from Redis (`GETDEL holdmeta`) into PostgreSQL, because a Redis mutation
> inside the order transaction cannot roll back and leaked inventory. `holdmeta` no longer exists.
> Kept here for the record.

**Decision.** A held quantity is returned to stock exactly once, by whoever wins an atomic claim.

* **Phase 2+ (Redis):** every hold writes a companion key
  `holdmeta:{holdToken}` = `"{eventId}:{tierId}:{quantity}:{sessionId}"` with a 24 h TTL, written
  inside the same Lua script as the stock decrement. Consume, release, TTL-expiry and the
  reconciliation sweeper all begin with `GETDEL holdmeta:{holdToken}`. `GETDEL` is atomic: exactly
  one caller receives the value, everyone else receives `nil` and does nothing.
* **Phase 1 (PostgreSQL only):** the same semantics via
  `UPDATE ticket_holds SET status = ? WHERE hold_token = ? AND status = 'ACTIVE'` — restore stock
  only when the affected row count is `1`.

**Why.** Three separate bugs collapse into this one primitive:

1. Redis keyspace expiry is pub/sub. **All three app replicas receive `__keyevent@0__:expired`**, so
   the original design restored a 2-ticket hold three times.
2. The original expiry listener was told to read "the shadow backup record" — which was never
   defined anywhere. A key's Hash fields are already gone when its expiry event fires.
3. Nothing prevented the expiry listener and an in-flight `releaseHold` from both restoring.

**Replaces.** The Redisson lock on the sweeper path, which is no longer needed.

**Required Redis configuration.** `notify-keyspace-events Ex`. `E` is the key-*event* channel
(`__keyevent@0__:expired`, message = the key name); `K` is the keyspace channel and carries the
event name instead, so `Kx` would leave the listener permanently silent. Shipped in
[`../docker/redis/redis.conf`](../docker/redis/redis.conf).

---

## ADR-004 — Redis stock is never rebuilt from `total_capacity` during a live sale

**Decision.** While an event's window is `OPEN`, a missing `catalog:stock:{eventId}:{tierId}` key is
a **fault, not a cache miss**. The reserve script returns a distinct `-2` code, the API returns
`503`, and an alarm fires. Recovery is an explicit, locked rebuild:

```
remaining = ticket_tiers.total_capacity
          − SUM(order_items.quantity)  where orders.status = 'CONFIRMED'
          − SUM(ticket_holds.quantity) where status = 'ACTIVE' and expires_at > now()
```

Seeding from `total_capacity` via `SETNX` is legal **only** when the window is `UPCOMING`.

**Why.** The original catalog spec said a cache miss falls back to `total_capacity` and repopulates.
A Redis eviction, cold restart, or `FLUSHDB` mid-sale would have silently restored every sold
ticket. This was the highest-severity defect in the design.

**Supporting configuration.** `maxmemory-policy noeviction` (a `TTL = −1` key is *not* protected from
an LRU policy), AOF `appendfsync everysec`, and a mandatory reconciliation pass after any Redis
restart — `everysec` can lose up to one second of `DECRBY`s, which reads as extra inventory.

---

## ADR-005 — The facade graph is acyclic; the only cross-module event is the Stripe webhook

**Decision.**

```
filter ──► bot
hold   ──► queue, catalog
order  ──► hold, catalog, payment
payment ──(PaymentSettledEvent, webhook path only)──► order
order  ──(outbox → RabbitMQ)──► notification
```

`payment` no longer calls `HoldFacade` at all. Grace extension is requested by `order` before it
invokes payment; hold release on decline is unnecessary because the hold is deliberately retained.

**Why.** The original matrix had `payment → hold` plus a `PaymentSucceededEvent → order` edge, and
the corrected webhook path would have added `payment → order`, forming a cycle that Spring
Modulith's `ApplicationModules.verify()` rejects. Routing the async webhook through a domain event
keeps every synchronous edge one-directional.

---

## ADR-006 — Three nested timers, each with a hard ceiling

> ⚠️ **AMENDED by [ADR-020](#adr-020--three-tier-timer-model-add-the-admission-session--amends-adr-006).**
> A middle tier — the 600 s admission session — now sits between the pass and the hold, and the pass
> is revoked when it is exchanged for that session rather than at hold creation. Ceilings below are
> unchanged.

| Timer | Value | Single-use | Expiry behaviour |
| :--- | :--- | :--- | :--- |
| Queue pass (`queue:pass:{sid}`) | **120 s** | yes — revoked on exchange for an admission session | user returns to the queue |
| **Admission session** (ADR-020) | **600 s** | no | user returns to the queue |
| Seat hold (`hold:{token}`) | **300 s** | yes | stock restored via ADR-019 |
| Payment grace | **+120 s, once**, ceiling 420 s from hold creation | yes | hold expires normally |

**Why.** Three problems:

* The pass TTL was 300 s — identical to the hold TTL — and `QueueFacade.revokePassToken()` existed
  but **was never called by any flow**. One pass therefore granted unlimited holds for five minutes,
  letting a single promoted session drain a tier.
* `HoldFacade.extendHold()` was invoked by the end-to-end doc and listed in the facade matrix but
  **was not declared on the interface**, and the hold spec called the window "strict,
  non-extendable." Unbounded extension is free seat-squatting; hence the ceiling.
* An extension must also push `ticket_holds.expires_at`, or the sweeper reclaims the seat out from
  under an in-flight 3-D Secure challenge.

---

## ADR-007 — Queue promotion fans out over Redis Pub/Sub

**Decision.** The batch promoter publishes `{sessionId, passToken}` to `queue:events:{eventId}`.
Every replica subscribes and delivers to the `SseEmitter`s held in *its own* heap.
`GET /api/v1/queue/status` additionally returns the pass if `queue:pass:{sid}` exists, giving a
polling fallback and clean reconnect recovery.

**Why.** The promoter is a `@Scheduled` job that runs on one replica; the SSE connection lives in
another replica's memory. Behind round-robin Nginx across three replicas, roughly **two-thirds of
promotions would never reach the browser.**

**Supporting configuration.** `proxy_buffering off`, a long `proxy_read_timeout` on the SSE
location, a 15 s heartbeat comment frame, and `Last-Event-ID` reconnect support.

---

## ADR-008 — Admission control is bounded by real remaining capacity

**Decision.** Each promotion tick admits `N = min(batchSize, remainingStock − livePasses)`, where
`livePasses = ZCOUNT queue:passes:{eventId} now +inf`. When `remainingStock` reaches zero and no
holds remain, the queue emits a terminal `sale-exhausted` SSE event and drains.

Joining uses `ZADD NX` so a page refresh preserves the user's place, and a `queue:hb:{sid}`
heartbeat lets abandoned entries be evicted so wait estimates stay honest.

**Why.** Nothing stopped the promoter from admitting users into a sold-out sale — buyers would wait
twenty minutes to receive a `409 INSUFFICIENT_STOCK`. And plain `ZADD` *updates* an existing
member's score, so refreshing the page sent the user to the back of the line, contradicting the
FIFO fairness guarantee.

---

## ADR-009 — Hand-rolled `outbox_events`, not the Modulith Event Publication Registry

**Decision.** Keep the explicit `outbox_events` table. Remove the `spring-modulith-events-api`,
`-starter-jpa`, and `-events-amqp` dependencies. Keep `spring-modulith-starter-core` and
`-starter-test` so `ApplicationModules.verify()` mechanically enforces the boundaries these
documents assert.

Poll with `SELECT … WHERE status = 'PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100`,
and add `processed_at`, `retry_count`, `last_error` columns plus a purge job for `PROCESSED` rows
older than 7 days.

**Why.** Both mechanisms were on the classpath, which would have produced two competing outboxes and
an auto-created `event_publication` table nobody reasoned about. The explicit table gives full
control over payload shape and retry policy. Separately, three replicas each running a plain
`WHERE status = 'PENDING'` poll would publish every event three times — hence `SKIP LOCKED`.

**To reverse:** re-add `org.springframework.modulith:spring-modulith-starter-jpa` and
`spring-modulith-events-amqp`, then replace `OutboxPublisher` with
`@ApplicationModuleListener`.

---

## ADR-010 — Identity comes from a signed cookie, never from the request body

**Decision.** `bot` issues `fsid` as `HttpOnly; Secure; SameSite=Lax`, valued
`base64url(uuid) + "." + HMAC-SHA256(uuid, serverSecret)`. The filter verifies it and exposes it as
a request attribute. `userSessionId` is **removed** from `CreateHoldRequestDTO` and `X-Session-ID`
is removed from the hold-cancel endpoint.

`GET /api/v1/orders/{orderNumber}` requires a matching `fsid` **or** a signed `receiptToken` query
parameter (for the link in the confirmation email). `GET /api/v1/holds/{holdToken}` requires
ownership.

**Why.** Queue position, pass validity and hold ownership all key off `userSessionId`, and it was
accepted from a client-controlled body field. The public order lookup returned the buyer's email
address against a guessable `TK-98213` reference — an IDOR.

---

## ADR-011 — Session-first rate limiting; the IP bucket is a coarse backstop

**Decision.**

| Bucket | Limit | Notes |
| :--- | :--- | :--- |
| `bot:rate:session:{sid}` | 20 burst, 10/s refill | primary |
| `bot:rate:ip:{ip}` | 300 burst, 150/s refill | flood backstop only |
| `POST /queue/join` | 1 per session per event; 5/min per IP | |
| `GET /queue/stream` | counted once at connect | exempt from per-request accounting |

Bucket4j is **Redis-backed** in every phase.

**Why.** A 20-req/s-per-IP limit is fatal for carrier-grade NAT and corporate networks during
exactly the traffic spike this system exists to serve. And the docs contradicted themselves —
`01-system-architecture.md` said Bucket4j was in-memory while the `bot` spec said Redis-backed;
in-memory across three replicas silently triples every configured limit.

---

## ADR-012 — The webhook may not finalise an order whose seats are gone

**Decision.** The `payment_intent.succeeded` handler publishes `PaymentSettledEvent`. `order`
attempts `consumeHold`. If the hold has expired and the seats were re-sold, `order` issues an
automatic refund, sets `status = REFUNDED`, and writes a `REFUND_NOTICE` outbox event so the buyer
is told what happened.

**Why.** The original spec had the webhook unconditionally "complete the order in the background."
The hold can easily expire during the disconnect that made the webhook necessary in the first place,
so this path could charge a customer for inventory another buyer already owns.

---

## ADR-013 — Prices are computed server-side, always

**Decision.** `PaymentFacade.authorize()` receives an `orderNumber`; the amount is derived inside
`order` from `CatalogFacade.getTierSummary().priceCents × quantity`. No client input contributes to
the charge amount. `orders` and `payment_transactions` both carry a `currency` column.

**Why.** `POST /payments/intent` was `Public (With Hold Token)` and `PaymentFacade` took
`amountCents` from its caller — price tampering.

---

## ADR-014 — Payment idempotency is anchored to the hold, not to a client-chosen string

**Decision.** Three layers:

1. `UNIQUE(hold_token)` on `orders` — the durable guarantee (ADR-002).
2. `SETNX payment:inflight:{holdToken}` with a **90 s** TTL — fast duplicate-click shield.
3. The client's `idempotencyKey` is forwarded to Stripe as its `Idempotency-Key` header — nothing
   more.

At most **3** charge attempts per hold.

**Why.** The guard was keyed on a client-generated value, so a client that regenerated the key on
retry bypassed it entirely. A 24 h `IN_PROGRESS` TTL also meant an app crash mid-charge locked that
key for a day, and "return the stored result from the initial attempt" is impossible while the first
attempt is still in flight.

---

## ADR-015 — The outbox payload is a complete, self-contained snapshot

**Decision.** `OrderConfirmedEvent.payload` carries the event name, venue, event date, buyer email,
signed `receiptToken`, and an **array** of line items. `notification` makes no facade calls and
holds no reference to `catalog`.

`notification_logs` gains `kind` (`TICKET_DELIVERY` | `REFUND_NOTICE`) and a
`UNIQUE(order_number, kind)` constraint; the row is inserted as `PENDING` **before** the email is
sent, so the unique violation — not a prior `SELECT` — is what stops a duplicate.

**Why.** The consumer payload was flat (`tierName`, `quantity`), so a two-tier order would have
rendered a wrong PDF. It also needed `eventName`, which `notification` had no legal way to obtain.
And `notification_logs.order_number` was indexed but *not* unique, so two workers could both pass
the `SELECT`-based idempotency check and both send.

---

## ADR-016 — Sale windows are enforced, and the server owns the clock

**Decision.** `catalog` derives `windowStatus ∈ {UPCOMING, OPEN, CLOSED}` from `sale_start_time`,
`sale_end_time` and `events.status`. `GET /api/v1/events/{id}` returns `serverTime` alongside it.

| Action | Required window |
| :--- | :--- |
| `POST /queue/join` | `OPEN` |
| `POST /holds` | `OPEN` |
| `POST /orders/checkout` | `OPEN`, or `CLOSED` within 15 min of `sale_end_time` |

**Why.** `sale_start_time` and `sale_end_time` existed as columns that no endpoint ever checked.
And the landing-page countdown — step one of the user journey — appeared in no spec at all; without
a server-authoritative time, client clock skew smears the start of the sale.

---

## ADR-017 — Explicit inventory limits per session

| Limit | Value |
| :--- | :--- |
| Tickets per hold | 6 |
| Concurrent `ACTIVE` holds per session per event | 1 |
| Charge attempts per hold | 3 |

**Why.** `quantity` was constrained only by `CHECK (quantity > 0)`, and nothing capped concurrent
holds — so a single session could reserve an entire tier.

---

## ADR-018 — Redis topology: single primary + Sentinel, not Cluster

**Decision.** One Redis primary with a Sentinel-managed replica. Not Redis Cluster.

**Why.** `02-high-level-design.md` said "Redis 7 Cluster", but `hold_reserve.lua` touches
`catalog:stock:{e}:{t}` and `hold:{token}` — different hash slots, so the script would fail with
`CROSSSLOT`. Keyspace notifications are also per-node. This workload is a few hundred thousand ops/s
against a handful of keys; a single primary is nowhere near its ceiling, and Cluster would buy
complexity for no throughput.

*If Cluster ever becomes necessary*, hash-tag the hold keys as `hold:{e:t}:{token}` so every key a
script touches shares a slot.

---

# Second-pass decisions (ADR-019 – ADR-025)

> Produced by the Best Practice & Architecture Alignment Audit. ADR-019 **supersedes ADR-003** and
> ADR-020 **amends ADR-006**; both originals are kept for the record.

---

## ADR-019 — One claim, in PostgreSQL — *supersedes ADR-003*

**Decision.** `ticket_holds.status` in PostgreSQL is the **sole authority** for a hold's lifecycle.
Every terminal transition is one conditional statement:

```sql
UPDATE ticket_holds SET status = ?, settled_at = now(), settle_reason = ?
 WHERE hold_token = ? AND status = 'ACTIVE';     -- rowcount = 1 ⇒ you won the claim
```

Stock is restored only by the caller that gets `rowcount = 1`. Redis holds the **timer** and hot
metadata; it is never the authority.

Consequences:

* **`holdmeta:{holdToken}` and `GETDEL` are deleted.** They existed only because a Redis expiry
  event carries no payload — but if PostgreSQL is the authority, the expiry handler simply reads
  the row by token.
* **`consumeHold` runs inside the order transaction** and rolls back with it.
* **Redis cleanup moves after the commit**, via `@TransactionalEventListener(AFTER_COMMIT)`, and is
  explicitly best-effort.
* **Phase 1 and Phase 2 now use the identical mechanism.** Phase 1 already did.

**Why.** ADR-003 put the claim in Redis (`GETDEL holdmeta`), which meant `consumeHold` mutated Redis
*inside* the SQL transaction. Redis does not roll back. If the commit failed after a successful
consume, the claim ticket and the timer key were both gone, no order existed, and **the stock was
never restored** — those seats became permanently unsellable. A non-transactional side effect inside
a transactional block, and a silent inventory leak.

Moving the claim into the transaction removes the failure mode by construction rather than
compensating for it.

**Still correct across replicas.** All three receive the expiry event and all three run the
`UPDATE`; PostgreSQL row-locks and exactly one gets `rowcount = 1`.

**Cost.** One indexed `UPDATE` per settle instead of one `GETDEL`. We were already performing that
`UPDATE` for the audit row, so the true cost is zero. Expiry rate is bounded by hold-creation rate.

**Ordering guarantee.** If a concurrent expiry wins first, the order transaction's `UPDATE` returns
`rowcount = 0`, the transaction rolls back, and the already-settled charge is refunded via the
ADR-012 path. The grace extension (ADR-006) exists to make this rare.

---

## ADR-020 — Three-tier timer model: add the admission session — *amends ADR-006*

**Decision.** Insert a middle tier between the queue pass and the seat hold:

| Tier | Key | TTL | Single-use | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| 1. Queue pass | `queue:pass:{sid}` | 120 s | yes | proves you left the queue |
| 2. **Admission session** | `queue:admit:{eventId}:{sid}` | **600 s** | no | **you are inside the sale** |
| 3. Seat hold | `hold:{token}` | 300 s | yes | these seats are yours |

The pass is exchanged for an admission session at `POST /api/v1/queue/admit` and revoked there —
**not** at hold creation. `POST /holds` now requires a live *admission session*, not a pass. A hold
that is released or expires leaves the admission session intact, so the buyer can pick a different
tier without re-queueing.

Admission control counts sessions rather than passes, with an oversubscription factor:

```
pending    = ZCOUNT queue:passes:{eventId}     now +inf
admitted   = ZCOUNT queue:admissions:{eventId} now +inf
admittable = min(batchSize, floor(remainingStock × oversubscribeFactor) − pending − admitted)
```

`oversubscribeFactor` defaults to **1.5**: hold-to-order conversion is well under 100 %, so
admitting exactly `remainingStock` buyers under-fills the sale. Every real waiting room tunes this.

**Why.** Industry (Ticketmaster, AXS, Queue-it) runs three tiers; we had two. A buyer promoted at
t=0 had 120 seconds to choose a tier or return to the queue — but real buyers compare tiers, check
prices, and consult someone. The 1st pass surfaced this as "changing tier means re-entering the
queue, possibly too harsh"; it is not a harshness problem but a **missing concept**.

One addition fixes tier changes, browse time, the back button, refresh recovery, and gives the
promoter a materially better admission signal.

**Revocation.** The admission session is revoked when an order reaches `CONFIRMED`.

---

## ADR-021 — RFC 7807 `ProblemDetail`, per-module advice, and a shared kernel

**Decision.** All errors are `application/problem+json` per RFC 7807, with the fixed extension
schema and canonical code registry in [`05-global-standards.md`](05-global-standards.md) §1–§2.
**No `ApiResponse<T>` envelope.**

Each module owns a `@RestControllerAdvice` for its own exceptions; one global fallback advice lives
in a new shared kernel module, `com.flashseats.shared`, declared to Spring Modulith as an **open
module**.

**Why.** Seven module docs had each invented their own error shapes and code names
(`HOLD_EXPIRED_OR_INVALID`, `INSUFFICIENT_STOCK`, `BOT_VERIFICATION_FAILED`) with no shared
contract, so no frontend could switch on them reliably. Boot 4 supports `ProblemDetail` natively;
an envelope would fight HTTP semantics, break caching, and force a double unwrap on every client.

A shared kernel is *required*, not optional: error codes, session identity, and money types are
needed by all seven modules, and without a declared open module they would either be duplicated or
create cross-module dependencies that `ApplicationModules.verify()` rejects. §8 of the standards
document fixes what may and may not live there.

---

## ADR-022 — Drop Redisson; use PostgreSQL advisory locks

**Decision.** Remove the Redisson dependency. The stock-rebuild lock becomes
`pg_try_advisory_xact_lock(hash(eventId))`.

**Why.** After ADR-019 moved the hold claim into PostgreSQL, Redisson's only remaining use was one
lock on a rare admin path. That does not justify a dependency that ships its own Netty stack — and
its `synchronized`-heavy internals risk **pinning virtual threads** on JDK 21, which under a
flash-sale spike presents as a throughput collapse that looks like a Redis problem.

`pg_try_advisory_xact_lock` is transaction-scoped, released automatically on commit or rollback,
cannot leak, and needs no new dependency. The rebuild already reads PostgreSQL, so the lock lives
where the data does.

---

## ADR-023 — A SQL transaction may contain only SQL

**Decision.** No HTTP, SMTP, RabbitMQ, Redis write, PDF rendering, sleep, or retry inside
`@Transactional`. Three sanctioned patterns — external call before the transaction, side effects
via `AFTER_COMMIT`, and the outbox relay as **three short transactions** rather than one. Full rules
in [`05-global-standards.md`](05-global-standards.md) §4.

**Why.** A transaction holds row locks and a pooled connection, and under virtual threads the
connection pool is the system's real concurrency limit — one slow call inside a transaction
throttles everything. Two concrete violations existed: `consumeHold` mutating Redis inside the
order transaction (ADR-019), and an outbox poller that would have held `FOR UPDATE SKIP LOCKED`
locks across a RabbitMQ publish.

The relay's crash window between claim and mark re-publishes on the next sweep — at-least-once,
which the consumer's unique constraint absorbs (ADR-015).

---

## ADR-024 — Queue ordering is configurable; FIFO by default, randomized available

**Decision.** `flashseats.queue.ordering` takes `FIFO` (default) or `RANDOM`.

* `FIFO` — score is arrival epoch-millis. Intuitive, explicable, and correct.
* `RANDOM` — score is a uniform random draw taken at join time, seeded per event.

**Why.** Ticketmaster Verified Fan, SNKRS and DICE have largely abandoned arrival-order for
high-demand drops, because FIFO by arrival millisecond rewards whoever has the lowest network
latency and the most aggressive automation — everybody fires at `t = 0.000` and the winner is
decided by RTT, not intent. A randomized draw removes the thundering herd's advantage entirely and
is fairer for human buyers.

FIFO stays the default because it is easier to explain to users and to reason about while building.
The change is one line — the ZSET score — so this is a configuration decision, not an architecture
one.

---

## ADR-025 — `saleflow`: a read-only composition module

**Decision.** Add an eighth module, `com.flashseats.saleflow`. It owns **no storage and performs no
writes**. Its sole responsibility is `GET /api/v1/sale/{eventId}/state`, which aggregates
`QueueFacade`, `HoldFacade`, `OrderFacade` and `CatalogFacade` into one rehydration payload:

```json
{
  "windowStatus": "OPEN",
  "serverTime": "2026-08-30T10:04:12Z",
  "queue":     { "state": "ADMITTED", "position": null, "admissionExpiresAt": "…" },
  "hold":      { "holdToken": "hld_…", "tierId": 501, "quantity": 2, "expiresAt": "…" },
  "order":     { "orderNumber": "TK-98213", "status": "PENDING" }
}
```

Nothing depends on `saleflow`; `saleflow` depends on four facades. The graph stays acyclic.

**Why.** A tab reload at any point in the journey previously lost everything — there was no way for
the SPA to discover that this session is admitted, holds seats, and has a payment in flight. Every
production ticketing SPA calls exactly one state endpoint on mount.

It cannot live in an existing module: `queue` would need `HoldFacade`, but `hold → queue` already
exists, so that would create a cycle. A leaf composition module — depended on by nothing, depending
on many — is the standard Modulith answer and is architecturally cheap.

**Side benefit.** It gives `OrderFacade` its first real caller, resolving the YAGNI finding that
`OrderFacade`'s only stated consumers were tools that do not exist.

---

# Third-pass decisions (ADR-026 – ADR-030)

> Produced by the 360° edge-case and UX audit. ADR-026 fixes a defect that would have cost real
> buyers their place in line; ADR-030 resolves a conflict between the requested decline-retry
> behaviour and the grace ceiling in ADR-006.

---

## ADR-026 — The queue drains by promotion, never by eviction

**Decision.** The promotion worker **never** removes a live entry from `queue:waiting:{eventId}`
for a missing heartbeat. It promotes whoever is at the front, regardless.

`queue:hb:{sid}` becomes **advisory only** — used for the abandonment-rate metric, never for
eviction. Its TTL rises from 30 s to 90 s and it is refreshed by *any* request from that session,
not only by the SSE tick.

Wait estimates are computed from the **measured drain rate** (`ZCARD` delta over a sliding 30 s
window), not from `position × assumed-service-time`.

**Why.** This was a live defect. The previous rule was:

```
if not EXISTS queue:hb:{sid}: ZREM ; continue     -- abandoned
```

with a 30 s heartbeat TTL. A buyer who switches from Wi-Fi to cellular — the single most common
mobile event during a long wait — loses their SSE connection for 10–60 s while the handover
completes and the new connection establishes. **Any handover longer than 30 s silently deleted them
from the queue.** They would reconnect to find themselves not in line at all, having done nothing
wrong.

Not evicting costs nothing. An abandoned entry reaches the front, is promoted, never claims its
pass, and the pass expires in 120 s — capacity returns automatically, and the oversubscribe factor
(ADR-020) already accounts for non-conversion. The queue drains either way.

It also fixes wait estimates rather than harming them: measuring the real drain rate implicitly
accounts for abandoned entries, whereas eviction only ever approximated it.

---

## ADR-027 — Per-tier availability is pushed into the waiting room

**Decision.** `catalog` publishes `TierAvailabilityChangedEvent` when a tier crosses a bucket
boundary (`PLENTY` → `LIMITED` → `SOLD_OUT`). `queue` fans it out over the existing
`queue:events:{eventId}` channel as a `tier-availability` SSE frame:

```
event: tier-availability
data: {"tiers":[{"tierId":501,"level":"SOLD_OUT"},{"tierId":502,"level":"LIMITED"}]}
```

**Why.** ADR-008 stopped the queue admitting people into a *fully* sold-out sale, but said nothing
about a *partially* sold-out one. A buyer waiting twenty minutes specifically for VIP had no way to
learn that VIP went in the first ninety seconds — they discovered it only after admission, at seat
selection.

Telling them while they wait lets them decide to switch tiers or leave, which is both kinder and
better for throughput: they arrive at seat selection already knowing what they are buying.

Buckets, not exact counts (ADR-004 note): exact live inventory drives panic-buying and hands
scalpers a free feed.

---

## ADR-028 — Promotion batch size is derived from the connection pool

**Decision.**

```
promotionBatchSize ≤ hikariMaxPoolSize × 1.5
```

With the default pool of 30, batch size caps at **45 per tick** — not the previously documented 50.
Changing one without the other is a configuration error, and the two properties carry comments
saying so.

**Why.** "Ten thousand users reach the front simultaneously" is the scenario the queue exists for,
and admission control (ADR-020) bounds it by *inventory*, not by *capacity to serve*. Those are
different limits. A tier with 5,000 remaining would have admitted 5,000 buyers into a checkout path
backed by 30 database connections.

Under virtual threads this is especially easy to miss: nothing blocks, nothing errors, requests
simply queue on HikariCP and p99 latency collapses. `hikaricp_connections_pending` is the alarm
(standards §9).

---

## ADR-029 — Notification failures are classified before they are retried

**Decision.** The consumer classifies every failure before deciding to retry:

| Class | Examples | Action |
| :--- | :--- | :--- |
| **Transient** | SMTP timeout, broker blip, transient OOM | 3 retries: 5 s / 30 s / 2 min → DLQ |
| **Deterministic** | Thymeleaf charset/encoding failure, malformed payload, PDFBox font or glyph error, invalid recipient | **straight to DLQ, no retries** |
| **Poison** | `x-death` count ≥ 5 regardless of class | straight to DLQ, alarm |

**Why.** A Thymeleaf rendering exception on a particular character set — the case in the brief —
will fail identically on every attempt. Burning three retries over 2.5 minutes delays every other
message in the queue, produces three identical stack traces, and reaches the same DLQ. Worse, a
render failure that also corrupts consumer state can produce an infinite redelivery loop; the
`x-death` cap is the backstop against that.

Retry is for *transient* failures. This is the same rule the payment module already applies to card
declines (ADR-014), stated once for the async path.

---

## ADR-030 — The grace budget is per hold, not per payment attempt

**Decision.** A hold receives **one** +120 s extension across its entire lifetime, with an absolute
ceiling of 420 s from creation. It is granted **before the first charge attempt**. Retries after a
decline consume the remaining time; they do not earn a new extension.

If a retry is submitted with less than 45 s remaining, the API returns `409` with
`retryAfterSeconds: null` and `expiresAt`, and the UI tells the buyer plainly that there is not
enough time left rather than starting a charge that cannot be completed.

**Why.** The brief asked for "extend hold timer by 2 minutes" on decline, and up to 3 attempts are
allowed (ADR-014). Granting an extension per attempt yields 300 + 3×120 = **660 s**, blowing the
420 s ceiling and making seat-squatting cheap: three deliberate declines buy eleven minutes on
inventory during a flash sale.

The buyer's real need is met either way — a decline already retains the hold (ADR-011 in the payment
spec), and the single extension is granted *before* the first attempt, so the retry window exists.
What changes is that the budget is bounded and cannot be farmed.

---

# Implementation-pass decisions (ADR-031 – ADR-033)

> Produced while building the MVP. Each records a place where the design as written could not be
> implemented as written — a missing edge, or two rules that contradicted each other in practice.

---

## ADR-031 — `queue → catalog` is a real facade edge

**Decision.** Add `queue ──► catalog` to the facade graph. It was already required by two flows and
present in no diagram.

`queue` calls `CatalogFacade` in three places:

* `getWindowStatus(eventId)` — a join before the sale opens must be `409`, not a silent success
  (ADR-016).
* `getRemainingForEvent(eventId)` — admission control is bounded by real capacity, and without this
  the queue is only a slower way to deliver a `409` (ADR-008).
* `findOpenEventIds()` — the promotion worker needs to know which sales are live.

**Why it was missed.** `queue.md` §4 and §6 both describe these calls, but every graph — in
`01-system-architecture.md`, `03-end-to-end-flow.md`, `README.md` and `CLAUDE.md` — omitted the edge.
It is acyclic (`catalog` depends on nothing), so nothing was ever at risk; the diagrams were simply
incomplete, and a diagram that omits a real dependency is worse than no diagram.

The corrected graph:

```
                    shared          ← open module; everyone may depend on it

filter   ──► bot
queue    ──► catalog
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog        ← read-only leaf; nothing depends on it
payment  ──( PaymentSettledEvent — webhook path only )──► order
order    ──( outbox → RabbitMQ )──► notification
```

---

## ADR-032 — The promotion tick is a singleton by Redis lock, not a PostgreSQL advisory lock

**Decision.** `PromotionWorker` acquires `SET queue:promote:{eventId} <nodeId> NX PX 900` and skips
the tick if it cannot. The lock is never released explicitly; its TTL is shorter than the tick
interval, so it frees itself.

**Why.** Two documented rules contradict each other here, and one had to give:

* Global standards §7 says a `@Scheduled` job must be *idempotent by claim* or *singleton by
  `pg_try_advisory_xact_lock`*. The promotion worker is not idempotent — running it twice promotes
  twice — so it needs the lock.
* `pg_try_advisory_xact_lock` is **transaction-scoped**: it holds only while a transaction is open.
* Global standards §4 forbids Redis writes inside a SQL transaction — and this worker's entire body
  is Redis writes.

So a PostgreSQL advisory lock cannot guard this worker without violating the transaction rule. A
plain Redis `SET NX PX` needs no transaction, expires on its own, cannot leak if a replica dies
mid-tick, and adds no dependency.

**Not released deliberately.** Deleting a lock you may no longer own is how one replica frees
another's. Letting a short TTL expire is simpler and has no such failure mode. If a tick ever
overruns the TTL, two replicas may promote in the same second — bounded by the batch size, and
already absorbed by the 1.5× oversubscribe factor (ADR-020).

**Scope.** This changes the mechanism for *this* worker only. The sweeper and the outbox relay
remain idempotent-by-claim and need no lock at all.

---

## ADR-033 — One `@RestControllerAdvice`, via a shared exception base type

**Decision.** A single `GlobalExceptionHandler` in `shared` handles every module's failures. Each
module's exceptions extend `shared.error.FlashSeatsException`, which carries the module's own
`ErrorCode` and any RFC 7807 extension members.

**Why.** Global standards §1 asked for one advice per module, reasoning that a global advice *"would
have to import every module's exception types into one class and break the boundary
`ApplicationModules.verify()` enforces."*

That reasoning is sound, and the shared base type satisfies it: the handler catches
`FlashSeatsException` and imports nothing module-specific. The constraint is met with one class
instead of seven, and the response shape cannot drift between modules because there is only one
place that builds it.

**What is preserved.** The handler runs at `@Order(LOWEST_PRECEDENCE)`, so any module that later
needs bespoke handling can add its own advice and win. Error codes remain the module's own; only the
rendering is shared.

**Also recorded here:** two codes were added to the §2 registry during implementation, for behaviour
the docs specified but left un-named — `INSUFFICIENT_TIME_REMAINING` (409, `order`) for a retry with
too little of the hold left to finish safely (ADR-030), and `ORDER_REFUNDED` (409, `order`) for a
charge that settled against seats that could not be delivered (ADR-012). The latter is deliberately
distinct from `HOLD_EXPIRED`, whose promise is that *nothing was charged* — which would be false.

---

# Pass 1 — decisions the first review forced

ADR-034 to ADR-039 all come from the same review pass over the built MVP. Each records a defect
that shipped, and the rule that now prevents it. They are stated as rules on purpose: a rule can be
checked against new code, where a fix cannot.

---

## ADR-034 — A `PENDING` order is in-flight, never terminal

**Decision.** Every exit from checkout leaves the order in a state a retry can resume. Two rules,
because one is not enough:

1. Any throw past the find-or-create step marks the order `FAILED` — a state `findOrCreate` already
   knows how to resume, on the same order number. **No payment attempt is consumed:** a gateway
   outage is not one of the buyer's three tries at their card.
2. A `PENDING` row untouched for longer than `flashseats.order.stale-pending-seconds` is resumable
   regardless. That value tracks `flashseats.payment.inflight-ttl-seconds`, past which no charge can
   still be running.

**The defect.** `findOrCreate` commits the order as `PENDING` *before* charging, and answered every
subsequent request with `409 DUPLICATE_PAYMENT`. That is correct for a double-click and wrong for
everything else. A gateway error therefore produced:

```
POST /orders/checkout  →  503  "Your seats are still held — please retry."   retryable: true
POST /orders/checkout  →  409  "A payment for this reservation is already
                                being processed."                            retryable: false
```

The buyer held live seats, was told to retry, and could not — for the remaining five minutes of
their hold, on any card. The same dead end was reachable through `InsufficientTimeRemainingException`,
a grace extension lost to a concurrent expiry, and any transient database blip in that window.

**Why two rules.** Rule 1 handles everything that throws, immediately. It cannot handle a process
killed between the commit and the charge, because no handler runs — and that row is indistinguishable
from a live charge except by its age, which is what rule 2 reads. Rule 2 alone would work but would
make an instant gateway error cost the buyer a 90-second wait.

**Why not read the payment module's in-flight key.** It is the more precise signal, and `order` may
not touch a `payment:` Redis key. The property mirrors it instead; the comment on each says so.

**What is preserved.** A genuine double-click inside the staleness window still gets `409`. Global
standards §3 rule 4 — in-flight is `409`, never a wait — is unchanged; what changed is that
"in-flight" now has an end.

---

## ADR-035 — "No counter" is never "zero", and `EXHAUSTED` is derived, not destructive

**Decision.** Two rules over inventory reads in the waiting room:

1. `CatalogFacade.getRemainingForEvent` returns `COUNTER_UNAVAILABLE` if **any** tier of the event
   lacks a counter row. A `SUM` cannot express the difference between "nothing left" and "nothing
   known", so the aggregate answers with the fault.
2. Nothing deletes the waiting set. Exhaustion is a marker key that the promotion worker sets when
   stock is gone and **clears the moment stock returns**, so `EXHAUSTED` is a state derived from
   live inventory rather than an act performed on the queue.

**The defect.** ADR-004's failure mode, reproduced one module over. `PromotionWorker` guarded on
`remaining == COUNTER_UNAVAILABLE` — but the guard was unreachable, because `COALESCE(SUM(remaining), 0)`
over zero rows returns `0`. An event that opened without pre-warm therefore looked sold out, and the
worker's response to a sold-out sale was to publish `sale-exhausted` to every watcher **and delete
the waiting ZSET**. A missing row told an entire waiting room the sale had ended and then destroyed
their place in it. The dev seeder ships exactly such an event, one `sale_start_time` away.

**Why non-destructive matters independently.** Even when the read is correct, "sold out" is not
final: a released hold returns seats within seconds, and the sweeper reclaims abandoned ones every
ten. Deletion cannot be undone; a marker can. The narrow race where a hold outlives its admission —
possible because the admission TTL is 600 s and a hold may live 420 s from creation — used to drain
a queue that was about to have seats again.

**Consequence, accepted.** One tier without a counter pauses promotion for the whole event. That is
the conservative direction: admission is bounded by total remaining, and admitting on a number that
cannot be read is how a sale oversells. It fails loudly — `log.error` on every tick — rather than
silently, and it is fixed by pre-warming the tier.

---

## ADR-036 — The window is checked before the queue, and every queue key expires

**Decision.** Three rules in `queue`:

1. `getQueueState` resolves `CLOSED` **first**, before any Redis lookup. The precedence is then
   `CLOSED → ADMITTED → PROMOTED → EXHAUSTED → WAITING → NOT_JOINED`.
2. `QueueBroadcaster` sweeps the events **its own emitters are watching**, not the open-event list,
   and delivers a terminal frame to a connection whose sale has closed.
3. Every key this module owns is scoped by event and carries a TTL tied to the sale window. Sets
   scored by expiry are trimmed on read.

**The defects.** Three, all in the same place.

*The waiting room never ended.* `getQueueState` checked ZSET rank before the window, so a session
still in the line reported `WAITING` after the sale closed — and because both the promotion worker
and the broadcaster iterated only *open* events, nothing was left to tell them otherwise. Nothing
ever published `sale-closed`; the client listened for a frame that had no producer, and
`QueuePhase.EXHAUSTED` was never returned by anything either. Two dead branches and a frozen screen.

*The pass key was not event-scoped.* `queue:pass:{sessionId}` while admission was
`queue:admit:{eventId}:{sessionId}`. One visitor queueing for two concurrent sales had one promotion
overwrite the other: the second sale reported them `PROMOTED` holding a token its own `/admit`
refused on the signature check, with their real position hidden behind it.

*The sets grew without bound.* `queue:passes` and `queue:admissions` are scored by expiry so the
counts self-correct, but members were only removed on `admit` and `revokeAdmission`. An unclaimed
pass stayed a member for the life of the key, and the keys had no life. Redis runs `noeviction`
precisely so it never discards anything quietly, which makes an untrimmed key the one leak nothing
else cleans up.

**Why the broadcaster iterates emitters.** The connections are what need serving. Deriving the sweep
from the open-event list meant the moment a sale stopped being open, the buyers waiting for it
stopped being swept — the exact moment they most needed a frame.

**Why `CLOSED` outranks a pass.** A pass or admission is a claim on a sale that is still running.
When the window has closed there is nothing left to claim, and reporting anything else invites a
client to try.

---

## ADR-037 — Rehydration reports the latest order, whatever its status

**Decision.** `OrderFacade.findLatestOrder` returns this session's most recent order for an event
regardless of status. The client's router decides what each status means for the screen it draws.

**The defect.** The facade returned only `PENDING` orders, so a **confirmed** purchase was invisible
to `GET /sale/{eventId}/state`. After a successful checkout the hold is consumed and the admission
revoked, so all three sections came back null:

```json
{"queue":{"state":"NOT_JOINED"}, "hold":null, "order":null, "partial":[]}
```

A buyer who reloaded their receipt page was shown the landing page and invited to join the queue for
seats they already owned. The client's `sale.order.status === "CONFIRMED"` branch was unreachable.

**Why the facade and not the client.** `saleflow` makes no decisions (ADR-025), and "which statuses
count" is a decision. Reporting the fact and letting the consumer route on it keeps the leaf leaf-like.

**The client rule that goes with it.** The confirmed order sits **below** the queue states in
`route()`'s precedence, so a buyer who purchases and then rejoins for a second tier sees the queue
rather than being pinned on their old receipt. Full precedence in `FE_SPEC.md`.

---

## ADR-038 — A claim is released when the work did not happen

**Decision.** `NotificationLogService.claim` succeeds for a row that is dead-lettered, via a
conditional `UPDATE … WHERE status = 'DLQ'`. Both halves of the claim are now single statements whose
rowcount is the answer: `INSERT … ON CONFLICT DO NOTHING`, then the re-claim.

**The defect.** Insert-then-send is right — the unique violation is atomic where a preceding `SELECT`
is a race — but the claim was also *permanent*. A transient SMTP outage dead-lettered the message
**and kept its claim**, so replaying it from the DLQ found the row already present and acknowledged
without sending. The ticket was unrecoverable without an operator deleting a row by hand. ADR-029's
"do not retry a deterministic failure" is correct and unchanged; a transport failure is not one.

**A second defect, found while fixing the first.** The original `claim` caught
`DataIntegrityViolationException` and returned `false`. It could not: a flush that violates a
constraint marks the transaction rollback-only, so the `REQUIRES_NEW` boundary threw
`UnexpectedRollbackException` at commit and the consumer read that as a delivery failure. The
duplicate-suppression path — quietly acknowledge a redelivered message — never worked, and every
redelivery went to the DLQ. `ON CONFLICT DO NOTHING` returns a rowcount instead of throwing, which
also brings this claim into the same shape as every other one in the system.

**What is preserved.** `AND status = 'DLQ'` is what keeps it safe. A `PENDING` row — a send genuinely
in progress — or a `SENT` one is untouched, so this can never authorise a second delivery of a
message that worked. No buyer receives two tickets.

---

## ADR-039 — Tokens are domain-separated and secret-separated; defaults refuse to boot

**Decision.** Four rules covering the signed-capability surface:

1. `X-Forwarded-For` is honoured **only** from a peer in `flashseats.bot.trusted-proxies`, which is
   **empty by default**.
2. Every signed token declares a `kind`, which is length-prefixed into the signed bytes and not
   carried in the token.
3. The receipt secret is its own value (`FLASHSEATS_RECEIPT_SECRET`), and a receipt token carries an
   expiry and a nonce.
4. On any profile but `dev` and `test`, a default secret or admin password **stops startup**.

**The defect behind rule 1.** `RateLimitFilter` read the header directly, with no trust check, on
every deployment shape including one with no proxy at all. Any caller could rotate a fake address to
mint unlimited fresh IP buckets, or poison a real one. Since discarding the cookie also mints a
fresh session bucket, this left **no effective rate limiting whatsoever** for a cookie-less client —
while ADR-011 was explicitly relying on the IP bucket as the backstop that makes a deliberately
loose session bucket acceptable. Note that `server.forward-headers-strategy` does not help here: the
filter reads the header itself, so it does its own trust check.

**The defect behind rules 2 and 3.** `flashseats.order.receipt-secret` defaulted to
`${FLASHSEATS_SESSION_SECRET}`, so every deployment that set the session secret signed receipts with
the same key — and `SignedToken` had no domain separation, so what stopped a token of one type
verifying as another was payload formats happening not to collide. Receipt tokens were also
`sign(orderNumber)`: deterministic, unexpiring, and against sequential order numbers, derivable by
counting rather than by observation.

**Why length-prefixed rather than delimited.** A delimiter only works while no kind can contain it,
an invariant that lives in a comment. With a space, `("pass", "admit x")` and `("pass admit", "x")`
sign identical bytes — precisely the confusion domain separation exists to prevent. A length prefix
is unambiguous for any kind and any payload.

**Why startup fails rather than warns.** This failure is silent by nature: everything works
perfectly with a default secret, so nothing about a running system reveals the problem until someone
exploits it. A warning is a line in a log that a deploy scrolls past. `dev` and `test` are exempt
because the defaults are the point there — the stack must run from a clean checkout, and tests need
deterministic secrets so a token minted in one place verifies in another.

**Consequence, accepted.** `docker compose --profile cluster` runs the `docker` profile and therefore
refuses to start until real secrets are supplied. A three-replica run is closer to a deployment than
to a laptop demo, and `.env.example` says so with the command to generate them.

---

# Pass 2 — decisions the second review forced

ADR-040 to ADR-042 come from the second review pass over the built MVP. ADR-040 is the one that
mattered: it is ADR-004's failure mode surviving in the one place ADR-035 did not look.

---

## ADR-040 — An unreadable counter is `UNKNOWN`, never a bucket

**Decision.** `AvailabilityLevel` gains a fourth value, `UNKNOWN`, and it is not a bucket — it is
the absence of one. `AvailabilityBuckets.of` receives the raw counter value, fault code included,
and answers `UNKNOWN` for `COUNTER_UNAVAILABLE`. No caller may clamp the fault into a number.

The client renders `UNKNOWN` as "Checking…", in neutral colour, and **never disables the tier**.
`POST /holds` is what actually knows, and it already distinguishes `409 INSUFFICIENT_STOCK` from
`503 INVENTORY_UNAVAILABLE` correctly.

**The defect.** `CatalogService.toTierResponse` read the counter and passed
`Math.max(remaining, 0)` into the bucket rule. `COUNTER_UNAVAILABLE` is `-1`, so a tier with no
`tier_inventory` row was published to every visitor as `SOLD_OUT` — on the landing page, the first
surface anyone touches.

This is exactly ADR-004's prohibition ("a missing counter is a fault, never sold out") and exactly
the trap `CLAUDE.md` names. ADR-035 closed it in `getRemainingForEvent` for the promoter and in
`HoldService` for the reserve path; **the browse read was the third caller and it was missed.** The
dev seeder demonstrates it out of the box: *Midnight Sessions* ships un-warmed, so its only tier
reads `SOLD_OUT` on a sale that has not opened.

**Why the second-order effect is worse than the first.** The demo client sets
`aria-disabled="true"` on a `SOLD_OUT` tier. An admitted buyer facing a tier whose counter went
missing mid-sale could not click it — no error, no `503`, no retry copy, nothing to act on. A
Phase-2 Redis eviction lands here before it lands anywhere else.

**Why a fourth enum value rather than a flag.** "Sold out" and "unknown" are different facts about
inventory, and a three-value enum can only express the first. Anything that maps the second onto
the first is a lossy conversion at a call site — which is precisely what happened. Making the type
able to say it removes the whole class of mistake.

**Consequence, accepted.** `availability` is a wider contract: `FE_SPEC.md` §V1 gains a badge row.
An old client that switches on three values falls through to its default, which must not be
"sold out" — the spec now says so explicitly.

---

## ADR-041 — A `@RestControllerAdvice` that catches `Exception` must list what Spring throws first

**Decision.** `GlobalExceptionHandler` handles `MissingServletRequestParameterException`,
`MethodArgumentTypeMismatchException`, `MissingPathVariableException`,
`HttpRequestMethodNotSupportedException` and `HttpMediaTypeNotSupportedException` explicitly, ahead
of its `Exception` backstop.

**The defect.** `ExceptionHandlerExceptionResolver` runs **before**
`DefaultHandlerExceptionResolver`, and `spring.mvc.problemdetails.enabled` is not set. So the
`@ExceptionHandler(Exception.class)` backstop — added for genuinely unhandled faults — matched
every one of Spring's own binding exceptions first. A missing query parameter returned
**`500 INTERNAL_ERROR`**, logged a stack trace at `ERROR`, and carried no registry `code`.

Verified before the fix: `GET /api/v1/queue/status` with no `eventId` and
`GET /api/v1/events/not-a-number` both answered `500`.

Three things break at once. Global standards §1 says `500` is never a client error and every
problem carries a `code`. The SPA switches on `code`, so it fell through to a default that
re-enables the Pay button. And an `ERROR` log line per malformed request buries real faults.

**The general rule.** A catch-all advice is a backstop, not a router. Anything the framework raises
on the way to a handler must be named before it, or the backstop silently owns it.

---

## ADR-042 — `DLQ` means the work did not happen

**Decision.** `OrderConfirmedConsumer` tracks whether the message was actually delivered. A failure
*after* a successful send records `SENT`, never `DLQ`.

**Why.** ADR-038 made a dead-lettered notification re-claimable so a DLQ replay actually sends —
and that is right. But its safety argument rests entirely on `DLQ` meaning the send did not happen.
The consumer's single `catch` spanned `dispatcher.send`, `markSent` and `basicAck`, so a closed
channel after the mail server had already accepted the message marked the row `DLQ` — and the
replay would then send the buyer a **second ticket**, the one outcome ADR-038 states cannot occur.

The redelivery still arrives. It finds the row `SENT`, wins no claim, and is quietly acknowledged,
which is the path a duplicate is supposed to take.

**The rule this generalises.** A claim's terminal states must mean what the next reader assumes they
mean. `DLQ` is not "something went wrong"; it is "the work did not happen and may be retried".

---

# Post-MVP decisions (ADR-043 – ADR-045)

> Taken before the work starts rather than during it, because all three shape schemas and the module
> graph — and the cheapest moment to decide where an identity lives is before anything stores one.

---

## ADR-043 — The operator surface is a correctness dependency, not polish

**Decision.** Build the admin surface in Stage 4, and treat it as **required**, not additive. Admin
endpoints live in the module that owns the state, under `/api/v1/admin/**`, guarded by `ROLE_ADMIN`.
**There is no `admin` module.**

Minimum set, each in its owning module:

| Endpoint | Module | Why it is not optional |
| :--- | :--- | :--- |
| `POST /admin/events/{id}/pause` | `catalog` | There is currently no way to stop a sale that is going wrong |
| `POST /admin/events/{id}/rebuild-stock` | `catalog` | **ADR-004 names this as the only legal recovery** from a missing counter, and nothing exposes it |
| `GET /admin/notifications/dlq` | `notification` | A dead letter nobody can see is a lost ticket |
| `POST /admin/notifications/resend/{orderNumber}` | `notification` | **ADR-029's premise.** See below |
| `GET /admin/orders/{orderNumber}` | `order` | Support cannot answer "where is my ticket?" without it |

**Why this is a correctness dependency.** Two ADRs already assume an operator who can act, and
neither says so out loud:

* **ADR-029** routes deterministic failures *straight to the DLQ with no retries*. That is the right
  call — three identical stack traces help nobody — but it is only correct **if someone can replay
  the message.** Pass 2 found a PDF font failure that dead-lettered a **paid** buyer's ticket, and
  because no replay endpoint exists the DLQ was a black hole. ADR-038 went to real trouble to make a
  dead-lettered claim re-claimable *specifically so a replay would send*; nothing can currently
  trigger that replay.
* **ADR-004** forbids reseeding a live counter and points at a locked rebuild instead. The rebuild is
  specified in three documents and implemented nowhere, so the documented recovery from the system's
  worst failure is currently "edit the database by hand".

A design that deliberately routes failures somewhere is only finished when something can retrieve
them from there.

**Why there is no `admin` module.** An admin module would have to read `catalog`'s inventory,
`notification`'s logs and `order`'s ledger — every module's internals, which is exactly the boundary
violation `ApplicationModules.verify()` exists to reject. Ownership does not change because the
caller is an operator. `catalog` already does this correctly with `AdminCatalogController`.

**Auth.** The in-memory `UserDetailsService` (§10 S12) must be replaced before this surface grows.
One hardcoded account is tolerable for one pre-warm endpoint and is not tolerable for a surface that
can pause sales and resend tickets.

**An admin UI is not required.** Every endpoint above is usable with `curl` and belongs to whoever
is on call. A console is presentation; the endpoints are the capability, and only the capability is
load-bearing.

---

## ADR-044 — Buyer accounts are an overlay on session identity, never a replacement

**Decision.** Add an optional account in Stage 5. **`fsid` remains the only session identity, and
ADR-010 is unchanged.** An account is a second, *durable* identity that attaches to purchases —
never to queue position, hold ownership, or rate limiting.

```
fsid       — anonymous, signed, 24 h, per browser.   Queue · holds · rate limits · order access
accountId  — durable, authenticated, optional.       Purchase history · a stronger abuse bucket
```

New leaf module `com.flashseats.account`, depended on by `order` (to stamp a purchase) and
`saleflow` (to report who is signed in). It depends on nothing, so the graph stays acyclic:

```
order    ──► hold, catalog, payment, queue, account
saleflow ──► queue, hold, order, catalog, account
```

Binding happens at **checkout only**: if the session is authenticated, `orders.account_id` (nullable
FK) is stamped inside the existing transaction. Not at join, not at hold — those must keep working
for a visitor who has never signed in.

**Why an overlay rather than a replacement.** Three things break if an account becomes *the*
identity:

1. **The queue must serve anonymous visitors.** Ten thousand people arrive at `t=0`; putting an
   authentication round trip in front of `POST /queue/join` adds a dependency to the hottest path in
   the system for no correctness gain.
2. **ADR-010's guarantee is that identity has exactly one source.** What makes spoofing impossible
   is that nothing anywhere reads a session id from a body, header or parameter. A second identity
   source is a second thing to get wrong, and the two would have to be reconciled at every ownership
   check.
3. **Pre-sale browsing is anonymous by nature.** The `fsid` is minted on the landing page so a
   visitor has a stable identity *before* the sale opens. An account cannot be required that early.

**What it actually buys, concretely.**

* **Purchase history that outlives the cookie.** `flashseats.bot.cookie.max-age-seconds` is
  **86 400**, so `GET /orders/{n}` authorised by a matching `fsid` works for exactly one day. After
  that a buyer's only route to their own order is the signed `receiptToken` link in their email
  (ADR-010) — lose the email, lose the ticket. `FE_SPEC.md` §3 papers over this with
  `localStorage: fs.recentOrders`, which is a per-browser hint, not a record. An account is the
  first durable answer.
* **A rate-limit bucket that costs something to mint.** §10 S5 is explicit that the session bucket
  "does not constrain a determined attacker at all", because anyone can discard a cookie for a fresh
  one, leaving the deliberately loose IP bucket as the only backstop. A verified account is the
  compensating control that section has been waiting for — and it is what Verified-Fan-style drops
  actually gate on.
* **A support identity.** "Which of these orders is mine" is currently unanswerable without an order
  number.

**Anonymous purchases stay first class.** A buyer who never signs in must still complete the whole
journey and still receive their ticket. An account created later can **claim** an existing order by
presenting its `receiptToken` — the capability already exists and already proves possession, so
claiming needs no new mechanism.

**Whether to require login to enter the queue is a product decision, not a technical one.** The
architecture above supports either. Requiring it raises the cost of automation and lowers
conversion; the default is not to require it, and to revisit that with real abuse data rather than
in advance.

**Consequence, accepted.** §10 S9 stops being deferrable. Storing `orders.user_email` in clear with
no retention policy is one thing for an anonymous transaction and another once it hangs off a named
account: a deletion path and a stated retention period become mandatory, not advisable, and they are
part of this stage rather than after it.

---

## ADR-045 — `/actuator/health` is already the right shape; the gap is what it reports

**Decision.** No change to the actuator surface. `/actuator/health` stays public because it is the
container healthcheck target in `Dockerfile`, `compose.yaml` and `nginx.conf`; `metrics` and
`prometheus` stay behind `ROLE_ADMIN`. Recorded so a later pass does not "fix" either one.

**Why the split is deliberate.** A healthcheck an orchestrator cannot read is useless, so
`health` must be open. `metrics` and `prometheus` are the opposite: they describe inventory levels,
queue depth, order rates and connection-pool pressure — a live read on how the sale is going, and a
useful one to anyone attacking it. Exposing them without authentication was a defect fixed in Pass 0.

**The real gap is upstream of the endpoint.** The endpoint works; the metrics behind it are thin.
`flashseats.stock.drift` — the system's canary, the one alarm that should page — is asserted in
tests and **not exported**, because with a PostgreSQL counter it cannot diverge from itself. It
becomes a live metric the moment Redis holds the count, which is Stage 1. The rest of the alarm set
in global standards §9 lands with Stage 3.

So: health is done and needs nothing. **Observability is not done**, and it is already scheduled —
the thing to avoid is mistaking the first for the second because the endpoint returns `UP`.
