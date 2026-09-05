# Implementation Roadmap

> **Principle:** every phase ends with a system that is *correct*, not merely *smaller*. Phase 1
> already makes overbooking impossible. Later phases make an already-correct system fast, defended,
> and observable. Concurrency guarantees are never retrofitted — that is precisely how overbooking
> bugs are born.

> **Where this stands.** The MVP cut a thin slice through *all four* phases rather than completing
> them in order: everything the journey needs is built, everything additive is deferred. See
> [`06-mvp-overview.md`](06-mvp-overview.md) §4 for exactly what ships and §11 for what comes next.
> The phase table below remains the plan for finishing each one properly.

| Phase | Objective | Core work | Exit criterion |
| :--- | :--- | :--- | :--- |
| **1** | Correct single-user transaction | catalog, hold, mock payment, order, outbox rows | Two parallel requests for the last ticket → exactly one succeeds — **done, and tested** |
| **2** | Move the hot path to RAM | Redis stock + Lua, ZSET queue, SSE, pass tokens | Same guarantee at 1,000 concurrent requests — **queue, SSE and passes done; Lua and the rebuild are not** |
| **3** | Defence and real money | bot, Stripe, webhooks, Resilience4j | Payments survive tab closure; floods are throttled — **cookie identity and rate limits done; Stripe and reCAPTCHA are not** |
| **4** | Async fulfilment and scale | RabbitMQ, PDFBox, email, Nginx, k6 | 10,000 users / 500 tickets / zero overbooking / 500 emails — **fulfilment done on one replica; the cluster and load runs are not** |
| **5** | Operate it, and let buyers return | The operator surface (ADR-043); buyer accounts as an overlay (ADR-044) | A dead-lettered ticket can be replayed by a human; a lost counter can be rebuilt without SQL; a buyer finds their order more than 24 h later |

---

## Phase 1 — Correct transactional core

### Objective
A single buyer completes a purchase, and two concurrent buyers cannot both get the last ticket.

### Build

**`catalog`** — `events`, `ticket_tiers`, `tier_inventory`. `GET /api/v1/events`,
`GET /api/v1/events/{id}` returning `windowStatus` **and `serverTime`**.
`CatalogFacade.getTierSummary()`, `tryReserve()`, `restore()`.

**`hold`** — `ticket_holds` with the full state machine (`ACTIVE → CONSUMED | RELEASED | EXPIRED`).
Reserve via the single atomic statement:

```sql
UPDATE tier_inventory SET remaining = remaining - :q
 WHERE tier_id = :t AND remaining >= :q;   -- rowcount = 1 ⇒ reserved
```

Settle via the conditional update:

```sql
UPDATE ticket_holds SET status = :s WHERE hold_token = :t AND status = 'ACTIVE';
-- restore stock only when rowcount = 1
```

`HoldReconciliationSweeper` every 10 s for expiry. **No Redis, no TTL listener, no distributed
locks.** This conditional `UPDATE` is the claim mechanism for every later phase too — it does not
get replaced, only accelerated (ADR-019).

**`payment`** — `PaymentFacade` returning `SUCCEEDED`, plus `payment_transactions` rows. A stub
behind the real interface, in the real position in the sequence.

**`order`** — `POST /api/v1/orders/checkout` with the complete orchestration from ADR-001:
find-or-create on `UNIQUE(hold_token)` → price server-side → charge → consume + commit + outbox in
one transaction. `GET /api/v1/orders/{orderNumber}` gated by session or `receiptToken`.
`outbox_events` rows are written from day one; a logging publisher drains them.

**Cross-cutting** — `spring.threads.virtual.enabled=true`; a `ModularityTests` class calling
`ApplicationModules.verify()`; Docker Compose with PostgreSQL only.

### Deliberately deferred
Queue, bot, Redis, RabbitMQ, PDF, email, Nginx.

### Why this is already correct
Overbooking is prevented by a row-locked conditional `UPDATE` plus `CHECK (remaining >= 0)`.
Double-spend of a hold is prevented by `UNIQUE(hold_token)` and the settle-once claim. The
transaction boundary — consume the hold *inside* the commit — is right from the first line of code.

### Exit criteria
- [ ] Full purchase completes end to end via API.
- [ ] Two parallel requests for the final ticket: exactly one `201`, one clean `409`.
- [ ] An abandoned hold returns to stock within ~10 s, exactly once.
- [ ] A double-submitted checkout produces one order and one charge.
- [ ] `ApplicationModules.verify()` passes.

---

## Phase 2 — Redis fast path and the waiting room

### Objective
Move inventory to RAM without weakening any Phase 1 guarantee, and add admission control.

### Build

**`catalog`** — Redis counters, `SETNX` pre-warm **restricted to `UPCOMING`**, and the locked
rebuild procedure (`00-architecture-decisions.md` ADR-004). Redis configured with
`maxmemory-policy noeviction`, AOF `everysec`, and `notify-keyspace-events **Ex**` — all three
already shipped in [`docker/redis/redis.conf`](../docker/redis/redis.conf).

**`hold`** — `hold_reserve.lua` and `hold_restore.lua`. **The claim stays exactly where it was in
Phase 1**: a conditional `UPDATE` on `ticket_holds` (ADR-019). Redis gains the TTL timer and the
keyspace listener as a latency optimisation; the sweeper (now 30 s) remains the correctness
guarantee. `extendHold()` — once, +120 s, ceiling 420 s, pushing `ticket_holds.expires_at`, and
**failing the checkout rather than charging** if it cannot win the claim.

**`queue`** — ZSET with `ZADD NX` (`FIFO` or `RANDOM` score — ADR-024); `GET /api/v1/queue/stream`
with a 15 s heartbeat and `Last-Event-ID`; `GET /api/v1/queue/status` returning the pass as a
polling fallback; HMAC passes (120 s, single-use); **`POST /api/v1/queue/admit` exchanging a pass
for a 600 s admission session** (ADR-020); promotion worker bounded by
`min(batchSize, floor(remainingStock × 1.5) − pendingPasses − liveAdmissions)`; **Redis Pub/Sub
fan-out on `queue:events:{eventId}`**; `sale-exhausted` and `sale-closed` frames; monotonic
position clamping.

**`saleflow`** — `GET /api/v1/sale/{eventId}/state` rehydration endpoint (ADR-025).

### Traps this phase exists to avoid
1. **Repopulating stock from `total_capacity`** on a cache miss — resurrects sold tickets. Return
   `-2`, alarm, rebuild.
2. **Restoring stock once per replica** — keyspace expiry is broadcast pub/sub. The conditional
   `UPDATE` claim handles it; do **not** reach for a distributed lock.
3. **Testing SSE on one instance.** Promotion fan-out works perfectly on one replica and drops
   two-thirds of passes on three. Test with ≥ 2 replicas or the bug stays hidden until Phase 4.

### Exit criteria
- [ ] 1,000 concurrent requests for 100 tickets → exactly 100 sold.
- [ ] `FLUSHDB` mid-sale → holds return `503`, rebuild restores the exact correct count.
- [ ] With 2 replicas, every promoted user receives a pass (pub/sub verified).
- [ ] Keyspace listener disabled → sweeper still restores every expired hold, exactly once.
- [ ] A refresh mid-queue preserves position (`ZADD NX`).
- [ ] `stock.drift` reads zero throughout.

---

## Phase 3 — Defence and real payments

### Objective
Survive hostile traffic and real-world payment failure modes.

### Build

**`bot`** — signed `fsid` cookie (`HttpOnly; Secure; SameSite=Lax`, HMAC-suffixed); Redis-backed
Bucket4j with the **session bucket primary** and IP as a coarse backstop; SSE exempt from
per-request accounting; reCAPTCHA v3 on `POST /queue/join` only, cached per session for 30 min,
failing open; `ip_rules` and `bot_audit_logs`.

**`payment`** — Stripe SDK (test mode); `POST /api/v1/payments/webhook` with signature
verification; `PaymentSettledEvent`; three-layer idempotency (ADR-014); Resilience4j circuit breaker
and retry; `refund()`.

**`order`** — decline handling that **retains the hold** with `retryable: true` and
`attemptsRemaining`; webhook finalisation of a `PENDING` order by `hold_token`; **auto-refund when
the hold is gone**, with a `REFUND_NOTICE` outbox event.

### Exit criteria
- [ ] A flood from one IP is throttled; a legitimate buyer behind the same NAT still succeeds.
- [ ] Tab closed after submit → webhook completes the order; exactly one charge.
- [ ] Tab closed **and** hold expired → automatic refund, order `REFUNDED`, buyer notified.
- [ ] Declined card → hold retained, retry succeeds on the same `order_number`.
- [ ] Stripe unreachable → circuit opens, `503`, holds **not** destroyed.
- [ ] reCAPTCHA unreachable → fail open, rate limits still enforced.

---

## Phase 4 — Async fulfilment, scale, load proof

### Objective
Get heavy work off the request path, run multi-replica, and prove the whole thing under load.

### Build

**`notification`** — outbox poller with `FOR UPDATE SKIP LOCKED`; RabbitMQ topology
(`order.events.exchange`, `notification.order-confirmed.queue`, DLX, DLQ); PDFBox; Thymeleaf;
`JavaMailSender` → Mailpit; `notification_logs` with `UNIQUE(order_number, kind)` and
insert-then-send; `REFUND_NOTICE` template; admin resend.

**Infrastructure** — Docker Compose for all services; Nginx across 3 replicas with
`proxy_buffering off` and `proxy_read_timeout 3600s`; Redis Sentinel.

**Observability** — the metric set in `03-end-to-end-flow.md` §7; the `stock.drift` alarm;
`POST /admin/events/{id}/pause` and `/rebuild-stock`.

**Load harness** — k6 firing 10,000 virtual users at Nginx.

### Exit criteria
- [ ] **10,000 users, 500 tickets, exactly 500 sold, zero overbooking.**
- [ ] 500 PDF emails land in Mailpit; zero duplicates; DLQ empty.
- [ ] Killing one replica mid-sale loses no orders and no stock.
- [ ] Restarting Redis mid-sale → reconciliation restores the exact count.
- [ ] Checkout p99 under 200 ms at peak.
- [ ] `stock.drift` zero for the entire run.
- [ ] Every SSE client receives its promotion across all 3 replicas.

---

## Cross-phase invariants

These must hold at the end of **every** phase:

1. `SUM(confirmed sold) + SUM(active holds) + remaining == total_capacity`, always.
2. No order exists without exactly one settled hold.
3. No charge exists without an order row.
4. No confirmed order exists without an outbox row.
5. Stock is restored **exactly once** per hold, by whichever path settles it first.
6. `ApplicationModules.verify()` passes.

Invariant 1 is the `stock.drift` metric. Wire it in Phase 1 and never let it go non-zero.

---

## Phase 5 — Operate it, and let buyers return

### Objective
Make every failure the earlier phases deliberately routed somewhere retrievable by a human, and give
a buyer a durable way back to what they bought.

### Build

**The operator surface (ADR-043)** — endpoints in the module that owns the state, under
`/api/v1/admin/**`, `ROLE_ADMIN`. There is no `admin` module: one would have to read every other
module's internals, which is the boundary violation `ApplicationModules.verify()` exists to reject.

* `catalog` — `pause`, `rebuild-stock`. The rebuild is **ADR-004's only legal recovery** from a
  missing counter and is specified in three documents and implemented nowhere.
* `notification` — DLQ inspection and `resend`. **ADR-029's premise.** Sending deterministic
  failures straight to the DLQ with no retries is right *only if someone can replay them*; without
  a replay path the DLQ is where paid buyers' tickets go to be forgotten.
* `order` — order lookup for support.
* Replace the in-memory `UserDetailsService` first (`06-mvp-overview.md` §10 S12).

**Buyer accounts as an overlay (ADR-044)** — a new leaf module `account`. `fsid` remains the only
session identity and ADR-010 is unchanged; an account attaches to *purchases* and to nothing else.
`orders.account_id` is nullable and stamped at checkout only, inside the existing transaction. An
anonymous order is claimable later with its `receiptToken`.

**PII (S9)** — a deletion path and a stated retention period ship with the accounts work, not after
it. Plaintext email hanging off a named account is a different obligation from the anonymous case.

### Traps this phase exists to avoid

1. **An `admin` module.** Ownership does not change because the caller is an operator.
2. **Replacing `fsid` with an account id.** Queue join, holds and rate limits must serve visitors who
   have never signed in, and ADR-010's guarantee is that identity has exactly *one* source.
3. **Requiring login to enter the queue** as a technical decision. It is a product one; the design
   supports either, and the default is not to require it.
4. **Building a console before the endpoints.** The endpoints are the capability and are usable with
   `curl`; a UI is presentation.

### Exit criteria
- [ ] A dead-lettered ticket is visible, replayable, and actually arrives on replay.
- [ ] A `FLUSHDB` mid-sale is recovered through `rebuild-stock` with no manual SQL.
- [ ] A paused sale stops promoting and stops accepting new holds, and honours existing ones.
- [ ] A buyer signs in 48 h later — past the cookie's `max-age` — and finds their order.
- [ ] An anonymous purchase is claimed into an account with its `receiptToken`.
- [ ] Anonymous checkout still completes end to end, untouched.
- [ ] A deletion request removes the buyer's PII and leaves the ledger's integrity intact.
