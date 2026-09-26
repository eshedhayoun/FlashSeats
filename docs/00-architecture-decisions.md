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

**Built in Pass 8, and the draw must stay a draw.** The first implementation scored by
`SHA-256(eventId + ":" + sessionId)`, for idempotency: a rejoin recomputes the same number. It is also
**precomputable**. Session ids are free to mint (§10 S5), so a bot generates candidates offline until
it holds a low draw and walks to the front deterministically — the exact advantage this ADR exists to
remove, restored in the mechanism meant to remove it.

A fresh uniform draw on every attempt is already idempotent, because `ZADD NX` discards the second
one: the place the first join won is the place that stands. The draw is bounded at 2^53 so it stays
exactly representable as the double a ZSET score is; anything larger collides after rounding and hands
two buyers the same position.

One consequence to weigh before RANDOM is switched on for a real sale: a later joiner can draw a lower
number, so a waiting buyer's true position can *worsen*, while `position-update` frames are clamped
monotonic non-increasing (ADR-007). Under FIFO the clamp hides nothing; under RANDOM it hides that.
Closing the draw at a fixed moment — a draw window rather than a rolling one — is the answer, and is
not built.

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

**Built in Pass 8 — as a diff in the broadcaster, not as an event.** `TierAvailabilityChangedEvent`
does not exist. Each replica reads the buckets once per sweep through `CatalogFacade`, compares them
with what it last sent, and broadcasts `tier-availability` to its own connections when they differ.

That is strictly simpler and it fits what the frame is for. A bucket crossing is *derived from the
counter*, exactly like exhaustion (ADR-035): publishing an event at the crossing means deciding which
write owns the transition, in a module where the same seat can move by reserve, restore, rebuild or
expiry. Reading the buckets where the frame is sent needs no such decision, costs one pipelined `MGET`
per event per sweep, and carries no cross-replica fan-out because every replica reaches the same answer
from the same counter.

What it gives up: a buyer who connects between two changes sees no availability frame until the next
one. The frame is advisory — the landing page and seat selection both carry the same buckets — so the
gap costs a waiting buyer information they can refresh for, not correctness.

The bucket crosses the facade as a string rather than `AvailabilityLevel`, because that enum lives in
`catalog.model` and no other module may read a module's `model` package. Moving it to
`catalog.facade`, where `EventWindowStatus` and `ReserveResult` already are, would make the contract
typed; it is a rename nothing else depends on and it has not been done.

---

## ADR-028 — Promotion batch size is derived from the connection pool

> **Amended by [ADR-049](#adr-049--admission-is-budgeted-globally-not-per-sale--amends-adr-028).**
> Everything below is correct **for a single sale**, which was the unstated assumption. The promotion
> worker applies this cap *per event* while the pool is shared, so at `E` concurrent sales the
> cluster admits `R × E × batchSize` per second. ADR-049 adds the global budget; this remains the
> per-sale cap.

**Decision.**

```
promotionBatchSize ≤ hikariMaxPoolSize × 1.5        # per sale
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

---

# Stage 1 — decisions the Redis fast path forced (ADR-046)

---

## ADR-046 — Redis is the counter, PostgreSQL is the ledger, and every Redis write fails toward under-counting

**Decision.** `catalog:stock:{eventId}:{tierId}` is the live inventory count. PostgreSQL holds the
record it can be derived from — `ticket_tiers.total_capacity`, `order_items` and `ticket_holds` —
and holds no copy of the count itself.

Because Redis cannot roll back with a SQL transaction, **every mutation of a counter sits outside
the transaction, and the ordering is chosen so that each failure loses seats rather than duplicating
them**:

| Operation | Order | If it fails half-way |
| :--- | :--- | :--- |
| Reserve | decrement, *then* write the `ticket_holds` row | seats decremented with no hold — invisible, drift alarms, rebuild returns them |
| Restore | win the settle claim, commit, *then* increment | seats never returned — invisible, same recovery |
| Rebuild | derive from the ledger, *then* overwrite | counters unchanged — the operator runs it again |

The asymmetry is the whole decision. **Invisible seats are lost revenue that a rebuild recovers;
phantom seats are an oversell that nothing recovers.** Anywhere the two orderings are both
defensible, choose the one that under-counts.

**Why the reserve is compensated rather than rolled back.** The decrement used to be a SQL `UPDATE`
sharing the hold's transaction, so a rejected insert rolled it back for free. It does not any more,
so `HoldService.createHold` catches the constraint violation and returns the seats explicitly. It
compensates **only** on that violation: a flush rejection is a definite rollback, while a failure at
*commit* is ambiguous — the row may exist, and returning seats that are still held is the oversell
this design refuses. Ambiguity falls through to drift. The path is not exotic; a buyer
double-clicking reaches it.

**Why restoration waits for the commit.** `HoldService.sweepExpired` settles up to 500 holds in one
transaction. An inline increment would return every earlier hold's seats and then, on any failure,
put those holds back to `ACTIVE` — on sale and still held at once. The restore moved to
`@TransactionalEventListener(AFTER_COMMIT)` on the settle event, with `fallbackExecution = true` so
a settle outside a transaction cannot silently discard it, and with its body wrapped so one failed
increment cannot abandon the rest of a batch.

**Five departures from what the module specs describe**, all of them recorded here rather than left
as drift:

1. **The scripts live in `catalog` and are executed by it.** `hold` moves stock by calling
   `CatalogFacade`, so it never touches the key. The "single exception" to key ownership that
   `CLAUDE.md` and `catalog.md` described is therefore **gone**, and with it the `CROSSSLOT`
   footnote in ADR-018. They are named `stock_reserve.lua` / `stock_restore.lua`, because they no
   longer touch a hold key.
2. **The reserve script does not write hold metadata.** `hold.md`'s version `HSET`s the hold's
   details alongside the decrement — `holdmeta` under another name, left over from ADR-003.
   ADR-019 deleted that authority, so nothing needs to be atomic with the decrement but the
   decrement.
3. **`tier_inventory` is dropped** (`V7__redis_inventory.sql`). It had become write-only: pre-warm
   and rebuild wrote it, nothing read it, and the rebuild derives its numbers from the ledger
   without consulting it. A write-only table named `tier_inventory` with a column named `remaining`
   reads exactly like the source of truth it is not. **This costs V1's `CHECK (remaining >= 0)`**,
   described there as the database-level guarantee that an oversell cannot be persisted; it guarded
   a number nobody read, and the guarantee now lives in `stock_reserve.lua` and is watched by
   `flashseats.stock.drift`.
4. **The rebuild counts *every* `ACTIVE` hold**, dropping `catalog.md`'s `expires_at > now()`
   filter. A hold past its expiry that the sweeper has not reached still owns its seats and will
   have them restored, so excluding it means nobody subtracts them now and the sweeper hands them
   back a moment later — the same seats counted twice.
5. **Browse degrades to `UNKNOWN`, not to "`tier_inventory` marked approximate."** With the table
   gone there is nothing to degrade to, and `UNKNOWN` is already ADR-040's answer: clients render it
   neutrally and keep the tier selectable.

**The rebuild reads the ledger twice.** A reserve decrements Redis a moment before its hold row
commits, so a single snapshot can miss a hold that is seconds from existing and write a count that
is too high — an oversell produced by the procedure meant to repair one. Two snapshots a settling
window apart, and the smaller of the two, make that impossible while still returning seats genuinely
abandoned, which read the same both times.

**It lives in `order`.** The ledger spans three modules' tables and `order` is the only module that
may read all three: it owns `order_items`, and `order → hold` and `order → catalog` already exist.
`catalog` would need its first outbound edge and the graph would cycle; one native query across the
other modules' tables would hide the same violation where `ApplicationModules.verify()` cannot see
it. The endpoint is therefore `POST /api/v1/admin/events/{id}/rebuild-stock` served by `order`,
which is the one place ADR-043's "endpoints live in the module that owns the state" bends — the
state here is nobody's alone.

**A Redis restart is the one inventory failure no ordering can prevent.** AOF is
`appendfsync everysec`, so a restart or a Sentinel failover replays to roughly a second ago: the
decrements in that second are gone while the `ticket_holds` rows that paid for them remain, and the
counters come back **high**. `catalog.md` has always said a rebuild is mandatory after a Redis
restart; `StockEpoch` is what makes that true rather than a note someone has to remember at three in
the morning. Each event carries its own vouched `run_id` in `catalog:vouch:{eventId}`, written only
by pre-warm and rebuild. An event vouched for by a different instance than the one running refuses
holds with `503 INVENTORY_UNAVAILABLE` until it is rebuilt.

**Per event, in Redis, recomputed each tick** — all three matter. Per event, so repairing one sale
vouches for nothing else and a sale created after the restart is never in doubt. In Redis, so every
replica reaches the same verdict and a rebuild performed on one releases the event on all of them.
Recomputed rather than accumulated, so noticing the restart does not consume it: an earlier cut kept
one in-memory flag and stamped a single shared key, which meant whichever replica noticed first used
the signal up and its neighbours went on selling from the same rolled-back counters.

**What is deliberately *not* in this stage.** The `hold:{token}` TTL key and the
`__keyevent@0__:expired` listener. They are a latency optimisation — `HoldReconciliationSweeper`
already reclaims every expired hold and is unchanged — and the one thing needing proof about them,
that three replicas receiving the same broadcast expiry restore a hold exactly once, cannot be
observed on one instance. They move to Stage 3 with the rest of the multi-replica work.
`HoldFacade.discardTimer` stays the no-op it has always been.

---

## ADR-047 — Proving the cluster: what a load harness must simulate, and what Stage 3 deliberately did not build

**Status:** Accepted (Stage 3)
**Supersedes:** nothing. **Amends:** the Phase 4 scope in `04-implementation-roadmap.md`.

### Context

Stage 3 was scoped "infrastructure only — Nginx, Sentinel, k6 — no application code", on the
reasoning that every module was already built and the stage's job was to *run existing code under
new conditions*. That reasoning held. The scope did not, until four things were fixed that nobody
had listed, because none of them can be seen from one instance.

The stage's real deliverable is evidence for one claim: **promotion fan-out works across replicas**
(ADR-007). It is the highest-value unverified claim in the system, it is correct by construction and
invisible on a single instance, and a naive implementation drops roughly two-thirds of promotions on
three replicas.

### Decision 1 — The proxy needs a fixed address, because the trust check takes addresses

`RateLimitService.isTrustedProxy` is exact-address set membership. ADR-039 chose that over a CIDR
parser deliberately: this is the single check that decides whether the rate limiter can be bypassed,
and a range parser is a good place for a subtle bug to hide.

The consequence had not been drawn. An exact address means the proxy must *have* a fixed one, and a
compose bridge assigns them dynamically. So the network carries an explicit subnet and **nginx is
pinned to `172.28.0.10`**, which `FLASHSEATS_TRUSTED_PROXIES` names.

Left unpinned, the failure is silent and total: the header is ignored, every request appears to come
from nginx, and all ten thousand buyers share one IP bucket of capacity 300 refilling at 150/s.

### Decision 2 — `proxy_set_header` replaces the inherited set; the shared headers are an include

**This one had already broken the cluster, and nothing could have noticed.** nginx does not merge
`proxy_set_header` — a directive at one level discards the entire set inherited from above. The
five proxy headers were declared once in the `server` block, and the three locations that added
their own `proxy_set_header Connection ""` — the SSE stream, `/api/`, and the webhook — silently
dropped all five.

Two consequences, and the first hid the second:

- With no `Host`, nginx sends `$proxy_host`, which is the upstream name `flashseats_app`. Tomcat
  rejects it — *"The character [_] is never valid in a domain name"* — so **every proxied API
  request answered a bare HTML 400** before Spring ever saw it. Not a `ProblemDetail`, not a `code`
  from the §2 registry: a Tomcat error page, because the request died below the application.
- With no `X-Forwarded-For`, Decision 1 would have achieved nothing anyway. The address the rate
  limiter needs was never being sent on the API path.

The set therefore lives in `docker/nginx/proxy-headers.conf` and is `include`d by every location
that proxies. Repeating it inline would work and would invite the same defect back the next time
someone adds a location.

### Decision 3 — The load harness must send a per-VU `X-Forwarded-For`

k6 runs as **one container**, so all ten thousand virtual users share one source address and
therefore one IP bucket. Against a bucket of 300 refilling at 150/s, a ten-thousand-user run
measures `flashseats.bot.ip-bucket` and nothing else.

So `flash-sale.js` sends each VU its own synthetic `X-Forwarded-For`. **This is the faithful
simulation, not a bypass of one.** Ten thousand real buyers arrive from thousands of addresses; one
container pretending to be ten thousand people from one address is the unrealistic case. It works
because nginx sets `$proxy_add_x_forwarded_for`, which appends rather than replaces, and the filter
reads `split(",")[0]`. The session bucket — ADR-011's *primary* control — is untouched and still
applies per VU.

### Decision 4 — The load-test sale is a reserved id, seeded in SQL and pre-warmed over HTTP

`CatalogDevSeeder` is `@Profile("dev")` and the cluster runs `docker`, so the cluster starts with an
empty catalog and there is no create-event endpoint. Widening the profile was rejected: it would put
demo-data seeding into the profile the packaged image runs, which is what ADR-039 had just finished
removing.

The sale is therefore seeded in SQL (`docker/seed/seed.sql`) as **event 9001**, and its counters are
written by `POST /admin/events/{id}/prewarm` — the only sanctioned path, and the reason the seeded
window is `UPCOMING`: pre-warm refuses anything else (ADR-004), because seeding `total_capacity`
into an open sale is precisely the failure that rule names.

The id is reserved rather than `1` because the postgres volume outlives any run. An earlier draft
seeded id 1 with `ON CONFLICT DO NOTHING`, found the dev seeder's event already there, silently did
nothing, and pointed the whole load test at a 700-seat sale while asserting against a capacity of
500. Seeding also resets its own event — in PostgreSQL *and* in Redis — because a second run against
a drained counter sells nothing and proves nothing.

### Decision 5 — Sentinel is deferred, and the reason is not cost

Sentinel is named in Phase 4 and is **not** built here. No Phase 4 exit criterion needs failover;
they need fan-out, no-oversell, p99, drift and fulfilment. More importantly it works *against* the
criterion "restarting Redis mid-sale → reconciliation restores the exact count", which is ADR-046's
vouch guard and is cleanest to observe against a standalone Redis that simply stops and starts.

Sentinel does not weaken that guard — it makes it matter more, since a failover to a replica that
lost a second of `DECRBY`s is exactly the case `catalog:vouch:{e}` exists to catch. That is an
argument for building Sentinel *after* the guard has been proven by hand, not before.

### Consequences

- Three replicas, one nginx, `--profile cluster`: **30/30 sessions promoted across all 3 replicas**.
  ADR-007's Pub/Sub fan-out is verified rather than asserted. `docker/scripts/fanout-check.sh`
  re-runs it in seconds and fails loudly if a future change breaks it.
- A load run is deterministic and repeatable: seed, run, re-seed, run again.
- `docker/scripts/sse-cadence.sh` measures what `QueueBroadcaster`'s sweep actually costs, from the
  client side, so the decision to batch its per-connection reads is gated on a number rather than on
  the arithmetic in §9.
- The `hold:{token}` timer and the keyspace-expiry listener remain deferred. Stage 3 built the rig
  that can prove them; it did not build them.

---

## ADR-048 — The operator surface, and the two guarantees Stage 3 left behind

**Status:** Accepted (Stage 4)
**Amends:** ADR-039 (how the admin credential is stored), ADR-046 (which events the vouch guard
covers). **Completes:** ADR-029 and ADR-038, both of which assumed this surface.

### Context

ADR-029 sends deterministic failures straight to the dead-letter queue **with no retries**. That is
right — a render that fails once fails identically three times and only delays the queue — and it is
right **only if someone can replay them**. ADR-038 then went to real trouble making a dead-lettered
claim re-claimable *so that* a replay would send. Nothing could list a dead letter and nothing could
trigger a replay, so the DLQ was a black hole; pass 2 found a paid buyer's ticket in it, lost to a
font that could not draw a Hebrew event title.

Two items also arrived here from Stage 3 — RabbitMQ publisher confirms, and the `hold:{token}` timer
— having been deferred out of two stages in a row.

### Decision 1 — A confirm is not a delivery, and a confirm alone is not enough either

`rabbit.send()` returns when the frame reaches the socket. The relay read that as delivery, so a
broker that accepted the bytes and died before persisting them lost the message with the outbox row
already `PROCESSED`.

Publisher confirms fix that. **They do not fix the other half**: a confirm means *the broker has this
message*, not *a queue has this message*. An exchange with no matching binding acknowledges happily
and discards — and the entire notification topology sits behind
`flashseats.notification.enabled`, so a deployment with it off everywhere would confirm every ticket
into nothing. `publisher-returns` plus `template.mandatory` turns that into a failure, and the
publisher treats a return exactly like a nack. This is the case a "just switch confirms on" change
would have missed entirely, and `RabbitOutboxPublisherTest` pins it.

**`OutboxPublisher` became batch-shaped** — `List<UUID> publish(List<OutboxEvent>)`. Confirms are
asynchronous and per-message, so a one-at-a-time interface forces the caller to block on each in
turn: a batch of a hundred against a sick broker is a hundred sequential timeouts on the relay
thread. Sending the batch and awaiting it once bounds that to a single timeout, and makes partial
success a **return value rather than an exception**. Nothing is lost when a confirm does not arrive:
the row stays `PROCESSING`, the stale-claim sweep returns it, and the consumer's
`UNIQUE(order_number, kind)` absorbs the duplicate.

### Decision 2 — The hold timer accelerates expiry; it never decides it

`hold` gains its first Redis key. `HoldReconciliationSweeper` is unchanged and is still the
guarantee — keyspace notifications are at-most-once pub/sub and a restarting replica loses them
permanently — so the timer only removes the up-to-10s that abandoned seats spend invisible. Measured
at 339 ms against the cluster.

Two properties make it safe, and **neither is the event**:

- **It is a hint, not a verdict.** `HoldService.reclaimExpired` re-reads the row and settles only
  what the sweeper would have settled anyway. `grantGrace` moves a hold's expiry in PostgreSQL, so a
  key treated as authoritative would fire mid-payment and return seats a buyer is being charged for.
  A hold found alive has its timer **re-armed**, which keeps grace-extended holds on the fast path
  instead of silently falling back to the sweeper for the rest of their life.
- **The claim is what makes it exactly-once.** All three replicas receive the expiry, all three reach
  `UPDATE ... WHERE status = 'ACTIVE'`, exactly one wins. Restoring stock in the listener would
  return the seats three times — invisible on one instance, which is why
  `docker/scripts/hold-expiry-check.sh` asserts against the cluster that the counter never rises
  past its starting value.

`__keyevent@0__:expired` is one channel for the whole database — queue passes, admissions, payment
guards, rate-limit buckets — with no server-side filter, so the `hold:` prefix check is the
listener's first statement.

### Decision 3 — A resend lives where the payload lives, which is `order`

A resend needs the original **payload**; `notification_logs` records that a delivery was attempted,
never what was in it. The durable copy is `outbox_events.payload`, owned by `order`. So
`POST /admin/notifications/resend/{orderNumber}` is served by `order` and writes a **new outbox
row** — the same split `rebuild-stock` makes, where the path names what the operator is thinking
about and the module is where the state lives (ADR-043).

The existing pipeline then does everything: the relay publishes, and
`NotificationLogService.claim` already falls through `claimIfAbsent` to `reclaimDeadLettered`. **No
DLQ draining, no shovel, no new publish path, and no new facade edge.** The alternatives were worse:
draining the AMQP dead-letter queue to find one message means re-enqueueing everything that does not
match, and having `notification` ask `order` for the payload would add a facade edge across an
asynchronous boundary that exists precisely so the two can deploy independently.

**Resending something that already worked is safe by construction**, and that is what makes the
endpoint usable by someone who cannot tell whether the first attempt landed: a `SENT` row is not
`DLQ`, the re-claim matches nothing, and the consumer acknowledges without sending. Verified against
the cluster — a second resend queued a message and delivered no second ticket.

Bounded by `flashseats.outbox.purge-after-days`. Past that the payload is gone and the answer is
`410 NOTIFICATION_PAYLOAD_UNAVAILABLE` rather than a reconstructed message: rebuilding one from the
current catalog would render a ticket for the event as it is *now*, not as it was sold.

### Decision 4 — `PAUSED` is a publication state, and it splits the event query four ways

`EventStatus.PAUSED`. `SaleWindows.statusOf` already reads anything but `PUBLISHED` as `CLOSED`, so
every gate — queue join, hold, checkout — shuts with **no change to `SaleWindows`**. That is the
reason pause is a publication state rather than a fourth `EventWindowStatus`: a new window status
would have to be handled correctly by every consumer, and the one that forgot would be a sale still
selling while an operator believed it stopped. Nothing is destroyed — the waiting room keeps every
position and stock stays put — so resuming returns every buyer exactly where they were, which is
ADR-035's reasoning about never deleting a waiting room.

The real work was that `findOpenEventIds` had **three** callers wanting two different answers:

| Caller | A paused event is… | Why |
| :--- | :--- | :--- |
| `PromotionWorker` | excluded | that is what pause means |
| `CatalogService.list` | excluded | it is not on sale |
| `StockReconciliationService.measureDrift` | **included** | pausing is what an operator does *while* investigating a counter; losing the gauge then takes the instrument from the person using it |
| **`StockEpoch`** | **included** | a paused event whose counters a Redis restart rolled back must be flagged *now*, not when someone resumes — which is to say, not once it has started selling from them |

Hence `findManagedEventIds`. Reusing `findOpenEventIds` for all four is the easy version and the
wrong one; the drill in §9 restarts Redis against a paused sale and confirms all three replicas
still flag it.

### Decision 5 — The admin credential is hashed, not replaced

§10's S12 asks for the in-memory `UserDetailsService` to be replaced *"before anyone else needs
access."* Nobody does. A table, a migration and a user-management surface to administer one row is
machinery guarding nothing, and the day a second operator or an audit trail of who paused a sale is
wanted, that one bean is what changes — which is the property worth keeping.

What was genuinely wrong is that the credential was stored and compared in **plaintext**. It is now
a `DelegatingPasswordEncoder` value naming its own algorithm, and **`SecretsGuard` refuses any
`{noop}` value outside `dev`/`test`** rather than the one literal string `admin` — strictly
stronger, since the old check passed `hunter2` and stored it in the clear.

**401 and 403 now carry a registry `code`.** Spring Security throws inside the filter chain, before
`DispatcherServlet`, so no `@RestControllerAdvice` could ever see it and Boot's stock body came back:
these were the only endpoints in the API answering without a code, on the surface that can pause a
live sale. `AdminProblemResponses` writes RFC 7807 at the entry point and keeps the RFC 7235
`WWW-Authenticate` challenge that replacing the entry point would otherwise have dropped.

### Consequences

- The module graph is **unchanged**. Every endpoint writes only state its own module owns.
- The `.env` admin password is now a digest and cannot authenticate. `gen-env.sh` prints the
  plaintext once; scripts read it from `FLASHSEATS_ADMIN_PLAINTEXT`.
- `flashseats.outbox.transport` is bound on `OutboxProperties` instead of being a loose string.
- Still deferred: the refund-notice template and a consumer for
  `notification.order-refunded.queue`, which still grows without bound.

---

## ADR-049 — Admission is budgeted globally, not per sale — *amends ADR-028*

> **Built in Pass 8.** `queue:budget` is the allowance, claimed once per event per tick before anyone
> is promoted. Three things the build settled, each below in **Construction**: the allowance is a
> single key whose own TTL is the window, the open-event order is **shuffled** every tick, and the
> transaction count this ADR's budget is derived from is **nine**, not eight.

**Context.** ADR-028 caps the promotion batch at `hikariMaxPoolSize × 1.5` — 45 with the default
pool of 30 — and calls that "capacity to serve". The derivation is correct and the assumption it
rests on was never written down: **one sale at a time.**

`PromotionWorker.tick()` iterates `catalog.findOpenEventIds()` and calls `promote(eventId)` for each,
applying the batch cap **per event**. The tick lock `queue:promote:{e}` is per event as well, so
nothing stops three replicas from promoting three different sales in the same second. The connection
pool they all admit into is shared.

```
admissions/second  =  R replicas × E events × batchSize
                   =  3 × 5 × 45   =  675
connections        =  R × 30       =   90
```

With the operating envelope now at **E = 3..10 concurrent sales** (03-end-to-end-flow §2), ADR-028's
limit is exceeded by roughly the factor `E` — and exceeded silently, because under virtual threads
nothing errors. Requests queue on HikariCP and p99 collapses, which is precisely the failure ADR-028
was written to prevent, arriving through the door it left open.

**A second error in the same arithmetic.** ADR-028 implicitly prices an admitted buyer at about one
connection. A checkout is **eight sequential transactions** — `findConfirmedReceiptFor`,
`getActiveHold`, `getTierSummary`, `findOrCreate`, `grantGrace`, the payment store, `confirm`,
`receiptFor` — and a full session costs roughly fifteen including `POST /holds` and
`GET /sale/{id}/state`. The per-event cap was already generous for E = 1.

**Decision.**

1. **A cluster-wide admission budget, spent per tick, shared across every open sale.** It lives in
   Redis so all replicas draw on one pool, and it is the *first* limit applied.
2. **The per-event batch size stays** as a secondary cap, so one hot sale cannot consume the whole
   budget and starve the others.
3. **The budget is derived from the true per-buyer connection cost**, not from one connection per
   buyer. Both numbers are properties, and both carry the derivation in a comment — the same
   discipline ADR-028 established.
4. **ADR-028's formula is not deleted**; it is re-scoped. It remains the correct per-sale cap and is
   now explicitly labelled as such.

**Why a global budget rather than a smaller per-event one.** Dividing the batch by `E` would be
simpler and is wrong in both directions: with one open sale it throttles the system to a tenth of
what it can serve, and `E` changes whenever an operator publishes an event. A budget that is spent
tracks actual demand — a quiet sale returns what it does not use, in the same tick.

**Why not just enlarge the pool.** The pool is sized against PostgreSQL's `max_connections` (200 in
compose) and against what one PostgreSQL instance actually serves well. Admitting more buyers than
the database can serve moves the queue from the waiting room — where it is visible, ordered, fair and
has a position indicator — into HikariCP, where it is none of those things. The waiting room is the
correct place to hold people, and that is the whole thesis of the system.

**Consequences.**

- `flashseats.queue.promotion-batch-size` keeps its meaning and its ADR-028 comment, gaining a
  pointer here.
- One new Redis key, owned by `queue`, event-independent, and therefore the first key in the system
  that is deliberately *not* scoped by event — the ADR-036 rule is about per-buyer keys, and this is
  a cluster-wide counter.
- `hikaricp_connections_pending` stays the alarm, and now has a drill: five sales at once
  (`docs/06-mvp-overview.md` §8).
- The existing single-sale load results remain valid for what they measured. They simply do not
  generalise, and §9 says so.

**Construction.**

**The allowance is one key, and its own TTL is the window.** `queue:budget`, claimed by one Lua
script: read what is spent, grant no more than what is left, `SET … PX interval` when the key is
absent and `INCRBY` when it is not. One round trip and atomic, so two replicas promoting two
different sales in the same second cannot both read the allowance as untouched.

The first version derived a window id from each replica's own clock (`epochMillis / interval`) and
spent it with `INCRBY` plus a compensating `DECRBY` of the overage. The arithmetic held — each claimer
refunds exactly its own unused amount — but it cost three round trips and it made the replicas' clocks
load-bearing: skew puts two replicas in adjacent windows, each with a full allowance. Anchoring the
window to the key removes the clock from the question entirely.

**The open-event order is shuffled every tick, and that is what makes the allowance fair.** Every
replica reads `findOpenEventIds()` in the same ascending order and claims as it reaches each sale, so
a fixed order lets the lowest event id take the whole allowance every second — at `E = 5`, one sale
draining and four frozen. Decision 2's per-event cap does not save it: once the cluster allowance is
smaller than `promotion-batch-size`, the per-event cap never binds at all. A shuffle gives every sale
an equal chance of being served first while still letting a busy sale use what its quiet neighbours
did not claim. Dividing the allowance by `E` is still rejected, for the reason already given.

**It is claimed against demand, not against the cap.** The worker asks for the number of sessions
actually at the front of that sale's queue, so a sale with three people waiting asks for three and
the rest stays available — "a quiet sale returns what it does not use, in the same tick", literally.

**It fails closed.** A script failure promotes nobody that tick and the next tick retries. The waiting
room is correctness-neutral: a late promotion costs a second of someone's patience, while promoting
without an allowance is the defect this ADR exists to prevent.

**Two new counters, because the limit is otherwise invisible.**
`flashseats.queue.admissions` and `flashseats.queue.admission.budget.denied`. The new failure mode is
*admitted too slowly*, which looks exactly like *nobody waiting* from the outside — and the allowance
is a number somebody has to be able to tune.

**A correction this ADR's own arithmetic depends on.** A checkout is **nine** sequential
transactions, not eight: every listing collapses `PaymentTransactionStore`'s two `REQUIRES_NEW`
transactions — the pair that brackets the gateway call, and which its javadoc describes as two — into
"the payment store".

**And decision 3 was wrong, which the measurement found.** "Derive the budget from the true per-buyer
connection cost" became `globalAdmissionConnectionBudget / databaseConnectionsPerBuyer` = 90 ÷ 8 = 11 —
and **those units do not compose.** 90 is a *concurrency* (connections the cluster holds at one instant);
8 is a *count* (transactions one buyer issues over a session lasting minutes). Their quotient is neither,
and it was then spent as a **rate**, per tick, per second.

The error is invisible in the algebra and obvious in the instruments. At 11 per tick:
`hikaricp_connections_pending` peaked at **10 of 90** while
`flashseats.queue.admission.budget.denied` logged **ten refusals for every admission granted**, and five
500-seat sales sold **76 %** rather than selling out. The budget meant to stop the pool being the
bottleneck had made itself the bottleneck, at a tenth of the pool's capacity.

**So the two properties are replaced by one — `global-admission-budget-per-tick`, a rate, default 45.**
That is ADR-028's `hikariMax × 1.5` re-scoped from one sale to the whole cluster, which keeps the
lineage this ADR set out to correct without inventing a second derivation.

**The general point is worth more than the number.** A capacity limit expressed as a formula over
quantities that do not share units will look derived and be arbitrary. The honest form is a rate with a
measured ceiling, and the ceiling is observable: raise it until
`hikaricp_connections_pending` stops returning to zero. `denied` staying high while the pool sits idle
means there is room; `pending` refusing to drain means there is not.

---

## ADR-050 — A ticket is retrievable, not only deliverable

**Context.** The PDF ticket is rendered inside `OrderConfirmedConsumer` and handed straight to the
mail dispatcher. `GET /orders/{orderNumber}` returns `OrderReceiptResponse` — JSON. **There is no
endpoint anywhere that returns the ticket.**

The buyer's email address is collected once, in the checkout body, and never verified. A typo
therefore means:

- the ticket is delivered to a stranger, or bounces;
- the buyer holds a valid receipt and a 90-day `receiptToken` and still has no way to obtain the
  thing they paid for;
- and the operator resend path (`POST /admin/notifications/resend/{orderNumber}`) replays the **same
  outbox payload**, so it re-sends to the same wrong address.

Every layer of this system is built so a failure has a recovery path. This one has none, and it ends
with a paying buyer holding nothing.

**Decision.** `GET /api/v1/orders/{orderNumber}/ticket.pdf`, authorised **exactly like the receipt
read it sits beside**: a matching `fsid` session cookie **or** a valid `receiptToken`. The order
number alone authorises nothing (ADR-010). No new authorisation concept is introduced.

**Placement, which is the only hard part.** `TicketPdfRenderer` lives in `notification`, and
`order → notification` is currently outbox-only with **no facade edge at all**. Adding one would
make `notification` the first module reachable both synchronously and asynchronously, for a page
render.

**`TicketPdfRenderer` moves to `shared`.** It is a pure function from a payload to bytes — no
repository, no facade, no state — which is precisely what the shared kernel is for (standards §8).
Both `order` and `notification` then render from one implementation, no new edge appears, and the
guarantee that a resent ticket is byte-identical to the original becomes structural rather than
coincidental.

The alternative — serving the endpoint from `notification`, keyed on the notification log row — was
rejected: it gives a module with no buyer-facing surface its first public endpoint, and it makes the
ticket unavailable exactly when fulfilment is broken, which is the case the endpoint exists for.

**Consequences.**

- Email verification is still not solved, and this does not pretend to solve it. It makes the failure
  *recoverable* rather than terminal, which is the correct first move.
- The renderer's ADR-042 property — that operator-supplied text outside WinAnsi must not reach a
  standard-14 font — moves with it, and its test moves with it too.
- A future "resend to a corrected address" operator action becomes a small change rather than a new
  subsystem, because the buyer already has a path that does not depend on email at all.

---

## ADR-051 — Event and tier metadata are cached; the sale window is still derived

**Context.** Nothing in this system was cached. `events` changes only when an operator pauses or
resumes a sale and `ticket_tiers` never changes after creation, yet every window check, event
summary, tier summary and tier-id lookup was its own PostgreSQL transaction — on the landing page,
the queue-status poll, the SSE sweep, the rehydration endpoint and the promotion tick.

At one sale that was invisible. At the `E = 3..10` envelope (ADR-049) it is the first thing to
exhaust the connection pool, and it is *polling* traffic, which scales with waiting buyers rather
than with admitted ones. The Pass 7 drill measured 202 connections pending against a pool of 30.

**Decision.** `CatalogMetadata` holds two in-process caches — one event row, one tier list, both keyed
by event id. Five rules make it safe, and every one of them is a defect that was written first.

1. **Rows, not summaries, and never the window status.** The cache holds immutable `EventRow` /
   `TierRow` records; `EventWindowStatus` is derived from the row and the clock on every call.
   A cached window status is wrong the moment the clock crosses a boundary, and there is no write to
   evict on — the one kind of staleness nothing can detect.
2. **Every entry expires, and the TTL *is* the cross-replica invalidation.** Eviction reaches only
   the replica that served the operator's call. Without a TTL, a paused sale answers `OPEN` on the
   other two replicas for the life of the process — and `getWindowStatus` and `getTierSummary` gate
   queue join, hold creation and checkout, so *pause stops nothing*. ADR-043 calls the operator
   surface a correctness dependency; a cache without an expiry makes it a no-op. Events: 1 s. Tiers:
   60 s, because they are immutable and the TTL is only there for rows the seed SQL inserts.
3. **Loads happen outside the map.** `get`, then load, then `put` — never `computeIfAbsent`, which
   runs the loader inside `ConcurrentHashMap`'s per-bin monitor. Blocking JDBC inside `synchronized`
   **pins carrier threads** on JDK 21 (invariant 11, and why Redisson was removed in ADR-022); on a
   cold key at sale open, thousands of virtual threads converge on one bin and pin every carrier at
   once. A cache added to stop a stall would have introduced a worse one. Two threads racing the same
   miss both query and both write the same answer: one wasted query, no correctness cost.
4. **A miss is never cached.** Events and tiers are inserted straight into PostgreSQL by
   `docker/seed/*.sql` and by the dev seeder, so a remembered "no such event" outlives the insert
   that created it.
5. **Recovery paths read the authority.** `prewarm` and `getTierCapacities` call `tiersUncached`.
   Both write inventory counters derived from that list: a stale list leaves a tier with no counter,
   which answers `503` for the rest of the sale (ADR-004), or makes a rebuild write counters derived
   from the wrong set of tiers (ADR-046). A list that is merely probably right produces a counter that
   is definitely wrong.

**Eviction is published, not performed.** `setPaused` publishes `EventMetadataChanged` inside its
transaction and the eviction runs `AFTER_COMMIT` (ADR-023's shape, one module over). Evicting inline
leaves a window in which a concurrent reader re-caches the row the transaction is about to change —
and the entry it writes is *fresh*, so with rule 2's TTL the operator's pause is merely delayed, and
without it the pause would fail on the replica that served it.

**Why not a cluster-wide invalidation channel.** `catalog` already owns a Redis prefix and could
publish an invalidation. With a 1 s event TTL it would buy a fraction of a second on a control an
operator measures in seconds, and it would add a failure mode — a missed message — that no TTL-bounded
cache has. It becomes worth building when operators can *edit* an event, which no endpoint allows
today (`catalog.md` §6).

**The tests run with it enabled, and that is a decision.** The first version disabled caching in
`application-test.properties` because the fixture truncates with `RESTART IDENTITY`, so ids come back
as `1` and a surviving entry describes the previous test's sale. That leaves the configuration
production actually runs with no coverage at all. Instead `SaleFixture.reset()` clears every
`DerivedStateCache` — a one-method interface in `shared`, which exists because a test fixture
importing `catalog.service` is precisely the boundary violation `ApplicationModules.verify()` catches,
and it does not care that the caller is a test.

**Consequences.**

- On a hit, `getWindowStatus`, `getEventSummary`, `getTierSummary` and the landing page take **no
  pooled connection at all**; those wrappers are no longer `@Transactional`.
- `SaleWindows.statusOf` takes an `EventRow`. One mapping from entity to snapshot, one derivation of
  the window — the property its javadoc already claimed.
- `flashseats.catalog.metadata.cache{result=hit|miss}` is how the effect is read, and
  `metadata-cache-enabled` is the switch that makes the concurrent-sales drill a comparison rather
  than an assertion.
- ~~`findOpenEventIds`, `findManagedEventIds` and `listEvents` are deliberately **not** cached. They
  are clock-parameterised, and their cost is `O(replicas × ticks)` — about four queries per second
  cluster-wide — not `O(requests)`.~~ **Reversed the same day; see below.**

**Amendment — the three list reads are cached after all, for availability rather than cost.**

The reasoning above is arithmetically right and asks the wrong question. Cost was never the issue: it
really is about four queries a second cluster-wide. **Dependency** was the issue.

`PromotionWorker.tick()` called `findOpenEventIds()` every second, which needed a pooled connection.
So under pool pressure the promoter queued *behind the buyers it existed to admit*. The Pass 8 drill
caught it: `Unable to acquire JDBC Connection … timed out after 16068ms (total=30, active=30, idle=0,
waiting=63)` — **a sixteen-second wait inside a one-second tick.** A tick that does not run promotes
nobody; a waiting room that does not drain keeps polling; polling is what saturated the pool. That is a
feedback loop, and the component whose whole job is to bound admission was inside it.

It explains the measurement that made no sense otherwise: the allowance permitted ~2,300 admissions
over the run and only **352** happened. The allowance was never the binding limit — the tick was
running roughly once every six to thirteen seconds instead of once a second.

**So one query — every `PUBLISHED` or `PAUSED` event, any window — is cached under the event TTL, and
all three list reads filter it in memory.** The promotion tick now needs **no** pooled connection on its
hot path: the event row, the tier list and the open-event set are all cache reads, and the only thing
left is Redis. Two details keep it honest:

- **The window is still derived.** Only the rows are remembered; `saleStartTime <= now < saleEndTime`
  is evaluated against the live clock, exactly as rule 1 requires of the status.
- **`StockEpoch` keeps querying PostgreSQL directly.** It is the Redis-restart guard, it runs every
  five seconds, and a fault detector should not read a cache. Its query is now also the surviving SQL
  definition of the window that the in-memory filter must match.

**The general rule this is an instance of:** a cache is usually an optimisation, but in front of a
control-plane read it is an *availability* decision. Ask not only what the read costs, but what else
stops working when it is slow.

---

## ADR-052 — Stripe goes behind the seam that was already there; the breaker is a decorator

**Context.** The MVP shipped `PaymentGateway` with one implementation, a stub, in the final position
in the checkout sequence. Every idempotency layer around it was real from day one — `UNIQUE(hold_token)`,
`SETNX payment:inflight:{holdToken}`, the two `REQUIRES_NEW` transactions bracketing the network call,
find-or-create, the three-attempt ceiling, the compensating refund. **Only the gateway was fiction.**

**Decision.** `StripePaymentGateway` implements the same interface, server-confirming PaymentIntents:
the client keeps sending a `pm_...` and the server charges it. `PaymentGatewayConfig` builds exactly
one `PaymentGateway` bean — Stripe when `flashseats.payment.stripe.enabled`, the stub otherwise —
wrapped in `CircuitBreakingGateway`.

**Why server-confirm rather than Payment Element.** Payment Element has the browser confirm and makes
the webhook the *primary* settlement path. That inverts ADR-001's charge-then-consume ordering, and the
synchronous `402`/`409` contract that `FE_SPEC` §2, the client's error table and half this document
describe would become mostly dead code. The flow the whole system was built around is the one kept.

**Why one assembled bean rather than `@ConditionalOnMissingBean`.** That condition was a good seam
while there was one real implementation and one stub. With a provider, a stub *and* a decorator all
implementing the interface, "whichever bean exists" stops being a seam and becomes an ambiguity.

**Why the stub survives, and is the default.** `enabled` is false unless a deployment says otherwise,
so a clean checkout, the whole test suite, the load harness and every drill run the complete buyer
journey — decline, provider outage, 3-D Secure — with no keys and no network. A stub that were deleted
on the day the real thing arrived would take the only deterministic coverage of those branches with it.
Its token vocabulary is deliberately the provider's own (`pm_card_authenticationRequired`,
`pm_card_chargeDeclined` alongside the older `pm_card_declined`), so one script drives either gateway.

**Why the breaker is a decorator and counts only `GatewayTransportException`.** Resilience4j's Spring
Boot starter targets Boot 3, so the plain artifacts are declared and one `CircuitBreaker` bean is built
by hand. More importantly, the breaker must distinguish two things that an AOP-driven one cannot:

| Provider says | Shape | Counted? |
| :--- | :--- | :--- |
| card refused | returned `GatewayResult.DECLINED` | **no** |
| unreachable, 5xx, rate-limited | thrown `GatewayTransportException` | **yes** |

A decline is a *correct answer*. A breaker that counted declines would open during an ordinary burst
of expired cards — which during a flash sale is the normal state of the world, not a signal — and take
a perfectly healthy sale's payments down with it. What the breaker exists to stop is ten thousand
queued buyers each waiting out a 20-second read timeout against a provider that is already down,
holding a pooled connection apiece, which under virtual threads is the system's real concurrency limit.

An open breaker and a transport failure leave by the same door, `GatewayResult.error`, which the
existing `ERROR → PAYMENT_GATEWAY_UNAVAILABLE → 503` path already handles: seats retained, **no payment
attempt consumed**, exactly what `05-global-standards.md` §2 already promised for that code. Nothing
above `CircuitBreakingGateway` changed to accommodate the breaker.

`maxNetworkRetries(0)`: the provider's own client-side retry would be a second retry mechanism, and
this system already has one — re-POSTing the same checkout body.

---

## ADR-053 — A webhook delivery is a claim, and a claim is released when its work did not happen

**Context.** The charge settles and the buyer never sees the response — a dropped connection, a killed
replica, a closed laptop. The money moved and nothing in this system knows it. The provider's webhook
is the only remaining witness, and ADR-005 reserved `payment → order` as the one cross-module event
precisely for it. Until now nothing traversed that edge.

**Decision.** `POST /api/v1/payments/webhook` verifies, claims, settles, and reports:

1. **Verify the signature over the raw bytes.** The endpoint is unauthenticated by necessity — the
   provider cannot hold a session — so this is the only gate. The body is bound as a `String`, never a
   DTO: the signature is over the bytes, not the meaning, and letting Jackson re-serialise an
   equivalent object changes key order and whitespace and fails every legitimate delivery.
   `WEBHOOK_SIGNATURE_INVALID` finally has a thrower.
2. **Acknowledge every other event type with `200`.** A non-2xx asks for a redelivery of something we
   will go on ignoring for ever.
3. **Claim it.** `INSERT … ON CONFLICT (stripe_event_id) DO NOTHING`, rowcount as the answer — the same
   shape as `notification_logs`, and never an insert whose exception is caught, which marks the
   transaction rollback-only so the `catch` block's `return` throws at commit (ADR-038).
4. **Publish `PaymentSettledEvent` synchronously.** The contract of the endpoint is that a failed
   settlement becomes a non-2xx; an asynchronous listener's failure cannot be reported to the provider.
   A plain `@EventListener`, not `@ApplicationModuleListener` — that needs the Modulith event-publication
   registry, removed in ADR-009.
5. **Release the claim if settlement throws**, then rethrow. Otherwise the redelivery is dismissed as a
   duplicate and the buyer's charge never reaches an order. `processed_at IS NULL` therefore means *in
   flight*, never *failed*: a failure leaves no row at all.

**`order` settles it, and may refuse** (ADR-012, now reachable for the first time). The listener finds
the order by `hold_token` — carried to the provider as metadata and back — and:

| Order state | Action |
| :--- | :--- |
| `CONFIRMED` / `REFUNDED` | nothing; the synchronous path or an earlier delivery already resolved it |
| `PENDING` / `FAILED`, hold still claimable | `OrderCommitService.confirm` — the same transaction the synchronous path uses, so the `ORDER_CONFIRMED` outbox row and the ticket follow identically |
| hold gone | refund, `REFUNDED`, `ORDER_REFUNDED` outbox row |

The hold can easily have expired during exactly the disconnect that made the webhook necessary, and
another buyer may own those seats by now. Confirming anyway would charge one customer for inventory
another already holds.

**The endpoint is not exempt from rate limiting.** An exempt endpoint is an unmetered one
(`docs/modules/bot.md` §6), and the IP bucket sits orders of magnitude above any delivery rate.

**A refund that fails is no longer recorded as a refund.** The pre-existing compensation discarded
`RefundResult`, so a provider that refused still produced an order marked `REFUNDED` and an email
telling the buyer their money was on its way — money this business holds and should not, described to
the only person who would notice as already returned. Both call sites now share `OrderRefundService`,
which writes the failure into `failure_reason` and increments `flashseats.payment.refund.failed`.
**Alarm on any non-zero value:** each one is money owed to a named buyer.

---

## ADR-054 — 3-D Secure resumes the existing intent; there is no resume endpoint

**Context.** Three documents disagreed. `03-end-to-end-flow.md` §5 and `06-mvp-overview.md` §11 both
specified `POST /api/v1/orders/checkout/resume`; `FE_SPEC.md` §2 said flatly that it does not exist,
is not needed, and that **re-POSTing the same body is the retry**. FE_SPEC is the client contract and
it is right: a second retry path would need its own idempotency story, and this system's whole
guarantee is that there is exactly one.

**Decision.** A challenge is a *pause in one checkout*, not a second checkout.

- The gateway returns `REQUIRES_ACTION` with the intent id **and** a `clientSecret`.
- `CheckoutService` throws `PaymentActionRequiredException` → `402 PAYMENT_ACTION_REQUIRED` carrying
  `clientSecret` and `expiresAt`. (`05-global-standards.md` §2 said "follow `resumeUrl`" — a field that
  existed nowhere; it now names the real contract.)
- The client runs `stripe.handleNextAction(clientSecret)` and **re-POSTs the same body**.
- `PaymentService` then looks for a `PROCESSING` ledger row for that hold and, finding one,
  **retrieves that intent instead of charging**.

**Three things already in place do the work**, which is why the server-side change is three lines:

1. The throw lands in `CheckoutService`'s existing catch-all → `markAbandoned` → order `FAILED`,
   resumable by find-or-create on the same order number, **no payment attempt consumed** (ADR-034).
   A bank challenge is not one of the buyer's three cards.
2. `payment:inflight` is released in a `finally` regardless, so the buyer is not locked out of their
   own challenge.
3. The `+120 s` grace was granted *before* the charge, so the challenge window is already paid for
   (ADR-006, ADR-030). No new extension is granted, and none is needed.

**Why retrieving is mandatory, not an optimisation.** The client mints one `idempotencyKey` per hold
and reuses it on every retry (`FE_SPEC` §1). A second `charge` would therefore replay the provider's
cached `requires_action` response **for ever**, and the buyer could never complete. Varying the key per
attempt instead would create a *second* intent — so the buyer authenticates one payment and is billed
for two. Retrieving the existing intent is the only correct shape, and `PaymentStatus.PROCESSING`,
declared on day one and never written because the stub could not reach it, is what marks the row.

**Accepted consequence.** While a challenge is outstanding, a *different* card in the body changes
nothing: the resume re-reads the pending intent. A buyer who abandons the challenge cannot switch cards
until the hold expires, which is minutes. The alternative is opening a second charge against a hold that
already has one in flight, and that trade is not close. A *failed* challenge is different and resolves
itself: the provider moves the intent to `requires_payment_method`, the retrieve returns `DECLINED`, the
row goes `FAILED`, and the next attempt charges fresh.

---

## ADR-055 — Bot defence fails open, and its rules are never read from the database on the request path

**Context.** `06-mvp-overview.md` §10 S5 has said the same thing since Pass 1: **session identity is
free to mint.** ADR-011 makes the per-session bucket the primary rate-limit control, and anyone can
discard a cookie to get a fresh one; the IP bucket is deliberately loose (300 burst) so that
carrier-grade NAT populations are not blocked during exactly the spike this system exists to serve.
ADR-039 made that backstop real by refusing `X-Forwarded-For` from untrusted peers, but the
conclusion stood: **the session bucket does not constrain a determined attacker at all.** The named
compensating control was a challenge on join, and it was deferred for four passes.

Until now this module wrote no tables, had no operator surface, and read a `recaptchaToken` field
that `queue` had accepted and ignored since day one.

**Decision.** Three things, and a rule that governs all of them.

**1. reCAPTCHA v3 on `POST /queue/join`, failing open.** Join is the one place a challenge is worth
its cost: it is the front of the line, it is cheap to repeat, and everything after it is already
gated by a queue pass and an admission the server issued. `queue ──► bot` is a new facade edge and
cannot make the graph cyclic — `bot` depends on nothing but `shared`.

Only a score the provider *actively returns* below `min-score` refuses a request. An unconfigured
secret, a missing token, a timeout, a non-2xx and a malformed body all allow it. **Failing open is
the decision, not a fallback**: a challenge provider's outage must not close a sale that ten thousand
people are waiting for, and it would arrive at peak load, because that is when the provider is
busiest too. Every degraded verification is audited — failing open is invisible from the outside, and
"our bot defence was off for three hours" must not be learned afterwards from an absence.

The provider timeouts (1 s connect, 2 s read) are therefore **correctness settings**. This call sits
on the join path, and a default-timeout client there turns a provider slowdown into a sale-length
outage — reintroducing the exact failure that failing open exists to prevent, through the client that
implements it.

**2. `ip_rules`, held in memory and re-read on a timer.** This is consulted on *every* API request.
A per-request query would put the rate limiter — whose entire job is keeping load off the system —
inside the connection pool it is protecting, queued behind the buyers it is shielding. That is
ADR-051's trap ("a job that protects a resource by reading that resource") with the pool as the
resource and the filter as the job, and it is the second time this repository has walked toward it.

ADR-051's three rules apply unchanged: **the TTL is the cross-replica invalidation** (an operator's
call evicts one replica; the others follow within 10 s), **the load happens outside every monitor**
(one `AtomicReference` swapped after the read — blocking JDBC inside a `ConcurrentHashMap` bin pins
carrier threads on JDK 21), and **expiry is derived from the clock on read, never baked into the
snapshot** — a rule that lapses between reloads has to stop applying at its expiry, not at the
cache's. A fourth is specific to this table: a failed reload **keeps the previous snapshot**, because
the database being briefly unreachable must neither unblock every address nor block every address.

An `ALLOW` rule exempts **the IP bucket only**, never the session bucket. It is for a known shared
egress where hundreds of real buyers share one address; it is not a statement that the traffic is
trusted.

**3. `bot_audit_logs`, asynchronous and non-`ALLOWED` only.** There is no `ALLOWED` outcome and there
must not be one — a row per allowed request is a write per request during precisely the traffic this
system is built for, and it would make the table unreadable for the purpose it exists to serve.
Writes go to a bounded queue with a **discard** policy: every row here is written on a path that has
just refused someone, and the caller is very often an attacker, so a synchronous insert would let
them convert their own `429`s into database load at whatever rate they can generate requests. If the
audit trail cannot keep up, the right outcome is to lose audit rows. Evidence is not worth an outage.

**The migration is `V11`, not `V6`.** Earlier documents called for `V6__bot.sql`. `V6` has been
`V6__pass1_corrections.sql` in every database that has ever run this schema, and Flyway checksums the
whole file — renumbering would refuse to start every container with a checksum mismatch.

**What this does and does not close.** S5's compensating control now exists, but
`flashseats.bot.recaptcha.secret` is blank in `dev`, `test` and a clean checkout, which means
verification is **off** by default. That is deliberate — the stack must run from a clean checkout with
no configuration — and it means the control is only real where someone sets the secret. ADR-044's
verified buyer accounts remain the other route to the same problem: an account is a rate-limit bucket
that costs something to mint, where a discarded cookie costs nothing.

---

## ADR-056 — Compensation requires a definite failure; a cache in front of a failing dependency must back off

**Context.** Reviewing Stage 2 against the conditions it will actually meet — a rolled-back
transaction, a dropped connection, one actor retrying hard, a sale with many buyers at once — turned
up four defects that a green suite could not see, because **none of them is an error**. Two of them
are instances of rules this repository had already written down for other components, reappearing in
new ones. That is what makes them worth an ADR rather than a commit message.

**Decision 1 — money moves on facts, not on exceptions.**

`PaymentSettlementService` caught `RuntimeException` around the whole settlement and refunded. So a
pool timeout, an `InventoryUnavailableException` (a 503 *fault*, ADR-004) or any commit blip refunded
a buyer whose seats were perfectly fine — and then answered the provider `200`, so nothing ever
redelivered and the mistake was permanent.

Only the three **definite** outcomes may compensate: `HoldNotFoundException`, `HoldExpiredException`,
`HoldAlreadySettledException`. Each is the hold module stating a fact. Everything else propagates,
which releases the webhook claim and earns a redelivery — the only outcome that can still come out
right.

This is ADR-046's rule reaching money. There it was inventory: *a constraint rejection is a definite
rollback and safe to compensate; a failure at commit is ambiguous, and returning seats that may still
be held is an oversell.* The same sentence with "seats" replaced by "money" is this decision, and the
asymmetry is the same — an unnecessary refund is not recoverable by retrying, so ambiguity must fall
to the retry rather than to the compensation.

**Corollary, stated because it was got wrong once already:** a `finally` that can throw will
**replace** the value its block was returning. `PaymentService` released `payment:inflight` in an
unguarded `finally`, so Redis dropping between the charge and the release discarded a *successful*
result and sent the caller down its catch-all to mark the order `FAILED` — money moved, and the order
said it did not. Cleanup in a `finally` is guarded, always. The key expires on its own.

**Decision 2 — a cache in front of a dependency must back off when that dependency fails, or it
becomes the load.**

`IpRuleService` is read on every API request. Its first version reloaded on any failure without
stamping the attempt, so while PostgreSQL was unreachable **every request opened a connection**
against it. It also had no single-flight guard, so every thread arriving at a TTL boundary issued its
own query — a pool spike on a timer.

Both are the same failure wearing different clothes: *the component whose job is shedding load became
the thing generating it, at exactly the moment that was most expensive.* ADR-051 said "ask not what a
read costs but what stops working when it is slow"; this adds the other half — **ask what the cache
does when the read fails.** Three rules, all non-blocking, because this runs on the hottest path and
a lock there pins carrier threads (invariant 11):

1. A failed attempt stamps the clock exactly like a successful one. One query per window, whatever
   the outcome.
2. One reload in flight at a time; everyone else serves the stale snapshot, which is what a TTL means
   anyway.
3. An empty result is as cacheable as a full one — "no rules" is the normal state of that table, and
   the common case must not be the expensive one.

**Decision 3 — the resume lookup rides the insert it was about to duplicate.**

Finding a resumable 3-D Secure intent had its own `REQUIRES_NEW` read, so **every** checkout paid a
tenth sequential transaction to serve the challenge minority — against a count ADR-049's admission
allowance is derived from. It is now one `beginAttempt` that either finds the `PROCESSING` row or
inserts a new one, in one transaction. Back to nine, and a class shorter.

**Decision 4 — only `requires_action` is a challenge.**

`requires_confirmation` was mapped to `PAYMENT_ACTION_REQUIRED` alongside it. That state means the
*server* has yet to confirm, so the client would receive a `clientSecret` whose `handleNextAction`
does nothing, re-POST, retrieve the same state, and be told to authenticate again for the life of the
hold. It now falls to the transport branch — a retryable `503` with the seats intact, which is the
honest answer to a state this system does not model.

**And one boundary tidy.** `X-Forwarded-For` is resolved against the trusted-proxy set in exactly one
place (ADR-039), so everything downstream reads the answer from `shared`'s `ClientAddress` request
attribute rather than calling `getRemoteAddr()` again — which behind nginx is nginx, so every audit
row in the deployment that matters recorded the same meaningless value.

---

## ADR-057 — The contract is a type, not a layer: facades are implemented by their services, and most exceptions are factories

**Status:** accepted, Pass 9 (13 Sept 2026). Built.

> Numbered 052 when written; renumbered to 057 on the merge with the payment work, which had taken
> 052–056 in parallel. Two branches appending to the same log is exactly how a duplicate number
> happens — check the tip before claiming one.

**Context.** Eight passes of adding correctness left the logic sound and the *packaging* unreadable:
215 Java files for ~8,360 lines of real code, 91 of them 25 lines or fewer. Two patterns produced
most of that fan-out.

**First, every module had a `*Facade` interface, a `*FacadeImpl`, and a service.** The Impl held no
logic — `CatalogFacadeImpl` was twelve one-line delegations and said so in its own javadoc
(*"deliberately no logic here"*). Tracing `checkout → charge` passed through six classes:
`CheckoutService → PaymentFacade → PaymentFacadeImpl → PaymentService → PaymentGateway →
StubPaymentGateway`. Fourteen of the twenty-one facade methods had exactly one production caller.

**Second, every failure was its own class** — twenty-five of them, up to twelve in one module, most
binding an `ErrorCode` to a message and nothing more. **Seventeen were never caught by type.** A
reader could not learn what a module could refuse without opening a directory.

**Decision.**

1. **The module's service implements its own facade interface.** The five `*Impl` classes are gone.
   The interface stays in the `@NamedInterface` `facade` package; the service stays in the internal
   `service` package, which no other module may name. Where a service now carries both shapes —
   `HoldService` returns entities internally and `HoldSummary` records across the boundary — the
   facade methods are grouped under one heading, so a module's published contract is a *section of
   one file* rather than a separate file that only forwards.

2. **A failure gets a class only when something catches it by type, or when two sibling types keep a
   distinction visible.** Everything else is a static factory on one `<Module>Errors` class in the
   same `@NamedInterface` package. Twenty-five classes became ten plus four `Errors` files. Each
   deleted class's javadoc moved onto its factory verbatim — those paragraphs are the record of why
   a distinction exists, and this is a re-shelving, not a deletion.

   Ten survive. `DuplicatePaymentException` is genuinely caught by type at `CheckoutService`.
   `HoldNotFoundException` is caught by type at `PaymentSettlementService`, together with
   `HoldExpiredException` and `HoldAlreadySettledException` — the three ways a webhook settlement
   finds the seats gone, caught as a set so the buyer is refunded (ADR-053). **It was a factory for
   about a week**: nothing caught it when this ADR was written, and the webhook receiver landed on a
   parallel branch days later. The rule produced the right answer both times; what it cannot do is
   see another branch's tip.
   `HoldAlreadySettledException` and `OrderRefundedException` steer control flow.
   `PaymentDeclinedException` and `TicketNotAvailableException` each choose between two answers.
   `HoldExpiredException` carries `expiresAt`. `PaymentGatewayUnavailableException` stayed rather
   than leave `payment` with a one-method `Errors` class. And **`InsufficientStockException` and
   `InventoryUnavailableException` stay as a pair on purpose**: "pick another tier" and "we cannot
   see our own inventory" is the distinction ADR-004 exists to protect, and two sibling types with
   cross-referencing javadoc make it visible in a way two factory methods would not.

**Why this is safe under Modulith, and why that is checked rather than argued.**
`ApplicationModules.verify()` resolves **source-code type references**, not runtime bean types. Call
sites name `CatalogFacade`; Spring injects `CatalogService`. No call site can name the service,
because its package is internal. `ModularityTests` fails the build if any of that is wrong — so the
claim is tested, not reasoned about. All 112 tests stayed green across both changes.

**What this does not change.** The wire format is byte-identical: same `ErrorCode`, same RFC 7807
body, same statuses. `ProblemResponseIT` needed no edit. The facade *interfaces* are unchanged
except that `QueueFacade.verifyAdmission`'s null-token check moved out of the deleted Impl and into
`QueueService`, where the rest of that rule already lived.

**The rule this generalises.** A layer earns its place by holding a decision. A type that only
forwards is not an abstraction — it is a second name for the same thing, and the reader pays for it
on every trace. Prefer making the contract a *type* the compiler enforces over a *layer* a
convention enforces.

**Applied to `bot` on the merge.** Pass 9 added a tenth module facade, `BotFacadeImpl`, in the shape
the other five used — written before this ADR existed. It was never pure delegation: it decides what
a challenge verdict means and orchestrates two services to act on it, which global standards §5 rule
6 already placed in a service. It is now `bot/service/BotVerificationService implements BotFacade`.
Nothing about its behaviour changed. **That it appeared at all is the consequence worth noting**: a
convention removed in one branch is re-added by any branch that forked before it, so rule 7 exists in
§5 precisely because the ADR alone will not be read in time.

**Consequences.**

- Cross-module tracing is one hop shorter everywhere.
- A module's whole failure surface is one file, readable top to bottom.
- `FlashSeatsException`'s constructors are public, which is the point rather than a concession: a
  refusal that needs no type should not have to invent one.
- The temptation returns whenever someone adds a facade method and reaches for a matching `*Impl`.
  Global standards §5 rule 7 now forbids it in the place they will look.

---

## ADR-058 — What arrived with the frontend merge: Sentinel, a broadcast-only replay log, and the metric set

**Status:** accepted, Pass 11 (25 Sept 2026). Built.

> **This ADR is written after the fact, and that is the finding.** PR #16 merged 7,943 files —
> Redis Sentinel, SSE reconnect replay, most of the metric set that three documents called
> "specified, not built", and a React SPA — **with no document change at all.** In this repo a stale
> spec is a standing order to build the wrong thing (`CLAUDE.md`, "Updating the docs is part of the
> change"), so for a week `06` §11 told every reader Sentinel was deferred while three sentinels were
> running, and `04` still said Stripe was unbuilt. The work is good; it was invisible. Nothing below
> is a new decision — it is the record that should have been committed with the code, plus the three
> defects that reading it that way exposed.

### Decision 1 — Sentinel is built, and it supersedes ADR-047's Decision 5

ADR-047 deferred Sentinel with a reason: no Phase 4 exit criterion needs failover, and the
Redis-restart criterion reads more cleanly against a standalone instance that simply stops. That
argument has been spent. The `cluster` profile now runs **one primary, two replicas and three
sentinels** (`quorum 2`, `down-after 5000 ms`, `failover-timeout 60 s`), and the application
discovers the primary through `spring.data.redis.sentinel.*` in `application-docker.properties`.
Dev, test and the plain `docker compose up -d` stack stay standalone.

**The consequence that matters is not failover, it is what failover does to inventory.** Every
Redis process has its own `run_id`, and `StockEpoch` vouches each event's counters against the
`run_id` that derived them. A promoted replica is a different process, so after a failover **every
managed event is distrusted at once** and holds are refused until each is rebuilt. That is not a
regression — AOF is `appendfsync everysec`, so a replica promoted mid-sale is a second behind and its
counters read *high*, which is the one inventory failure no ordering of operations can prevent
(ADR-046). ADR-047 said Sentinel "does not weaken that guard — it makes it matter more"; this is
what that sentence costs in practice: **failover keeps the cluster up and stops the sale**, and an
operator must run `POST /admin/events/{id}/rebuild-stock` to restart it.

`docker/scripts/sentinel-failover-check.sh` proves the topology — promotion within 30 s, the old
primary rejoining as a healthy replica — and says in its own header that it is deliberately *not* an
inventory test. The inventory half is the manual step above, and it is listed in `06` §11.

**Two operational notes.** The sentinels monitor a fixed IP rather than a service name
(`sentinel resolve-hostnames no`), so compose now pins `172.28.0.11-16` — which makes the `ipam`
hazard `CLAUDE.md` already documents live: change that block and `docker compose down` first, or
containers reattach without their DNS aliases and every service name resolves `NXDOMAIN`. And the
sentinels carry no `auth-pass`, matching the current unauthenticated Redis; both move together or
neither does.

### Decision 2 — The replay log retains broadcasts only, and a capability is never replayed

`queue:replay:{e}` (ZSET, frame JSON scored by sequence, capped at 256) and
`queue:replay-seq:{e}` (String, the monotonic sequence) bridge an SSE reconnect: a client sends
`Last-Event-ID` and receives the frames it missed. Both expire with the sale plus
`key-retention-after-sale-seconds`.

**Only broadcast frames are retained.** The log is one ZSET per event, shared by everyone watching
that sale and outliving the sale itself. The only session-targeted frame this system sends is
`queue-promoted`, and it carries a `passToken` — a single-use bearer capability whose own key expires
in 120 s. Retaining it turns a two-minute capability into a durable per-event record filed next to
the session id it belongs to, readable by anything with Redis access long after it was spent. That
is the shape ADR-048 already refused when it kept `receiptToken` out of an operator response, and it
was how this shipped.

**A promoted buyer's reconnect is served by re-deriving, not replaying.** `GET /queue/stream` reads
`getQueueState` on connect and rebuilds `queue-promoted` from the live
`queue:pass:{e}:{sid}` key. This is the `hold:{token}` idiom in a new place — the Redis hint is never
the authority, the real thing is re-read (ADR-048) — and it is strictly better than a replay: it needs
no `Last-Event-ID`, so it also works in a fresh tab or on another device, and it cannot hand back a
pass that has since been spent or has expired.

**One id space, and it belongs to the replay log.** Only retained frames carry an SSE `id`. Position
updates, `sale-exhausted` and the re-derived promotion are sent with **no `id` field at all**, which
the SSE specification defines as leaving the client's last-event-id untouched. So whatever a browser
quotes back is always a sequence this log minted.

> **The defect that made this concrete.** As merged, live frames carried a per-connection
> `"local-N"` id while retained frames carried the sequence, and `after()` parsed the header with
> `Long.parseLong` and returned nothing when it failed. Positions arrive every two seconds, so a
> reconnect almost always quoted a `local-N` — and the replay answered every one of them with an
> empty list. **The feature could not fire on the normal path**, and nothing failed, because there
> was no test. `local-N` also restarts at zero on each connection, so those ids were not monotonic
> either. An id space with two authorities is not an id space.

### Decision 3 — The metric set is built; three gauges remain specified

`flashseats.outbox.lag.seconds`, `flashseats.dlq.depth`, `flashseats.payment.decline.ratio`,
`flashseats.payment.webhook.received{type}`, `flashseats.bot.refusals{outcome}` and
`flashseats.notification.delivered` / `.failed` now emit. `03` §7's "specified, not built" table is
down to `flashseats.queue.depth{event}`, `flashseats.hold.conversion.ratio{event}` and
`flashseats.sse.connections.active`. No alarm thresholds changed.

`bot` also gained a second hand-declared `CircuitBreaker`, around the reCAPTCHA call. `CLAUDE.md`
said there was exactly one, in `payment`; there are two, and both are plain Resilience4j beans
because the starter targets Boot 3.

**One tunable was left half-changed and is now aligned.** `BotProperties.verifiedTtlSeconds`'s field
default moved from 900 to 1800 while `application.properties` continued to ship 900 — so the
*effective* TTL never changed, but the new unit test asserted `PT30M`, which is to say it asserted a
default production does not use. The field default is 900 again, matching what ships and what the key
tables say, and the test now derives its expectation from the configured value rather than a literal,
so the two cannot drift apart again. Whether 15 minutes is the right number is a separate question
from whether four places agree on it; `04`'s "cached per session for 30 min" is the remaining
statement of the other view.

### Decision 4 — The client mirrors the payment seam, and the dependencies leave the repository

The SPA could not start without `VITE_STRIPE_PUBLISHABLE_KEY`: `stripe.ts` threw at module load and
`CheckoutPage` imported it at the top level, so a clean checkout rendered a white screen. Meanwhile
`flashseats.payment.stripe.enabled` is **false by default**, which means the configuration every
developer, the load harness and every drill actually runs — the stub gateway — was the one
configuration the client could not exercise. The client now follows the server: no key means the stub,
and the checkout page offers the stub's documented magic tokens, so decline, gateway outage and 3-D
Secure are walkable in a browser with no account. It also mints **one idempotency key per hold** and
reuses it across retries, as `FE_SPEC` §3 has always specified; as merged it minted a fresh key per
attempt, which opens a second PaymentIntent — the failure ADR-054 exists to prevent, approached from
the other side.

**The SPA is not wired into the cluster**, deliberately and for now: `npm run dev` proxies to
`:8080`, nginx serves no static root, and the demo client at `src/main/resources/static` remains what
the cluster serves. Wiring it touches `nginx.conf`, which is correctness rather than tuning, and it
deserves its own pass.

**57 MB of `node_modules`, `frontend/dist`, four `tsc` outputs and five scratch files** were tracked,
because the `.gitignore` rules for them were added in the same merge that tracked the files, and an
ignore rule never applies to a path git already tracks. They are untracked now and the rules are
real. **The blobs stay in history on purpose** — the pack is 24 MB, and a `filter-repo` rewrite costs
every collaborator a re-clone and every open branch a rebase. That trade is worth revisiting only if
the pack becomes a problem.

**Consequences.**

- A Sentinel failover is now a *sale-stopping* event until an operator rebuilds. That is the design
  working, and it is the strongest argument yet for the operator console `06` §9 still lists as
  missing: the recovery is one call, and it is currently a hand-written `curl`.
- Every capacity number in `06` §11 was measured against standalone Redis. None of them has been
  re-measured through Sentinel.
- The replay log is now safe to read by anyone who can read Redis, which is what lets it stay a
  per-event key rather than a per-session one.
- A reader can tell what is built by reading the documents again.

---

## ADR-059 — A connection-pool timeout is back-pressure: `503 SERVICE_BUSY`, not `500`

**Context.** Under virtual threads the HikariCP pool is the system's real concurrency ceiling, and a
request that waits `connection-timeout` (3 s) for it throws `SQLTransientConnectionException`,
wrapped by Spring as `CannotCreateTransactionException` or `CannotGetJdbcConnectionException`.
Nothing named either, so both fell to `GlobalExceptionHandler`'s `Exception` backstop and a buyer
mid-checkout was told **`500 INTERNAL_ERROR`** — 1,218 times across three replicas in the 2,000-VU
run (`06` §11). The recovery was already correct: checkout is find-or-create, so re-POSTing the same
body resumes the same order. The *answer* was wrong — the least actionable code in the registry, at
exactly the moment a buyer most needed to be told "retry".

**Decision.** A new registry code, `SERVICE_BUSY` (`503`, `shared`), carrying a `Retry-After: 1`
header and `retryable: true, retryAfterSeconds: 1` — the extension members `RATE_LIMITED` and
`PAYMENT_GATEWAY_UNAVAILABLE` already use. The handler classifies by **cause, not by wrapper**: only
an `SQLTransientConnectionException` somewhere in the chain is busy. `CannotCreateTransactionException`
also means "the database is down" and "the credentials are wrong", and telling a client to retry either
in one second would be a lie — those still reach the backstop and answer `500`. Logged at `WARN`:
back-pressure under a spike is expected, and an `ERROR` per rejected request buries real faults.

**What it deliberately does not change.** `CheckoutService` refunds when `commit.confirm` throws after
a successful charge. A pool timeout there is a transaction that never *began*, which is a definite
failure, so the refund is right (ADR-056) and the buyer still gets `409 ORDER_REFUNDED`. The new
handler only sees pool timeouts that happen before money moves, which the `unresolved` catch already
rethrows unchanged after marking the order resumable (ADR-034).

**Why one second.** A pool of 30 turning over millisecond transactions frees hundreds of connections a
second when it is merely saturated; a longer hint would idle a buyer whose retry would have worked.
`FE_SPEC` caps client retries at a handful and then asks the human, so a pool that stays exhausted does
not become a retry storm above the API.

**Consequences.**

- The client contract gains one code, on any endpoint, with "Try again" enabled and seats untouched
  (`FE_SPEC` §2 and the checkout error table).
- Filters run before `DispatcherServlet`; a pool timeout inside one (the `ip_rules` snapshot reload)
  still answers whatever that filter answers. ADR-055/056 already keep the request path off the pool.

---

## ADR-060 — `POST /session/reset` accepts only `application/json`

**Context.** The endpoint expires the `fsid` cookie so the bundled demo page can start over as a new
visitor. The session *is* the buyer's queue position and their only authority over their hold, and
CSRF is disabled (`06` §10 S6), so a hidden form on any site could POST to it and throw a buyer out of
the line they were waiting in. `06` §10 recorded it as S13 and offered two fixes: scope it to the demo
profile, or require something a form post cannot send.

**Decision.** `@PostMapping(value = "/reset", consumes = "application/json")`. An HTML form can send
only `application/x-www-form-urlencoded`, `multipart/form-data` or `text/plain`; a cross-origin
`fetch` carrying `application/json` is not a CORS-safelisted request and needs a preflight, and no
CORS mapping here grants one. Anything else answers `415` through the existing handler, before the
method runs, so no expiring cookie is written.

**Why not the profile.** The cluster runs the `docker` profile and serves the demo page that calls
this endpoint, so scoping it to `dev` would break the demo exactly where it is shown. The demo page's
`api()` helper already sent `Content-Type: application/json`, and the React client never calls it —
the fix needed no client change at all.

**Consequences.**

- S13 is closed. **S6 is not**: `POST /queue/join` and `POST /holds` still accept a cross-site
  request. The same one-line control would apply to them, but both are on the buyer path and each
  deserves its own check that every client sends JSON; it is left as recorded.
- This relies on no CORS configuration being added. A future `CorsConfigurationSource` that allows
  credentials from another origin reopens S13, and should be read against this ADR.

---

## ADR-061 — Cached test contexts are never paused

**Context.** From the frontend merge onwards, `./mvnw test` depended on class order. In some orders,
every `/queue` request in the shared integration-test context answered a bare `500` with no registry
`code`. Pass 12 ruled out everything inside the application: contention, id collisions, the rate
limiter, metrics, parallelism, context accumulation. It pinned `alphabetical` because that order was
*verified green*, and recorded the pollution as open (`06` §9).

**Finding.** Spring Framework 7 **pauses** a cached test context when a test class switches to a
different context: it stops the paused context's `Lifecycle` beans, then restarts them on the next
use. This suite has several contexts. `BotDefenceIT`, `RecaptchaFailOpenIT` and, from Pass 13,
`NotificationListenerIT` each add properties, and that forces a context of their own. When a later
class switched back to the shared context, that context had been through a pause and a resume.

Adding `NotificationListenerIT` shrank the reproduction from "most of the suite" to three classes:
`HoldLifecycleIT`, `NotificationListenerIT`, `CheckoutRecoveryIT`. With the size down to three,
experiments became cheap:

- The pair `NotificationListenerIT`, `CheckoutRecoveryIT` passes: the shared context is created
  *after* the switch, so it has never been paused.
- Removing `@DirtiesContext` from the new class changes nothing.
- A temporary `HIGHEST_PRECEDENCE` servlet filter never saw the failing requests. They are answered
  before the resumed context's filter chain runs, which is why `GlobalExceptionHandler` never logged
  them.
- `spring.test.context.cache.pause=never` makes the three-class run green, and makes the full suite
  green in `alphabetical`, `reversealphabetical` and `filesystem` order.

**Decision.** `src/test/resources/spring.properties` sets `spring.test.context.cache.pause=never`.
This restores Spring 6's behaviour, which the suite was written against. It has to live in
`spring.properties` rather than `application-test.properties`, because the cache reads it before any
context exists.

**Consequences.**

- A cached context keeps its schedulers running while other classes run. Every context has its own
  PostgreSQL and Redis, because Testcontainers reuse is not enabled on this machine
  (`withReuse(true)` logs that it was ignored), so they cannot interfere through shared state.
- **Open:** *why* a resumed context's embedded Tomcat answers without running its filters. It is
  inferred to be the web server's restart, not demonstrated. It does not affect production, where
  contexts are never paused. If Testcontainers reuse is ever enabled, reread this ADR, because the
  contexts would then share containers.
- Surefire stays pinned to `alphabetical`, now only because a stable order is worth having.
- The lesson for this repo: when an intermittent failure depends on the number of Spring contexts,
  suspect the test framework's context lifecycle before the application.

---

## ADR-062 — Each replica has a memory limit, and the image alone owns the JVM flags

**Context.** Pass 13's load sweep at 2,000 VUs across five sales saw app replicas SIGKILLed mid-sale.
The Docker VM's kernel log recorded `Out of memory: Killed process … (java)` three times, with RSS of
4.0, 2.5 and 2.7 GiB. Three defects combined to cause it, and each was invisible on its own:

1. **No container memory limit.** The Dockerfile set `-XX:MaxRAMPercentage=75`, and its comment
   said this sizes the heap "from the container limit". There was no limit, so each JVM sized
   itself against the whole 7.65 GiB VM. Three replicas could claim about 17 GiB between them. G1
   grows the heap lazily, so this passed every run at 300–600 VUs, where each replica settled at
   1.1–1.5 GiB, and only failed once the load pushed the heaps past what the VM could hold.
2. **`compose.yaml` replaced the image's `JAVA_TOOL_OPTIONS`.** It set the variable to
   `-XX:MaxRAMPercentage=75` alone, which silently dropped the image's `-XX:+ExitOnOutOfMemoryError`
   and `-XX:+UseZGC`. Every cluster measurement ever recorded therefore ran on G1, not ZGC.
3. **The JVM's collector choice depends on the limit.** Setting a 1.5 GiB limit on its own made
   the JVM stop treating the container as a "server-class machine" (that needs ≥ 1,792 MB). It
   silently switched to **SerialGC**, a single-threaded stop-the-world collector, on a server
   carrying thousands of virtual threads. `-XX:+PrintFlagsFinal` inside the container showed this
   before any run did.

**Decision.**

- `mem_limit: ${APP_MEM_LIMIT:-1536m}` on every app replica. This covers the 1.1–1.5 GiB they reach
  at the load this host can serve, and it keeps three replicas plus the infrastructure and k6
  inside the VM.
- The Dockerfile is the **only** place `JAVA_TOOL_OPTIONS` is set:
  `-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`. 70 % leaves about 460 MiB of
  the limit for metaspace, thread stacks and Lettuce/Tomcat's off-heap buffers. **G1 is named**,
  so ergonomics cannot swap it for SerialGC. G1 rather than ZGC because G1 is what every number in
  `06` §11 was measured on. `compose.yaml` now says in a comment why it does not set the variable.

**Verified.** On the new configuration the same four runs, from 300 to 2,000 VUs, completed with
**zero restarts**, zero unreadable metric samples and an exact ledger. At 2,000 VUs each replica
peaked at 1.48–1.50 GiB against its 1.5 GiB limit. That is tight, but `ExitOnOutOfMemoryError`
never fired, and the p99 there (6.1 s) is set by CPU, not by GC.

**Consequences.**

- A replica that genuinely runs out of heap now exits cleanly and restarts, instead of being
  killed at random by the VM with a neighbour's memory. Either way the Redis-first ordering loses
  only in-flight reservations, and only toward under-count (ADR-046). Pass 13 watched a rebuild
  recover exactly those seats.
- 2,000 VUs runs replicas at their limit on this host. Raising `APP_MEM_LIMIT` is the lever for a
  bigger machine, but not on this 7.65 GiB VM.
- The lesson for this repo: a flag that reads correctly in one file can be cancelled by another,
  and a JVM decides things about itself from the container it finds. Check effective settings with
  `java -XX:+PrintFlagsFinal -version` inside the container, not by reading the Dockerfile.

---

## ADR-063 — ADR-057's exception rule, applied without exceptions

**Status:** accepted, Pass 14. Amends ADR-057.

**Context.** ADR-057 set the rule: *"a failure gets a class only when something catches it by type,
or when two sibling types keep a distinction visible."* It then kept seven classes for other
reasons. `PaymentDeclinedException` and `TicketNotAvailableException` "chose between two answers".
`OrderRefundedException` "steered control flow". `PaymentGatewayUnavailableException` avoided "a
one-method `Errors` class". `PaymentActionRequiredException`, `WebhookSignatureInvalidException` and
`BotVerificationFailedException` arrived on branches that forked before the rule. Pass 14 checked
all seven: **none is caught by type anywhere in `main`.** Choosing a code, a message or a
`retryable` flag from an argument is exactly what a static factory does. And "steers control flow"
had become untrue once `CheckoutService`'s catch-all caught `RuntimeException`.

**Decision.** All seven become factories: `PaymentErrors.{declined, actionRequired,
gatewayUnavailable, webhookSignatureInvalid}`, `OrderErrors.{refunded, ticketNotAvailable}` and
`BotErrors.verificationFailed`. The classes that remain are the ones something catches:
`DuplicatePaymentException`, the three `Hold*` exceptions `PaymentSettlementService` catches as a
set, and `RecaptchaTransportException`. The `InsufficientStock`/`InventoryUnavailable` pair also
stays, for ADR-057's distinction reason, which still holds.

**What does not change.** The wire format stays byte-identical: same codes, statuses and extension
members, in the same order. `ProblemResponseIT` and the checkout ITs pass unedited. The two unit
tests that asserted `isInstanceOf(...)` now assert the `ErrorCode`, which is the contract a client
actually sees.

**The rule, stated so it cannot drift again.** A class only if a `catch` names it. Everything else is
one static method on `<Module>Errors`, and a module's refusals are read from that one file.
