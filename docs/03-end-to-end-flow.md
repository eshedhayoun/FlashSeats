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
|  ticket_tiers, |   | Redis: ZSET,   |          |     holds      |    | order_items,   |
|  tier_inventory|   |  pass, pubsub  |          | Redis: hold:,  |    | outbox_events  |
| Redis: stock   |   |                |          |  holdmeta:     |    |                |
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

### Facade graph (acyclic — see ADR-005)

```
filter  ──► bot
hold    ──► queue, catalog
order   ──► hold, catalog, payment
payment ──( PaymentSettledEvent — webhook path only )──► order
order   ──( outbox → RabbitMQ )──► notification
```

`ApplicationModules.verify()` enforces this at build time. There is no illegal cross-module
database access anywhere in the design: a module reads only its own tables and its own Redis
key prefixes.

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
    { "tierId": 501, "tierName": "VIP", "priceCents": 7500, "available": 120, "soldOut": false }
  ]
}
```

The browser counts down against `serverTime + elapsedLocalTime`, never against the device clock.
Without this, every client's skew smears the start of the sale and the fairness of the ZSET
ordering becomes a lie (ADR-016).

`windowStatus` drives the UI directly: `UPCOMING` → countdown, "Join" disabled; `OPEN` → "Join Flash
Sale" enabled; `CLOSED` → sale-ended panel.

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

Abandoned entries are evicted by a `queue:hb:{sid}` heartbeat so `estWaitSeconds` stays honest.

> **Phase 1 (MVP):** no queue module at all. The user goes straight from the landing page to seat
> selection. Nothing downstream depends on the queue for correctness — the pass is an
> *admission-control* device, not a *safety* device. Overbooking is prevented in `hold`.

---

### Step 3 — Seat selection and holding

`POST /api/v1/holds`, header `X-Queue-Pass-Token`:

```json
{ "eventId": 10024, "tierId": 501, "quantity": 2 }
```

`userSessionId` is **not** in the body. It comes from the signed `fsid` cookie; a client-supplied
identity would let anyone act as anyone (ADR-010).

1. `QueueFacade.verifyPassToken(token, sid, eventId)` — HMAC signature plus a live
   `queue:pass:{sid}`.
2. `CatalogFacade.getTierSummary(eventId, tierId)` — tier exists, belongs to the event, window is
   `OPEN`.
3. Limits: `quantity ≤ 6`; at most one `ACTIVE` hold per session per event (ADR-017).
4. `hold_reserve.lua` — one atomic script:

```lua
local stockKey, holdKey, metaKey = KEYS[1], KEYS[2], KEYS[3]
local qty, ttl = tonumber(ARGV[1]), tonumber(ARGV[2])

local stock = redis.call('GET', stockKey)
if stock == false then return -2 end          -- key absent: FAULT, never "sold out" (ADR-004)
if tonumber(stock) < qty then return -1 end   -- genuinely insufficient

redis.call('DECRBY', stockKey, qty)
redis.call('HSET', holdKey,
    'userSessionId', ARGV[3], 'eventId', ARGV[4], 'tierId', ARGV[5],
    'quantity', tostring(qty), 'status', 'ACTIVE', 'expiresAt', ARGV[6])
redis.call('EXPIRE', holdKey, ttl)
-- the settle-once claim ticket; outlives the hold so expiry can still be settled (ADR-003)
redis.call('SET', metaKey, ARGV[4]..':'..ARGV[5]..':'..qty..':'..ARGV[3], 'EX', 86400)
return 1
```

5. `QueueFacade.revokePassToken(sid)` — **the pass is single-use.** It was never revoked in the
   original design, so one promoted session could mint holds for a full five minutes and drain a
   tier (ADR-006).
6. Async: insert the `ticket_holds` audit row.

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
4. HoldFacade.extendHold(holdToken, 120s)        → once, ceiling 420s total (ADR-006)
5. PaymentFacade.authorize(orderNumber, amountCents, currency, pmId, idempotencyKey)
6. @Transactional {
       HoldFacade.consumeHold(holdToken)         → settle-once claim
       orders.status = CONFIRMED
       INSERT order_items
       INSERT outbox_events (ORDER_CONFIRMED, PENDING)
   }
7. 201 Created + OrderReceiptDTO
```

**Charge first, consume second.** Consuming the hold before charging would require a
`CONSUMED → RELEASED` transition the state machine forbids, and would briefly release inventory the
buyer is actively paying for. Here a hold is only ever destroyed by a transaction that is about to
commit.

#### Edge cases

| Scenario | Handling |
| :--- | :--- |
| **Card declined** | Order → `FAILED`. **The hold stays `ACTIVE`.** `402` with `retryable: true` and `attemptsRemaining`. The user retries on the same hold and the same `order_number`. Max 3 attempts. |
| **Double-click / double submit** | Layer 1: `SETNX payment:inflight:{holdToken}` (90 s). Layer 2: `UNIQUE(hold_token)` — the second request sees a `PENDING` row and gets `409`. Layer 3: the client `idempotencyKey` reaches Stripe as its `Idempotency-Key` (ADR-014). |
| **Timer expires mid-3DS** | Step 4 already extended the hold by 120 s and pushed `ticket_holds.expires_at`. Without the `expires_at` push the sweeper would reclaim the seat mid-challenge. |
| **Timer expires before submit** | `409 HOLD_EXPIRED_OR_INVALID`. The UI shows "your reservation expired" and offers re-entry to the queue. Nothing was charged. |
| **Network drops after charge** | Stripe still settles. `payment_intent.succeeded` → `POST /api/v1/payments/webhook` (signature-verified) → `PaymentSettledEvent` → `order` finalises the still-`PENDING` order by `hold_token`. |
| **Webhook arrives, hold already gone** | `order` refunds automatically, sets `REFUNDED`, and writes a `REFUND_NOTICE` outbox event so the buyer is told. **This is the case the original design got wrong** — it would have charged a customer for seats another buyer already owned (ADR-012). |
| **Charge succeeds, commit fails** | Compensating refund via `PaymentFacade.refund()`, order → `REFUNDED`, `REFUND_NOTICE` queued. |
| **User cancels** | `DELETE /api/v1/holds/{holdToken}` → settle-once claim → stock restored immediately. |
| **User abandons silently** | TTL expires → §4.2. No action needed from anyone. |

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

```
outbox_events(PENDING)
   │  poller, every 1s:
   │  SELECT … WHERE status='PENDING' ORDER BY created_at
   │  FOR UPDATE SKIP LOCKED LIMIT 100
   ▼
order.events.exchange  (topic)  ── order.confirmed ──►  notification.order-confirmed.queue
                                └─ order.refunded  ──►  notification.order-refunded.queue
   ▼
OrderConfirmedConsumer
   1. INSERT notification_logs (order_number, kind='TICKET_DELIVERY', status='PENDING')
        └─ unique violation ⇒ already handled ⇒ ack and stop
   2. PDFBox renders the ticket in memory
   3. Thymeleaf renders the HTML body
   4. JavaMailSender → SMTP (Mailpit locally)
   5. status='SENT', sent_at=now, basicAck
   ✗ on failure: 3 retries (5s, 30s, 2m) → DLQ, status='DLQ'
```

**`FOR UPDATE SKIP LOCKED`** stops three replicas from publishing the same event three times.
**Insert-then-send** is what makes the consumer idempotent: the `UNIQUE(order_number, kind)`
violation is the guard, not a preceding `SELECT`, which two workers could both pass (ADR-015).

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

No transition leaves a terminal state. Every transition begins with the **settle-once claim**:

* Phase 2+: `GETDEL holdmeta:{holdToken}` — atomic, so exactly one caller across all replicas
  receives the value; everyone else gets `nil` and does nothing.
* Phase 1: `UPDATE ticket_holds SET status=? WHERE hold_token=? AND status='ACTIVE'` — restore only
  when `rowcount = 1`.

Stock is restored **only by the claim winner**. This single primitive fixes three separate bugs at
once: triple-restoration from replica-wide keyspace pub/sub, the undefined "shadow record" the
expiry listener was supposed to read, and the race between `releaseHold` and a concurrent TTL
expiry (ADR-003).

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
2. Acquire `RedissonLock:stock-rebuild:{eventId}`.
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

Redis TTL fires → `__keyevent@0__:expired` reaches **all** replicas → each attempts
`GETDEL holdmeta:{token}` → exactly one wins → `INCRBY catalog:stock:{e}:{t} qty`, audit row to
`EXPIRED`, `TicketHoldExpiredEvent`.

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

| Caller | Facade | Method | Purpose |
| :--- | :--- | :--- | :--- |
| filter | `BotFacade` | `authorize(ip, sid, path)` | buckets + block flags |
| filter | `BotFacade` | `verifyCaptcha(token, action)` | cached reCAPTCHA score |
| `hold` | `QueueFacade` | `verifyPassToken(token, sid, eventId)` | HMAC + live pass key |
| `hold` | `QueueFacade` | `revokePassToken(sid)` | single-use enforcement |
| `hold` | `CatalogFacade` | `getTierSummary(eventId, tierId)` | validity, price, window |
| `order` | `HoldFacade` | `getActiveHold(token, sid)` | read-only, ownership-checked |
| `order` | `HoldFacade` | `extendHold(token, seconds)` | bounded grace |
| `order` | `HoldFacade` | `consumeHold(token)` | settle-once claim |
| `order` | `HoldFacade` | `releaseHold(token, reason)` | settle-once claim |
| `order` | `CatalogFacade` | `getTierSummary(eventId, tierId)` | price snapshot |
| `order` | `PaymentFacade` | `authorize(orderNumber, amount, currency, pm, key)` | charge |
| `order` | `PaymentFacade` | `refund(txnRef, reason)` | compensation |
| admin | `NotificationFacade` | `resend(orderNumber)` | DLQ replay |

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

| Setting | Value | ADR |
| :--- | :--- | :--- |
| Queue pass TTL | 120 s, single-use | 006 |
| Hold TTL | 300 s | 006 |
| Payment grace | +120 s once, ceiling 420 s | 006 |
| Checkout after `sale_end_time` | 15 min | 016 |
| Tickets per hold | 6 | 017 |
| Active holds per session per event | 1 | 017 |
| Charge attempts per hold | 3 | 014 |
| `payment:inflight` TTL | 90 s | 014 |
| Promotion tick | 1 s | 008 |
| SSE position push / heartbeat | 2 s / 15 s | 007 |
| Sweeper interval | 30 s (Phase 2+); 10 s (Phase 1) | 003 |
| Outbox poll / batch | 1 s / 100, `SKIP LOCKED` | 009 |
| Session bucket | 20 burst, 10/s | 011 |
| IP bucket | 300 burst, 150/s | 011 |
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
