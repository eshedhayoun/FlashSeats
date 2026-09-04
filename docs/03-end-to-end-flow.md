# End-to-End Architecture & UX Flow

> **The authoritative user-journey document.** It describes the target (Phase 4) system. Where a
> phase is simpler, the **Phase 1 (MVP)** callout says what is deferred and — importantly — why the
> simpler version is still *correct*, not merely smaller.
>
> Decisions here are justified in [`00-architecture-decisions.md`](00-architecture-decisions.md).

---

## 1. System Overview

```
                            +---------------------------------------+
                            |            CLIENT / BROWSER           |
                            +---------------------------------------+
                              |  HTTP        |  SSE        |  HTTP
                              v              v             v
                       +---------------------------------------------+
                       |            `bot`  SERVLET FILTER            |
                       |  fsid cookie · Bucket4j (Redis) · reCAPTCHA |
                       +---------------------------------------------+
                                            |
        +---------------------+-------------+-------------+---------------------+
        v                     v                           v                     v
+----------------+   +----------------+          +----------------+    +----------------+
|   `catalog`    |   |    `queue`     |          |     `hold`     |    |    `order`     |
| metadata +     |   | waiting room   |          | reservations   |    | ledger+outbox  |
| inventory      |   |                |          |                |    |                |
+----------------+   +----------------+          +----------------+    +----------------+
| PG: events,    |   | PG: none       |          | PG: ticket_    |    | PG: orders,    |
|  ticket_tiers, |   | Redis: ZSET,   |          |  holds (AUTH-  |    | order_items,   |
|  tier_inventory|   |  pass, admit,  |          |  ORITY)        |    | outbox_events  |
| Redis: stock   |   |  pubsub        |          | Redis: hold:   |    |                |
|                |   |                |          |  (timer only)  |    |                |
+----------------+   +----------------+          +----------------+    +----------------+
                                                                              |
                                                             +----------------+---------+
                                                             v                          v
                                                     +----------------+        +------------------+
                                                     |   `payment`    |        | `notification`   |
                                                     | Stripe gateway |        | PDF + email      |
                                                     +----------------+        +------------------+
                                                     | PG: payment_   |        | PG: notification |
                                                     |  transactions  |        |      _logs       |
                                                     | Redis: inflight|        | RabbitMQ + SMTP  |
                                                     +----------------+        +------------------+
```

### Facade graph (acyclic — see ADR-005, ADR-025)

```
                    shared          ← open module: ProblemDetail, ErrorCode, SessionId
                 (everyone may depend on it)

filter   ──► bot
queue    ──► catalog
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog        ← read-only leaf; nothing depends on it
payment  ──( PaymentSettledEvent — webhook path only )──► order
order    ──( outbox → RabbitMQ )──► notification
```

`ApplicationModules.verify()` enforces this at build time. There is no illegal cross-module
database access anywhere in the design: a module reads only its own tables and its own Redis
key prefixes.

Two additions from the 2nd-pass audit:

* **`shared`** — a Modulith *open module* holding the RFC 7807 machinery, the canonical error-code
  enum, and value types (`SessionId`, `Money`). Required, not optional: without it, seven modules
  would either duplicate error codes or take dependencies on each other that `verify()` rejects
  (ADR-021).
* **`saleflow`** — a read-only composition module owning one endpoint,
  `GET /api/v1/sale/{eventId}/state`, which rehydrates the SPA after a tab reload. It cannot live in
  `queue`, because `queue` would then need `HoldFacade` while `hold → queue` already exists — a
  cycle. A leaf that depends on many and is depended on by none is the standard answer (ADR-025).

### The one shared key, and its contract

`catalog:stock:{eventId}:{tierId}` is the sole piece of state touched by two modules.

* **`catalog` owns it.** It seeds the key (only while the window is `UPCOMING`), reads it for
  display, rebuilds it during recovery, and reconciles it after the sale.
* **`hold` mutates it**, exclusively through `hold_reserve.lua` and `hold_restore.lua`. Those two
  scripts are the entire mutation surface, and they live in `hold` because a decrement and the
  creation of the reservation that justifies it must be one atomic operation.

No other module reads or writes it. This exception is deliberate, narrow, and written down.

---

## 2. The User Journey

### Step 0 — Pre-sale setup and inventory warming *(admin)*

An administrator creates the event and its tiers. Inventory is warmed either by a scheduled job
15 minutes before `sale_start_time`, or on demand via
`POST /api/v1/admin/events/{eventId}/prewarm`:

```
for each tier:
    SETNX catalog:stock:{eventId}:{tierId}  <total_capacity>
```

**`SETNX` is only legal while the window is `UPCOMING`.** Once the sale is `OPEN`, seeding from
`total_capacity` would resurrect every sold ticket, so pre-warm refuses to run and recovery uses the
rebuild procedure in §4.1 instead (ADR-004).

`catalog` then fires `EventPrewarmedEvent` for monitoring.

> **Phase 1 (MVP):** no Redis. Pre-warm writes `tier_inventory.remaining = total_capacity` in
> PostgreSQL. Still correct — it is just a slower counter.

---

### Step 1 — Landing page and pre-sale countdown

`GET /api/v1/events/{eventId}` returns metadata, tiers, live availability **and the server's clock**:

```json
{
  "eventId": 10024,
  "title": "Summer Fest 2026",
  "venueName": "Riverside Arena",
  "eventStartTime": "2026-09-14T19:00:00Z",
  "saleStartTime": "2026-08-30T10:00:00Z",
  "saleEndTime":   "2026-08-30T22:00:00Z",
  "windowStatus":  "UPCOMING",
  "serverTime":    "2026-08-30T09:57:12Z",
  "tiers": [
    { "tierId": 501, "tierName": "VIP", "priceCents": 7500,
      "currency": "USD", "maxPerOrder": 6, "availability": "LIMITED" }
  ]
}
```

The browser counts down against `serverTime + elapsedLocalTime`, never against the device clock.
Without this, every client's skew smears the start of the sale and the fairness of the ZSET
ordering becomes a lie (ADR-016).

`windowStatus` drives the UI directly: `UPCOMING` → countdown, "Join" disabled; `OPEN` → "Join Flash
Sale" enabled; `CLOSED` → sale-ended panel.

`availability` is a **bucket** — `PLENTY` | `LIMITED` | `SOLD_OUT` — never an exact count. Exact live
inventory drives panic-buying and hands scalpers a free feed (ADR-027). `maxPerOrder` is
server-authoritative: the UI renders whatever the API returns and never hardcodes a limit.

The `bot` filter issues the signed `fsid` cookie on this first request, so the visitor has a stable
identity before the sale opens.

---

### Step 2 — Joining the virtual waiting room

`POST /api/v1/queue/join` with a reCAPTCHA v3 token.

1. **`bot`** verifies the signed `fsid` cookie, consumes a session token and an IP token, and
   validates the reCAPTCHA score (≥ 0.5). The verdict is cached in `bot:captcha:{sid}` for 30
   minutes so the hottest endpoint in the system makes at most one outbound call to Google per
   visitor.
2. **`catalog`** confirms `windowStatus == OPEN`. A join before the sale opens is `409`, not a
   silent success.
3. **`queue`** places the user:

   ```
   ZADD NX queue:waiting:{eventId} <epochMillis> <sessionId>
   ```

   `NX` matters. Plain `ZADD` *updates* an existing member's score, so a page refresh or a
   double-click on "Join" would have reset the timestamp and sent the user to the **back** of the
   line — the exact opposite of the FIFO guarantee (ADR-008).

4. The browser opens `GET /api/v1/queue/stream`. Frames:

   | Event | Payload | Meaning |
   | :--- | :--- | :--- |
   | `position-update` | `{position, aheadOfYou, estWaitSeconds}` | every ~2 s |
   | `queue-promoted` | `{passToken, expiresInSeconds: 120}` | your turn — redirect |
   | `sale-exhausted` | `{soldOutAt}` | stock gone; queue drains |
   | `tier-availability` | `{tiers:[{tierId, level}]}` | a tier crossed a bucket boundary (ADR-027) |
   | `sale-closed` | `{saleEndTime}` | window closed |
   | *(comment frame)* | `:hb` | 15 s heartbeat, keeps proxies open |

Reconnects send `Last-Event-ID`. If the stream cannot be established at all, the client falls back
to polling `GET /api/v1/queue/status`, which returns the same state **including a pass if one was
already minted** — so a promotion is never lost to a dead socket (ADR-007).

#### Promotion

A scheduled worker ticks once per second per active event:

```
livePasses  = ZCOUNT queue:passes:{eventId} <now> +inf
admittable  = min(batchSize, remainingStock − livePasses)
if admittable <= 0: skip this tick
for sid in ZRANGE queue:waiting:{eventId} 0 admittable-1:
    passToken = HMAC-SHA256({eventId, sid, exp: now+120s}, serverSecret)
    SET  queue:pass:{sid} <passToken> EX 120
    ZADD queue:passes:{eventId} <now+120s> <sid>
    ZREM queue:waiting:{eventId} <sid>
    PUBLISH queue:events:{eventId} {sid, passToken}
```

Two things are load-bearing here:

* **`admittable` is bounded by real capacity.** The original design promoted users into a sold-out
  sale, so buyers waited twenty minutes to be handed a `409 INSUFFICIENT_STOCK`. When
  `remainingStock` hits zero with no holds outstanding, the queue broadcasts `sale-exhausted` and
  drains instead (ADR-008).
* **`PUBLISH` is how the pass reaches the browser.** The promoter runs on one replica; the
  `SseEmitter` lives in another replica's heap. Every replica subscribes to `queue:events:{eventId}`
  and delivers to its own local emitters. Without this, behind three round-robin replicas roughly
  two-thirds of promotions would silently vanish (ADR-007).

**Abandoned entries are never evicted** (ADR-026). They reach the front, are promoted, never claim
their pass, and the pass expires in 120 s — capacity returns on its own, and the 1.5× oversubscribe
factor already prices in non-conversion.

Evicting on a missing heartbeat was a live defect: a Wi-Fi → cellular handover routinely exceeds any
heartbeat TTL, so a buyer doing nothing wrong was silently deleted from the line. `estWaitSeconds` is
now derived from the **measured drain rate** (`ZCARD` delta over 30 s), which accounts for
abandonment implicitly.
Position is clamped **monotonic non-increasing** client-side: evictions ahead of you can make a raw
`ZRANK` jump backwards, and a number that goes *up* reads as a broken queue.

> **Phase 1 (MVP):** no queue module at all. The user goes straight from the landing page to seat
> selection. Nothing downstream depends on the queue for correctness — the pass is an
> *admission-control* device, not a *safety* device. Overbooking is prevented in `hold`.

---

### Step 2b — Admission *(the middle tier)*

`POST /api/v1/queue/admit`, header `X-Queue-Pass-Token`. The browser calls this immediately on
landing in the seat-selection view.

```
1. QueueFacade.verifyPassToken(token, sid, eventId)
2. SET  queue:admit:{eventId}:{sid} <signed admission token> EX 600
3. ZADD queue:admissions:{eventId} <now+600s> <sid>
4. revoke the pass: DEL queue:pass:{sid}; ZREM queue:passes:{eventId} <sid>
```

**Why this tier exists.** The pass proves you left the queue; the admission session means *you are
inside the sale*. Without it, a buyer promoted at t=0 had 120 seconds to choose a tier or return to
the queue — but real buyers compare tiers, check prices, and consult someone. Ten minutes of browse
time is the industry norm (Ticketmaster, AXS, Queue-it all run this three-tier model).

It also means **a released or expired hold does not cost you your place.** You can switch from VIP
to Section A without re-queueing, because the admission session outlives the hold. That single
property fixes tier changes, the back button, and refresh recovery at once (ADR-020).

The admission session is revoked when an order reaches `CONFIRMED`.

---

### Step 3 — Seat selection and holding

`POST /api/v1/holds`, header `X-Admission-Token`:

```json
{ "eventId": 10024, "tierId": 501, "quantity": 2 }
```

`userSessionId` is **not** in the body. It comes from the signed `fsid` cookie; a client-supplied
identity would let anyone act as anyone (ADR-010).

1. `QueueFacade.verifyAdmission(admissionToken, sid, eventId)` — HMAC plus a live
   `queue:admit:{eventId}:{sid}`. **Not** the pass: that was already spent in Step 2b.
2. `CatalogFacade.getTierSummary(eventId, tierId)` — tier exists, belongs to the event, window is
   `OPEN`.
3. Limits: `quantity ≤ 6`; at most one `ACTIVE` hold per session per event (ADR-017).
4. `hold_reserve.lua` — one atomic script:

```lua
local stockKey, holdKey = KEYS[1], KEYS[2]
local qty, ttl = tonumber(ARGV[1]), tonumber(ARGV[2])

local stock = redis.call('GET', stockKey)
if stock == false then return -2 end          -- key absent: FAULT, never "sold out" (ADR-004)
if tonumber(stock) < qty then return -1 end   -- genuinely insufficient

redis.call('DECRBY', stockKey, qty)
redis.call('HSET', holdKey,
    'userSessionId', ARGV[3], 'eventId', ARGV[4], 'tierId', ARGV[5],
    'quantity', tostring(qty), 'status', 'ACTIVE', 'expiresAt', ARGV[6])
redis.call('EXPIRE', holdKey, ttl)
return 1
```

> There is no `holdmeta` key any more. ADR-019 moved the settle-once claim into PostgreSQL, and
> `holdmeta` only ever existed because a Redis expiry event carries no payload — a problem that
> disappears once the expiry handler reads `ticket_holds` by token.

5. Insert the `ticket_holds` row with `status = ACTIVE`. **This row is the authority**, not the
   Redis key.

The admission session is **not** revoked here — that is the whole point of Step 2b.

| Script result | Response |
| :--- | :--- |
| `1` | `201` + `HoldResponseDTO` |
| `-1` | `409 INSUFFICIENT_STOCK` |
| `-2` | `503 INVENTORY_UNAVAILABLE` + alarm — **never** treated as sold out |

The `-2` case is the fix for the most dangerous line in the original docs, which had a cache miss
repopulate the counter from `total_capacity` (ADR-004).

> **Phase 1 (MVP):** the atomic primitive is
> `UPDATE tier_inventory SET remaining = remaining - :q WHERE tier_id = :t AND remaining >= :q`.
> One statement, row-locked by PostgreSQL, `rowcount = 1` means the seats are yours. Combined with
> `CHECK (remaining >= 0)` this makes overbooking impossible in the MVP too — the concurrency story
> is complete from day one; Phase 2 only makes it faster.

---

### Step 4 — Checkout

The UI presents the hold with a countdown driven by `ttlRemainingSeconds` (re-synced from
`GET /api/v1/holds/{holdToken}`, never from the device clock), collects the buyer's **email**, and
takes card details.

> Email is collected **here**. In the original flow it was collected nowhere at all, yet
> `orders.user_email` was `NOT NULL` and the entire notification module depended on it.

`POST /api/v1/orders/checkout`:

```json
{
  "holdToken": "hld_9f8b2c1a4d3e2f10b98a",
  "userEmail": "buyer@example.com",
  "paymentMethodId": "pm_card_visa",
  "idempotencyKey": "cli_4f2a9c81e70b"
}
```

`order` orchestrates. There is exactly one checkout endpoint (ADR-001):

```
 1. HoldFacade.getActiveHold(holdToken, sid)      → 409 if missing/expired/not yours
 2. CatalogFacade.getTierSummary(eventId,tierId)  → price snapshot, SERVER-SIDE (ADR-013)
 3. find-or-create orders row, hold_token UNIQUE, status = PENDING (ADR-002)
 4. HoldFacade.extendHold(holdToken, 120s)        → once, ceiling 420s (ADR-006)
       └─ FAILS ⇒ ABORT with 410 HOLD_EXPIRED. Do NOT charge.       ← see below
 5. PaymentFacade.authorize(orderNumber, amountCents, currency, pmId, idempotencyKey)
                                                  ← OUTSIDE any transaction (ADR-023)
 6. @Transactional {          ← SQL ONLY. No Redis, no HTTP, no broker.
        UPDATE ticket_holds SET status='CONSUMED'
          WHERE hold_token=? AND status='ACTIVE'   → rowcount 0 ⇒ roll back + refund
        orders.status = CONFIRMED
        INSERT order_items
        INSERT outbox_events (ORDER_CONFIRMED, PENDING)
    }
 7. AFTER_COMMIT (best-effort, safe to lose):
        DEL hold:{holdToken}                       ← Redis cleanup only
        QueueFacade.revokeAdmission(sid, eventId)
 8. 201 Created + OrderReceiptDTO
```

**Charge first, consume second.** Consuming before charging would require a
`CONSUMED → RELEASED` transition the state machine forbids, and would briefly release inventory the
buyer is actively paying for. Here a hold is only ever destroyed by a transaction about to commit.

**Consume is a SQL statement inside the transaction, and that is the whole point.** The claim is an
`UPDATE … WHERE status='ACTIVE'`; it rolls back with the transaction. The earlier design mutated
Redis here (`GETDEL holdmeta`), which cannot roll back — so a failed commit left the claim spent,
the timer key deleted, and no order, and **those seats became permanently unsellable** (ADR-019).

**Redis cleanup is deliberately after the commit and deliberately best-effort.** If step 7 never
runs, `hold:{token}` expires on its own, the expiry handler finds `status = CONSUMED`, and correctly
does nothing.

**Step 4 must abort on failure.** If the extension returns `rowcount = 0` the hold has just been
settled by a concurrent expiry — charging at that point would take money for seats we no longer
hold. This abort was missing from the first pass and is the phantom-hold race closed.

#### Edge cases

| Scenario | Handling |
| :--- | :--- |
| **Card declined** | Order → `FAILED`. **The hold stays `ACTIVE`.** `402 PAYMENT_DECLINED` with `retryable: true` and `attemptsRemaining`. Retry on the same hold and the same `order_number`. Max 3. |
| **Double-click / double submit** | Layer 1: `SETNX payment:inflight:{holdToken}` (90 s). Layer 2: `UNIQUE(hold_token)` — the second request sees `PENDING` and gets `409`. Layer 3: the client `idempotencyKey` reaches Stripe as its `Idempotency-Key` (ADR-014). |
| **3-D Secure required** | `402 PAYMENT_ACTION_REQUIRED` + `resumeUrl`. Client runs `stripe.handleNextAction()`, then calls `POST /api/v1/orders/checkout/resume` with the same `holdToken`. The order stays `PENDING` throughout; the grace extension from step 4 covers the challenge. |
| **Extension fails at step 4** | `410 HOLD_EXPIRED`, **nothing charged**. |
| **Timer expires before submit** | `410 HOLD_EXPIRED`. UI offers re-entry — and the admission session may still be live, so the buyer often does not have to re-queue at all. |
| **Network drops after charge** | Stripe still settles. `payment_intent.succeeded` → webhook (signature-verified) → `PaymentSettledEvent` → `order` finalises the still-`PENDING` order by `hold_token`. |
| **Webhook arrives, hold already gone** | `order` refunds automatically, sets `REFUNDED`, writes a `REFUND_NOTICE` outbox event. The original design would have confirmed an order for seats another buyer already owned (ADR-012). |
| **Charge succeeds, commit fails** | Compensating refund, order → `REFUNDED`, `REFUND_NOTICE` queued. |
| **User cancels** | `DELETE /api/v1/holds/{holdToken}` → claim → stock restored. **Admission session survives**, so they can pick another tier (ADR-020). |
| **User abandons silently** | TTL expires → §4.2. No action needed from anyone. |
| **Tab reloaded anywhere** | `GET /api/v1/sale/{eventId}/state` rehydrates queue position, admission, hold and pending order in one call (ADR-025). |

Note what is *absent*: `payment` never calls `HoldFacade`. Grace extension is requested by `order`;
a decline deliberately retains the hold; abandonment is handled by the TTL. Removing that edge is
what makes the facade graph acyclic (ADR-005).

> **Phase 1 (MVP):** `PaymentFacade` is a stub returning `SUCCEEDED`, but behind the *real*
> interface, in the *real* position in the sequence. No webhook, no refunds. The orchestration —
> including `UNIQUE(hold_token)`, find-or-create, and consume-inside-the-transaction — is built
> correctly from day one, because retrofitting transaction boundaries later is exactly how
> overbooking bugs are born.

---

### Step 5 — Confirmation

`201 Created`:

```json
{
  "orderNumber": "TK-98213",
  "status": "CONFIRMED",
  "userEmail": "buyer@example.com",
  "totalAmountCents": 15000,
  "currency": "USD",
  "receiptToken": "rcp_a91f…",
  "createdAt": "2026-08-30T10:04:12Z",
  "items": [
    { "eventId": 101, "tierId": 501, "tierName": "VIP Admission",
      "quantity": 2, "unitPriceCents": 7500 }
  ]
}
```

`receiptToken` is a signed capability. `GET /api/v1/orders/{orderNumber}` accepts either a matching
`fsid` cookie or this token — the order number alone is not authorisation. In the original design
that endpoint was public and returned the buyer's email against a guessable `TK-98213` reference
(ADR-010).

---

### Step 6 — Asynchronous fulfilment

The relay runs as **three short transactions, never one** (ADR-023):

```
tx1 (short):  UPDATE outbox_events SET status='PROCESSING', claimed_at=now()
               WHERE id IN (SELECT id FROM outbox_events WHERE status='PENDING'
                             ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100)
               RETURNING *;                              -- COMMIT immediately
      ↓
publish to RabbitMQ                                       -- OUTSIDE any transaction
      ↓
tx2 (short):  UPDATE outbox_events SET status='PROCESSED', processed_at=now()
               WHERE id = ANY(?);

order.events.exchange  (topic)  ── order.confirmed ──►  notification.order-confirmed.queue
                                └─ order.refunded  ──►  notification.order-refunded.queue
      ↓
OrderConfirmedConsumer                                    -- no @Transactional around 2-4
   1. INSERT notification_logs (order_number, kind='TICKET_DELIVERY', status='PENDING')
        └─ unique violation ⇒ already handled ⇒ ack and stop
   2. PDFBox renders the ticket in memory
   3. Thymeleaf renders the HTML body
   4. JavaMailSender → SMTP (Mailpit locally)
   5. status='SENT', sent_at=now, basicAck
   ✗ on failure: 3 retries (5s, 30s, 2m) → DLQ, status='DLQ'
```

**`FOR UPDATE SKIP LOCKED`** stops three replicas from publishing the same event three times.

**Publishing sits outside every transaction.** Doing it inside `tx1` would hold row locks across a
network round trip to the broker — and under virtual threads the connection pool is the system's
real concurrency ceiling, so one slow broker would throttle checkout itself. A crash between `tx1`
and `tx2` re-publishes on the next sweep of stale `PROCESSING` rows: at-least-once, which the
consumer's unique constraint absorbs.

**Insert-then-send** is what makes the consumer idempotent: the `UNIQUE(order_number, kind)`
violation is the guard, not a preceding `SELECT`, which two workers could both pass (ADR-015).
PDF rendering and SMTP likewise run outside any transaction.

The payload is a complete snapshot — event name, venue, date, all line items as an array, and the
`receiptToken`. `notification` calls no facade and knows nothing about `catalog`. The original
payload was flat (`tierName`, `quantity`) and would have rendered a wrong PDF for any multi-tier
order.

Admin replay: `POST /api/v1/admin/notifications/resend/{orderNumber}`.

> **Phase 1 (MVP):** the `outbox_events` row is written from day one — it is free and it is part of
> the transaction boundary. A logging publisher drains it. RabbitMQ, PDFBox and SMTP arrive in
> Phase 4 with no change to `order`.

---

## 3. State machines

### Hold

```
                  ┌──────────────── extendHold (once, ≤ +120s, ceiling 420s)
                  │                                        │
                  ▼                                        │
   create ───► ACTIVE ──────────────────────────────────────┘
                 │
                 ├── consumeHold  ──► CONSUMED  (terminal)
                 ├── releaseHold  ──► RELEASED  (terminal)
                 └── TTL / sweeper ─► EXPIRED   (terminal)
```

No transition leaves a terminal state. Every transition is **one conditional statement in
PostgreSQL** — the same statement in every phase (ADR-019):

```sql
UPDATE ticket_holds SET status = ?, settled_at = now(), settle_reason = ?
 WHERE hold_token = ? AND status = 'ACTIVE';      -- rowcount = 1 ⇒ you won the claim
```

Stock is restored **only by the caller that gets `rowcount = 1`**. Everyone else gets `0` and does
nothing.

**PostgreSQL is the authority; Redis holds the timer.** That ordering is what makes the whole
lifecycle safe:

* **Correct across replicas** — all three receive the expiry event, all three run the `UPDATE`,
  PostgreSQL row-locks, exactly one wins. No distributed lock needed.
* **Consume is transactional** — it participates in the order transaction and rolls back with it.
* **Redis needs no claim ticket.** The earlier `holdmeta` + `GETDEL` design existed only because a
  Redis expiry event carries no payload; once PostgreSQL is the authority the handler just reads
  the row. One fewer key, one fewer TTL, one fewer failure mode.
* **Phases 1 and 2 are identical here.** Phase 1 always worked this way.

This replaces ADR-003, whose Redis-side claim mutated state inside the SQL transaction and could
leak inventory permanently when a commit failed.

### Order

```
  (none) ──► PENDING ──► CONFIRMED   ← terminal, success
               │  ▲
               │  └── retry after decline (same order_number)
               ├──► FAILED           ← retryable, hold retained
               └──► REFUNDED         ← terminal; charge settled but seats unobtainable
```

### Payment

```
  INITIATED ──► PROCESSING ──► SUCCEEDED ──► REFUNDED
                     └───────► FAILED
```

---

## 4. Failure and recovery

### 4.1 Redis loss during a live sale

1. Reserve script returns `-2`; holds return `503`; the alarm fires.
2. Acquire `pg_try_advisory_xact_lock(hash('stock-rebuild', eventId))` — transaction-scoped, so it
   cannot leak if the rebuild crashes, and it needs no extra dependency (ADR-022).
3. Recompute per tier:

   ```sql
   remaining = tt.total_capacity
             - COALESCE((SELECT SUM(oi.quantity) FROM order_items oi JOIN orders o ON …
                         WHERE oi.tier_id = tt.id AND o.status = 'CONFIRMED'), 0)
             - COALESCE((SELECT SUM(th.quantity) FROM ticket_holds th
                         WHERE th.tier_id = tt.id AND th.status = 'ACTIVE'
                           AND th.expires_at > now()), 0)
   ```

   No double counting: a `PENDING` order still has an `ACTIVE` hold, a `CONFIRMED` order has a
   `CONSUMED` one.
4. `SET` the counters, release the lock, resume.

The same procedure is **mandatory after any Redis restart**, because AOF `appendfsync everysec` can
lose up to a second of `DECRBY`s — which reads as inventory that does not exist.

### 4.2 Hold expiry

Redis TTL fires → `__keyevent@0__:expired` reaches **all** replicas → each runs
`UPDATE ticket_holds SET status='EXPIRED' WHERE hold_token=? AND status='ACTIVE'` → PostgreSQL
row-locks and exactly one gets `rowcount = 1` → that one issues
`INCRBY catalog:stock:{e}:{t} qty` and publishes `TicketHoldExpiredEvent`.

If the row is already `CONSUMED` — the normal case when step 7 of checkout did not get to clean up —
every replica gets `rowcount = 0` and nothing happens. Correct by construction.

`HoldReconciliationSweeper` runs every 30 s over
`ticket_holds WHERE status='ACTIVE' AND expires_at < now()` and performs the identical claim. The
listener is therefore a **latency optimisation, not a correctness requirement** — keyspace pub/sub
is at-most-once, and the sweeper is what makes the system correct without it.

Requires `notify-keyspace-events Ex` (off by default). `E` is the key-**event** channel
(`__keyevent@0__:expired`, message = the key name) — which is what the listener needs. `K` is the
keyspace channel and carries the event name instead, so `Kx` would leave the listener silent.
Shipped in [`docker/redis/redis.conf`](../docker/redis/redis.conf).

### 4.3 Component outages

| Down | Effect | Behaviour |
| :--- | :--- | :--- |
| PostgreSQL | browse and queue survive on Redis | checkout `503`; no data loss (holds are in Redis) |
| Redis | fatal for sale operations | `503` + rebuild per §4.1; browse degrades to PostgreSQL |
| Stripe | Resilience4j circuit opens | `503` + retry guidance; **holds retained**, not destroyed |
| RabbitMQ | fulfilment stalls | orders still commit; outbox drains on recovery |
| SMTP | delivery stalls | 3 retries → DLQ → admin replay |
| reCAPTCHA | verification unavailable | **fail open**, rely on rate limits — a deliberate availability-over-security trade (ADR-011) |

---

## 5. Facade contracts

Rules every facade obeys — synchronous, never `@Transactional` itself, records not entities,
module-owned exceptions only — are in
[`05-global-standards.md`](05-global-standards.md#5-facade-contract-rules) §5.

| Caller | Facade | Method | Purpose |
| :--- | :--- | :--- | :--- |
| filter | `BotFacade` | `authorize(ip, sid, path)` | buckets + block flags |
| filter | `BotFacade` | `verifyCaptcha(token, action, sid)` | cached reCAPTCHA score |
| `hold` | `QueueFacade` | `verifyAdmission(token, sid, eventId)` | live admission session (ADR-020) |
| `hold` | `CatalogFacade` | `getTierSummary(eventId, tierId)` | validity, price, window |
| `order` | `HoldFacade` | `getActiveHold(token, sid)` | read-only, ownership-checked |
| `order` | `HoldFacade` | `extendHold(token, seconds)` | bounded grace; **fails ⇒ abort** |
| `order` | `HoldFacade` | `consumeHold(token)` | claim — **joins the caller's transaction** |
| `order` | `HoldFacade` | `releaseHold(token, reason)` | claim |
| `order` | `CatalogFacade` | `getTierSummary(eventId, tierId)` | price snapshot |
| `order` | `PaymentFacade` | `authorize(orderNumber, amount, currency, pm, key)` | charge |
| `order` | `PaymentFacade` | `refund(txnRef, amount, reason)` | compensation |
| `order` | `QueueFacade` | `revokeAdmission(sid, eventId)` | after `CONFIRMED`, post-commit |
| `saleflow` | `QueueFacade` | `getQueueState(sid, eventId)` | rehydration (ADR-025) |
| `saleflow` | `HoldFacade` | `findActiveHold(sid, eventId)` | rehydration |
| `saleflow` | `OrderFacade` | `findPendingOrder(sid, eventId)` | rehydration |
| `saleflow` | `CatalogFacade` | `getEventDetail(eventId)` | rehydration |
| admin | `NotificationFacade` | `resend(orderNumber, kind)` | DLQ replay |

`verifyPassToken` / `revokePassToken` moved off `hold` and onto the `POST /api/v1/queue/admit`
handler inside `queue` itself — the pass is now exchanged for an admission session rather than
consumed at hold creation (ADR-020).

Events — the only asynchronous couplings:

| Event | Publisher | Consumer | Transport |
| :--- | :--- | :--- | :--- |
| `PaymentSettledEvent` | `payment` (webhook only) | `order` | Spring in-process |
| `ORDER_CONFIRMED` | `order` | `notification` | outbox → RabbitMQ |
| `ORDER_REFUNDED` | `order` | `notification` | outbox → RabbitMQ |
| `EventPrewarmedEvent` | `catalog` | monitoring | Spring in-process |
| `TicketHoldExpiredEvent` | `hold` | monitoring | Spring in-process |
| `BotAttackDetectedEvent` | `bot` | monitoring | Spring in-process |

---

## 6. Tunables

Every value below is a named property in `application.properties`.

| Setting | Value | ADR |
| :--- | :--- | :--- |
| Queue pass TTL | 120 s, single-use | 006 / 020 |
| **Admission session TTL** | **600 s**, reusable | **020** |
| **Admission oversubscribe factor** | **1.5** | **020** |
| **Queue ordering** | **`FIFO` (default) or `RANDOM`** | **024** |
| Hold TTL | 300 s | 006 |
| Payment grace | +120 s once, ceiling 420 s | 006 |
| Checkout after `sale_end_time` | 15 min | 016 |
| Tickets per hold | 6 | 017 |
| Active holds per session per event | 1 | 017 |
| Charge attempts per hold | 3 | 014 |
| `payment:inflight` TTL | 90 s | 014 |
| Promotion tick | 1 s | 008 |
| Promotion batch size | **≤ `hikariMax × 1.5`** (45) | **028** |
| Queue heartbeat | 90 s, advisory only | **026** |
| SSE position push / heartbeat | 2 s / 15 s | 007 |
| Sweeper interval | 30 s (Phase 2+); 10 s (Phase 1) | 019 |
| Outbox poll / batch | 1 s / 100, `SKIP LOCKED` | 009 / 023 |
| Session bucket | 20 burst, 10/s | 011 |
| IP bucket | 300 burst, 150/s | 011 |
| HikariCP pool | 30 max, 10 idle | std §7 |
| Availability buckets | `SOLD_OUT` 0 · `LIMITED` < 10 % · else `PLENTY` | **027** |
| reCAPTCHA threshold / cache | 0.5 / 30 min | 011 |

---

## 7. Metrics and operational controls

Nothing in the original design was observable. At minimum:

| Metric | Alarm |
| :--- | :--- |
| `flashseats.queue.depth{event}` | — |
| `flashseats.queue.promotion.rate{event}` | zero while queue depth > 0 |
| `flashseats.hold.conversion.ratio{event}` | sustained < 0.3 |
| `flashseats.stock.drift{event,tier}` | **any non-zero value** |
| `flashseats.outbox.lag.seconds` | > 60 |
| `flashseats.dlq.depth{queue}` | > 0 |
| `flashseats.payment.decline.ratio` | > 0.2 |
| `flashseats.sse.connections.active` | — |

`stock.drift` compares the live Redis counter against the §4.1 formula every 60 s. It is the
system's canary: a non-zero value means inventory accounting has diverged, and it should page.

Controls: `POST /api/v1/admin/events/{id}/pause` (stop promotions and new holds, honour existing
ones) and `POST /api/v1/admin/events/{id}/rebuild-stock`. There was previously no way to stop a
sale that was going wrong.
