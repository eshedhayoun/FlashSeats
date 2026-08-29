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
