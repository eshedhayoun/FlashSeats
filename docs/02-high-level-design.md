# High-Level Design

**Infrastructure blueprint, module charters, and the reasoning behind the concurrency model.**

Companion documents: [`01-system-architecture.md`](01-system-architecture.md) (stack),
[`03-end-to-end-flow.md`](03-end-to-end-flow.md) (the user journey),
[`00-architecture-decisions.md`](00-architecture-decisions.md) (why each choice was made).

---

## 1. The problem

Ten thousand people want five hundred tickets, and they all arrive in the same second.

Three things must be true simultaneously:

1. **Exactly 500 tickets are sold.** Not 501. Not 499 with 2 stuck in limbo.
2. **Nobody is charged for a seat they do not get**, and nobody gets a seat they were not charged for.
3. **The 9,500 who miss out find out quickly**, rather than waiting twenty minutes to be told no.

Everything in this design serves one of those three.

---

## 2. Infrastructure

```
                          [ Concurrent buyers ]
                                   │
                    ┌──────────────▼──────────────┐
                    │  Nginx  ·  least_conn       │
                    │  proxy_buffering off (SSE)  │
                    └──────────────┬──────────────┘
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
   [ app 1 : Spring ]      [ app 2 : Spring ]      [ app 3 : Spring ]
     Java 21 virtual threads · stateless · any replica serves any request
          └────────────────────────┼────────────────────────┘
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
   [ Redis 7 ]              [ PostgreSQL 16 ]         [ RabbitMQ 3.13 ]
   primary + Sentinel        AOF everysec              order.events.exchange
   noeviction                                          + DLX / DLQ
   stock · queue · holds     orders · outbox · audit   PDF + email fulfilment
   rate limits · pub/sub
                                                       [ Mailpit ]  ← local SMTP
                                                       [ k6 ]       ← load harness
```

### Why these components

**Nginx** distributes HTTP and SSE across replicas and forwards the client IP for rate limiting.
`least_conn` beats round-robin here because SSE connections are long-lived and wildly uneven in
duration. The SSE-specific settings are not optional: with default buffering, position updates
accumulate in Nginx and the user sees a frozen page.

**Stateless Spring replicas.** Any replica handles any request. The single exception is the
`SseEmitter`, which lives in one replica's heap — hence Redis Pub/Sub fan-out for promotions
(ADR-007). This is the detail that most often breaks a queue system in production, because it
works perfectly on one instance and silently loses two-thirds of promotions on three.

**Redis** does the microsecond work: atomic stock decrements, ZSET ordering, TTL-driven holds,
token buckets. Single primary with Sentinel — not Cluster, because the reserve script spans hash
slots and would fail `CROSSSLOT` (ADR-018).

**PostgreSQL** is the money. Orders, payments, and the outbox are ACID or they are nothing.

**RabbitMQ** keeps PDF rendering and SMTP off the checkout path so the confirmation response stays
under 200 ms.

**k6 harness** fires 10,000+ virtual users at Nginx to prove zero overbooking under real
contention — the only way to know the concurrency design actually works.

---

## 3. The concurrency model

### Where inventory truth lives

This is the question the whole system turns on, and the answer changes by phase — deliberately.

| Phase | Fast path | Authority | Primitive |
| :--- | :--- | :--- | :--- |
| 1 (MVP) | — | `tier_inventory.remaining` in PostgreSQL | `UPDATE … WHERE remaining >= :q` |
| 2+ | `catalog:stock:{e}:{t}` in Redis | Redis during the sale; PostgreSQL for **rebuild** | `hold_reserve.lua` |

In Phase 1, one statement does everything:

```sql
UPDATE tier_inventory SET remaining = remaining - :q
 WHERE tier_id = :t AND remaining >= :q;
```

PostgreSQL locks the row; `rowcount = 1` means the seats are yours; `CHECK (remaining >= 0)` is the
backstop. **Overbooking is already impossible in the MVP.** Phase 2 does not make the system
*correct* — it makes an already-correct system fast.

In Phase 2 the same guarantee moves into a Lua script, which is single-threaded and atomic by
Redis's execution model:

```lua
if stock == false then return -2 end          -- FAULT: counter missing
if tonumber(stock) < qty then return -1 end   -- genuinely sold out
DECRBY stock qty;  HSET hold:{token} …;  EXPIRE 300;  SET holdmeta:{token} … EX 86400
```

The `-2` branch is the most important line in the system. The original design treated a missing
counter as a cache miss and repopulated it from `total_capacity` — which, after any Redis eviction
or cold restart mid-sale, would silently resurrect every ticket already sold. A missing counter is
a **fault**: `503`, alarm, and an explicit locked rebuild from PostgreSQL (ADR-004).

### Returning stock exactly once

A hold ends in one of four ways — consumed, released, expired, or reclaimed by the sweeper — and
three replicas may all try to handle the same ending at the same moment. Redis keyspace expiry is
pub/sub, so **every replica receives the expiry event**; the naive implementation returns a 2-ticket
hold three times.

The fix is one primitive, the **settle-once claim**:

```
Phase 2+ :  GETDEL holdmeta:{holdToken}     -- atomic; exactly one caller gets the value
Phase 1  :  UPDATE ticket_holds SET status=? WHERE hold_token=? AND status='ACTIVE'
```

Whoever wins the claim restores the stock. Everyone else gets `nil` (or `rowcount = 0`) and does
nothing. No locks, no coordination, correct across any number of replicas — and it simultaneously
resolves the undefined "shadow record" the expiry listener was supposed to read, since `holdmeta`
outlives the hold by design (ADR-003).

### Why the queue exists

The queue does **not** prevent overbooking — `hold` does. The queue exists so that the 9,500 people
who will not get tickets do not all hit the checkout path at once, and so they learn their fate
quickly instead of slowly.

That reframing has a consequence: admission must be bounded by *real* remaining capacity, or the
queue is just a slower way to deliver a `409`. Each tick admits
`min(batchSize, remainingStock − livePasses)`, and when stock is gone the queue broadcasts
`sale-exhausted` and drains (ADR-008).

---

## 4. Module charters

### `bot` — gatekeeping
Issues the signed `fsid` cookie that every other module treats as identity. Enforces Redis-backed
Bucket4j limits, **session bucket primary, IP bucket as a coarse flood backstop** — a tight per-IP
limit is fatal for carrier-grade NAT during exactly the spike this system exists to serve
(ADR-011). Verifies reCAPTCHA v3 on `POST /queue/join` only, caching the verdict per session.
Fails open if Google is unreachable: a deliberate availability-over-security trade, with the rate
limits as the compensating control.

### `catalog` — metadata and inventory ownership
Owns `events`, `ticket_tiers`, `tier_inventory` and the Redis stock counters. Derives
`windowStatus ∈ {UPCOMING, OPEN, CLOSED}` and publishes `serverTime` so the landing-page countdown
runs on the server's clock, not the device's. Seeds counters via `SETNX` **only while `UPCOMING`**,
and owns the rebuild procedure.

### `queue` — waiting room
Redis ZSET ordered by arrival millisecond, `ZADD NX` so a refresh preserves position. Streams
positions over SSE with a 15 s heartbeat and `Last-Event-ID` reconnect. Mints single-use HMAC passes
(120 s) and fans them to the right replica over Redis Pub/Sub. Stores nothing in PostgreSQL.

### `hold` — reservations
Atomically moves stock and creates a 300 s reservation in one Lua script. Owns the hold state
machine and the settle-once claim. Runs a keyspace-expiry listener as a latency optimisation and a
30 s reconciliation sweeper as the correctness guarantee — keyspace pub/sub is at-most-once, so the
sweeper, not the listener, is what makes expiry reliable.

### `payment` — gateway
Stripe behind Resilience4j. Idempotency anchored to the hold, not to a client-chosen string
(ADR-014). Verifies webhook signatures and publishes `PaymentSettledEvent`. Calls **no** other
module's facade.

### `order` — the ledger and the orchestrator
The single checkout entry point. Validates the hold, prices server-side, reserves the
`UNIQUE(hold_token)` row, charges, then consumes the hold and writes `orders`, `order_items` and
`outbox_events` in one transaction. Owns every compensation path: decline, refund, webhook
reconciliation.

### `notification` — fulfilment
Drains the outbox with `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ, renders PDF and HTML, sends
via SMTP. Idempotent by `UNIQUE(order_number, kind)` with insert-then-send. Handles both
`TICKET_DELIVERY` and `REFUND_NOTICE`. Receives a complete payload snapshot and calls no facades.

---

## 5. Timing

```
t=0    sale opens ──────────────────────────────────────────────────────────►
       │
       ├─ join queue ──► [ waiting room: ZSET, unbounded wait ]
       │                            │
       │                    promotion tick (1 s)
       │                            ▼
       ├─ pass issued ──► [ 120 s · single-use · revoked on first hold ]
       │                            │
       │                     POST /holds
       │                            ▼
       ├─ hold created ─► [ 300 s · +120 s grace once · ceiling 420 s ]
       │                            │
       │                  POST /orders/checkout
       │                            ▼
       ├─ charge ───────► [ Stripe · ≤ 3 attempts · hold retained on decline ]
       │                            │
       │                            ▼
       └─ CONFIRMED ────► [ outbox → RabbitMQ → PDF → email ]   (async, ~seconds)
```

Every timer is bounded, single-use, and nested inside the one before it. Nothing renews without a
ceiling — the original `extendHold` had none, which made permanent seat-squatting free (ADR-006).

---

## 6. What was wrong before, and what fixed it

| Defect | Fix | ADR |
| :--- | :--- | :--- |
| Cache miss repopulated stock from `total_capacity` | Missing counter = fault; locked rebuild from PostgreSQL | 004 |
| Keyspace expiry restored stock once per replica | Settle-once claim via `GETDEL` | 003 |
| Expiry listener read an undefined "shadow record" | `holdmeta:{token}`, written in the same Lua script, 24 h TTL | 003 |
| Two contradictory checkout flows | One: `order` orchestrates, charge first, consume second | 001 |
| No `hold_token` on `orders` | `UNIQUE(hold_token)` + find-or-create retry semantics | 002 |
| Webhook finalised orders whose seats were re-sold | Attempt consume; auto-refund + notify on failure | 012 |
| SSE promotions lost across replicas | Redis Pub/Sub fan-out + `/queue/status` polling fallback | 007 |
| `extendHold()` used but never declared; "non-extendable" | Declared, bounded: once, +120 s, ceiling 420 s | 006 |
| Pass never revoked → unlimited holds | Single-use, revoked on first hold | 006 |
| `PaymentFailedEvent` released a hold the UX said to keep | Decline retains the hold; `payment` never calls `hold` | 005 |
| `ZADD` sent refreshing users to the back of the line | `ZADD NX` | 008 |
| Queue admitted users into a sold-out sale | Admission bounded by capacity; `sale-exhausted` event | 008 |
| Email collected nowhere, yet `NOT NULL` | Collected at checkout | 001 |
| Client-supplied `amountCents` | Server-side pricing from `CatalogFacade` | 013 |
| Idempotency keyed on a client-chosen string | Anchored to `hold_token`; three layers | 014 |
| Three replicas publishing every outbox event | `FOR UPDATE SKIP LOCKED` | 009 |
| `SELECT`-based dedupe let two workers both send | `UNIQUE(order_number, kind)`, insert-then-send | 015 |
| Flat payload broke multi-tier orders | Complete snapshot with a line-item array | 015 |
| `userSessionId` from the request body | Signed `fsid` cookie | 010 |
| Public order lookup leaked buyer email | `fsid` match or signed `receiptToken` | 010 |
| 20 req/s per IP blocked NAT populations | Session bucket primary, IP coarse | 011 |
| Bucket4j "in-memory" vs "Redis" contradiction | Redis-backed in all phases | 011 |
| Sale windows never enforced | `windowStatus` gates join, hold, checkout | 016 |
| No countdown contract; client clock skew | `serverTime` in the event payload | 016 |
| Unbounded `quantity`, unlimited holds per session | 6 per hold, 1 active hold per session | 017 |
| Redis Cluster + multi-key Lua = `CROSSSLOT` | Single primary + Sentinel | 018 |
| Two competing outbox mechanisms on the classpath | Hand-rolled table; Modulith kept for verification only | 009 |
| No metrics, no kill switch | Metric set + `stock.drift` alarm + pause/rebuild endpoints | — |
