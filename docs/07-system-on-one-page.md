# FlashSeats — the system on one page

> **Read this after the README and before anything in `docs/`.** It is the whole system in one place:
> the journey, the module graph, the checkout sequence, where each concept lives, and the parts that
> look removable but are not. The documents listed in §1.6 own the detail.
>
> It began as Part 1 of `REFACTORING_BLUEPRINT.md` (Pass 10, ADR-057). That file's refactor steps
> and checklist were history and backlog, so they were retired in Pass 15: what is still open moved
> to [`06-mvp-overview.md`](06-mvp-overview.md) §11, and git keeps the rest.

## 1.1 The whole journey

Nine steps, all real HTTP. The demo client at `/` is one consumer of it.

| # | Call | Lands in | Gated by |
| :-- | :--- | :--- | :--- |
| 1 | `GET /api/v1/events/{id}` | `EventController` → `CatalogService` | mints the signed `fsid` cookie |
| 2 | `POST /api/v1/queue/join` | `QueueController` → `BotFacade`, then `QueueService` | the challenge check, which **fails open** (ADR-055); sale window `OPEN` or `PAUSED` (ADR-066); `ZADD NX` so a refresh keeps your place |
| 3 | `GET /api/v1/queue/stream` | `QueueController` → `SseEmitterRegistry` | SSE. `GET /queue/status` is the polling equivalent |
| 4 | *(worker, 1 s)* | `PromotionWorker` | cluster-wide `queue:budget`, then the per-event batch |
| 5 | `POST /api/v1/queue/admit` | `QueueService.admit` | pass is single-use — revoked here |
| 6 | `POST /api/v1/holds` | `HoldController` → `HoldService` | `X-Admission-Token`; reserve in Redis, then insert the row |
| 7 | `POST /api/v1/orders/checkout` | `CheckoutService` → `OrderCommitService` | the sequence in §1.3 |
| 8 | `GET /api/v1/orders/{n}` | `OrderQueryService` | matching `fsid` **or** `?receiptToken=` |
| 9 | *(async)* | outbox → RabbitMQ → PDFBox → SMTP | `UNIQUE(order_number, kind)` |

`GET /api/v1/sale/{eventId}/state` returns the caller's exact position in all of the above. It is
what makes a page reload cost nothing.

## 1.2 The module graph

Nine modules. The graph is **acyclic and build-enforced** — `ApplicationModules.verify()` in
`ModularityTests` fails the build otherwise.

```
                         shared          ← open module; everyone may depend on it
                                            (ErrorCode, SessionId, SignedToken, Clock, TicketPdfRenderer)

  bot      ──► shared only          filters, rate limits, IP rules, the challenge check
  catalog  ──► shared               events, tiers, sale windows, AND the Redis inventory counter
  queue    ──► catalog, bot         waiting room, promotion, admission. `bot` only on join
  hold     ──► queue, catalog       ticket_holds is the authority for a reservation's lifecycle
  order    ──► hold, catalog,       checkout, the outbox, the stock rebuild
               payment, queue
  payment  ──► (nothing)            calls NO facade — an edge here would make the graph cyclic
  saleflow ──► queue, hold,         read-only leaf; nothing depends on it
               order, catalog

  payment  ──( PaymentSettledEvent )──►  order          the only inbound edge, webhook path only
  order    ──( outbox → RabbitMQ )──►    notification   reached by the broker, never by a call
```

**Read the bottom two arrows differently from the top block.** Those are not facade calls. `payment`
publishes a Spring event that `order` listens for, and `order` writes an outbox row that RabbitMQ
delivers to `notification`. Both directions would be cycles as facade edges — which is exactly why
neither is one (ADR-005, ADR-009).

**Why nine modules for a codebase this size** — the question everyone asks once:

| Module | Exists because |
| :--- | :--- |
| `shared` | The only things every module may name. Open by declaration, so it cannot accumulate logic quietly. |
| `bot` | Filters run before Spring Security and before any controller. Keeping them a module keeps them out of the domain. |
| `catalog` | Owns `events`, `ticket_tiers` **and** `catalog:stock:{e}:{t}`. One owner for the counter is the whole no-oversell story. |
| `queue` | Owns every `queue:*` key. Admission is the only thing that bounds load on everything downstream. |
| `hold` | `ticket_holds` is the authority; Redis holds only a count. The settle-once claim lives here. |
| `payment` | Calls no facade **by design**. Adding one edge makes the graph cyclic (ADR-005). |
| `order` | The only module allowed to orchestrate. Owns no Redis keys at all. |
| `notification` | Reached only by the broker, so a slow mail server cannot touch checkout. |
| `saleflow` | Reads four facades to answer one question. **The only place that read can live** without making the graph cyclic. |

## 1.3 Checkout, in order

The sequence *is* the design. Read `CheckoutService.checkout()` alongside this.

```
0  already CONFIRMED for this hold?   → return the receipt, 200
1  getActiveHold(token, sid)          → 404/410 if missing, expired or not yours
2  getTierSummary()                   → price computed SERVER-SIDE; no client value reaches it
3  window gate                        → OPEN or PAUSED, or CLOSED within 15 min (ADR-066)
4  find-or-create on UNIQUE(hold_token)
5  grantGrace()                       → once per hold. FAILS ⇒ abort 410, DO NOT CHARGE
6  authorize()                        → OUTSIDE every transaction
6b 3-D Secure?                        → 402 + clientSecret; order left FAILED, no attempt consumed.
                                        The client authenticates and re-POSTs THIS SAME body —
                                        find-or-create resumes it (ADR-054). No resume endpoint.
7  @Transactional                     → consumeHold · CONFIRMED · order_items · outbox_events
8  AFTER_COMMIT                       → discardTimer, revokeAdmission — best-effort, safe to lose
9  lost the hold after a charge?      → the order row decides (ADR-064): CONFIRMED by the webhook
                                        ⇒ the receipt; otherwise claim REFUNDED, refund, then notify.
                                        Any OTHER commit failure moves no money: FAILED, and the
                                        retry reuses the charge that settled
```

**Step 0 has to be first.** A successful purchase consumes its hold, so validating the hold first
would answer a resubmission with "your reservation expired" when the buyer already owns the seats.

**Step 7 is one transaction containing only SQL.** `consumeHold` is a conditional `UPDATE` that joins
it, so if anything fails the hold returns to `ACTIVE` and expires normally.

**A gone hold is not gone seats.** Only this order can consume its hold, so a `CONSUMED` hold means
the webhook already confirmed this same charge. Confirming and claiming a refund are both
compare-and-sets on the `orders` row, so the two paths cannot both win (ADR-064).

## 1.4 Where each concept lives — once

| Concept | The one place | The guarantee it carries |
| :--- | :--- | :--- |
| The live inventory count | `catalog:stock:{e}:{t}` in Redis, moved only by `StockCounterRepository` | `stock_reserve.lua` — GET, compare, DECRBY in one atomic step |
| The reservation lifecycle | `ticket_holds` in PostgreSQL | `TicketHoldRepository.settle` — `UPDATE … WHERE status='ACTIVE'`; only `rowcount = 1` restores |
| One hold never becomes two orders | `UNIQUE(hold_token)` on `orders` | the constraint, not call-site discipline |
| Confirmed ⇒ fulfilment queued | `outbox_events`, written inside step 7's transaction | all-or-nothing with the order |
| No event published twice | `FOR UPDATE SKIP LOCKED` in `OutboxEventRepository.claimPending` | |
| No duplicate ticket email | `UNIQUE(order_number, kind)` + insert-then-send | |
| Admission is bounded | `queue:budget`, one cluster-wide allowance per tick | the pool, not inventory, is the real ceiling |
| Event/tier metadata | `CatalogMetadata`, TTL-bounded, rows not entities | the TTL **is** the cross-replica invalidation |
| Session identity | the signed `fsid` cookie, `shared/identity` | never a body field, query param or header |
| A charge that settled after the buyer left | `PaymentWebhookService` → `PaymentSettledEvent` → `PaymentSettlementService` | a delivery is a claim, released when its work did not happen (ADR-053) |

**Two rules over the whole Redis surface:** a module touches only its own prefix, and **no key is the
authority for anything**. Lose all of Redis and `ticket_holds` plus the rebuild reconstruct the state.
That is what makes `noeviction` a correctness setting rather than a tuning one.

## 1.5 Things that look like clutter and are not

**Read this before proposing a deletion.** Each of these has been examined and each stays.

| Looks removable | Why it stays |
| :--- | :--- |
| **SSE + Redis pub/sub** (5 classes, ~470 lines) next to `/queue/status` polling | Not duplicates. Polling is the *measured* CPU hog — ~90,000 calls per replica per run against ~1,100 checkouts. SSE is the **cheap** path per waiting buyer. Removing it makes the worst-measured load worse. |
| **`saleflow`**, a 205-line module with one endpoint | The only host for a four-facade read that keeps the graph acyclic. |
| **Checkout step 0**, apparently redundant with step 4 | It must precede step 1 — see §1.3. |
| **Three split class pairs**: `CheckoutService`/`OrderCommitService`, `OutboxRelay`/`OutboxStore`, `TicketDownloadService`/`OrderQueryService` | Spring's proxy does not intercept self-invocation: a `@Transactional` method called from its own class runs with **no transaction at all**, silently. The split *is* the boundary. |
| **`LoggingOutboxPublisher`** and the `OutboxPublisher` interface | The whole test suite runs on `transport=log`. It is what tests the three-transaction relay without a broker. |
| **`PaymentGateway` interface** over one stub | One `@Bean` swap was the Stripe seam — and it was taken: `StripePaymentGateway` and a circuit-breaking decorator now sit behind it (ADR-052). |
| **The drift gauge computed on all three replicas** | Under a lock only the winner updates its gauge and the other two report `0.0` for ever — on the system's correctness canary. |
| **`EventRow`/`TierRow`** next to the `Event`/`TicketTier` entities | The row records are the cache contract. Collapsing them re-introduces detached-entity caching. |
| **`OutboxPayload` ≡ `OrderConfirmedPayload`**, field for field | A wire contract between two modules. One shared class makes `notification` depend on `order`'s internals. |

## 1.6 Reading order for `docs/`

| Read | When |
| :--- | :--- |
| [`README.md`](../README.md), then **this file** | first, always |
| [`03-end-to-end-flow.md`](03-end-to-end-flow.md) | the authoritative journey and the 3–10 concurrent-sale operating envelope |
| [`00-architecture-decisions.md`](00-architecture-decisions.md) | before changing a decision. 76 ADRs; most record a defect and its fix |
| [`05-global-standards.md`](05-global-standards.md) | the cross-cutting contract — error registry, transaction rules, facade rules |
| [`FE_SPEC.md`](../FE_SPEC.md) | the client contract |
| [`modules/*.md`](modules/) | one page per module: owns / exposes / never |
| [`06-mvp-overview.md`](06-mvp-overview.md) | what is actually built, and the open limitations |

**Precedence when two documents disagree:** ADRs win, then `05`, then `FE_SPEC`, then `03`, then
`01`/`02`, then module pages. A module spec contradicting an ADR is stale — fix the spec, not the code.
