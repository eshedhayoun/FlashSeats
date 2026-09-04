# MVP Overview

> **The reference for what exists, what does not, and why.** Read this before changing code; read
> [`00-architecture-decisions.md`](00-architecture-decisions.md) before changing a decision.
>
> A **Review passes** log at the bottom records every pass over this MVP. Append to it; do not
> rewrite history.

**Status:** built and running. 25 tests green, including the concurrency and journey suites.

---

## 1. The goal

> One person can walk from the event page to a PDF ticket in their inbox — and when two people race
> for the last ticket, exactly one wins.

Both halves are the deliverable. A version that completes the journey but oversells is worthless; a
version that never oversells but cannot be walked proves nothing to anyone.

---

## 2. Run it

```bash
cp .env.example .env
docker compose up -d          # postgres, redis, rabbitmq, mailpit
./mvnw spring-boot:run        # the dev profile seeds a sale that is already open
open http://localhost:8080
```

| Where | What |
| :--- | :--- |
| `http://localhost:8080` | The demo client — the whole journey in a browser |
| `http://localhost:8025` | Mailpit — the ticket emails land here |
| `http://localhost:8080/docs` | OpenAPI |
| `http://localhost:15672` | RabbitMQ (`flashseats` / `flashseats`) |

The seeder creates two events: **Aurora Fest 2026**, open immediately with 700 seats across three
tiers, and **Midnight Sessions**, still `UPCOMING` and un-warmed so the countdown and the admin
pre-warm path stay demonstrable. It runs only on an empty database, so a restart never disturbs a
sale in progress.

**Driving the payment branches.** The stub gateway reads the payment method id, mirroring a real
provider's test cards:

| `paymentMethodId` | Outcome |
| :--- | :--- |
| `pm_card_visa` | succeeds |
| `pm_card_declined` | declines — **the buyer keeps their seats** and may retry |
| `pm_card_error` | provider unreachable — `503`, seats retained |

---

## 3. The journey

Nine steps. Everything below is real HTTP; the demo client at `/` is one consumer of it.

| # | Call | What happens |
| :-- | :--- | :--- |
| 1 | `GET /api/v1/events/{id}` | Metadata, `windowStatus`, `serverTime`, **bucketed** availability. This request also mints the visitor's signed `fsid` cookie. |
| 2 | `POST /api/v1/queue/join` | Window-gated. `ZADD NX` — a refresh keeps your place rather than sending you to the back. |
| 3 | `GET /api/v1/queue/stream` | SSE: `position-update` (2 s, clamped monotonic), `queue-promoted`, `sale-exhausted`, `sale-closed`, plus heartbeats. `GET /queue/status` is the equivalent polling path. |
| 4 | *(worker)* | `PromotionWorker` ticks once a second, admits `min(45, floor(remaining × 1.5) − pendingPasses − liveAdmissions)`, and publishes each pass to `queue:events:{id}` so it reaches whichever replica holds that browser's stream. |
| 5 | `POST /api/v1/queue/admit` | Exchanges the 120 s pass for a 600 s admission session, **and revokes the pass here** — that is what makes it single-use. |
| 6 | `POST /api/v1/holds` | Requires `X-Admission-Token`. Validates the tier and window, caps quantity, then decrements stock and inserts the hold **in one transaction**. |
| 7 | `POST /api/v1/orders/checkout` | The nine-step orchestration below. |
| 8 | `GET /api/v1/orders/{n}` | Requires a matching `fsid` **or** `?receiptToken=` — the order number alone authorises nothing. |
| 9 | *(async)* | Outbox → RabbitMQ → PDFBox → SMTP. The email is in Mailpit within seconds. |

`GET /api/v1/sale/{eventId}/state` returns the caller's exact position in all of the above, and is
what makes a page reload cost nothing.

### Checkout, in order

The sequence is the design (ADR-001, ADR-023, ADR-030):

```
0  already CONFIRMED for this hold?   → return the receipt, 200
1  getActiveHold(token, sid)          → 404/410 if missing, expired or not yours
2  getTierSummary()                   → price computed SERVER-SIDE; no client value reaches it
3  window gate                        → OPEN, or CLOSED within 15 min
4  find-or-create on UNIQUE(hold_token)
5  grantGrace()                       → once per hold. FAILS ⇒ abort 410, DO NOT CHARGE
6  authorize()                        → OUTSIDE every transaction
7  @Transactional                     → consumeHold · CONFIRMED · order_items · outbox_events
8  AFTER_COMMIT                       → discardTimer, revokeAdmission — best-effort, safe to lose
9  commit failed after a charge?      → refund · REFUNDED · ORDER_REFUNDED outbox row
```

**Step 0 has to be first.** A successful purchase consumes its hold, so validating the hold first
would answer a resubmission with "your reservation expired" when the buyer already owns the seats.

**Step 7 is one transaction containing only SQL.** `consumeHold` is a conditional `UPDATE` that joins
it, so if anything fails the hold returns to `ACTIVE` and expires normally.

---

## 4. Module map

| Module | Ships now | Deferred |
| :--- | :--- | :--- |
| `shared` | `ErrorCode` (37 codes), `ProblemDetails`, one global advice, `SessionId`, `Money`, `Clock`, `SignedToken`, `TraceIdFilter` | — |
| `bot` | Signed `fsid` cookie; Redis-backed Bucket4j session + IP buckets; SSE exempt from per-request accounting | reCAPTCHA, `ip_rules`, audit logs. **Writes no tables.** |
| `catalog` | Events, tiers, `tier_inventory`, window derivation, `serverTime`, bucketed availability, pre-warm, `tryReserve`/`restore` | Redis counters + Lua, the `-2` fault path, locked rebuild, pause, `TierAvailabilityChangedEvent` |
| `queue` | `ZADD NX` join, SSE with heartbeats, HMAC passes, admission sessions, promotion worker, **pub/sub fan-out**, measured drain-rate estimates | `RANDOM` ordering, `tier-availability` frame, `Last-Event-ID` replay |
| `hold` | `ticket_holds` authority, the settle-once claim, atomic reserve, bounded grace, sweeper, all three endpoints | Lua scripts, the `hold:{token}` Redis timer, the keyspace listener |
| `payment` | Real `PaymentFacade`, `payment_transactions`, three idempotency layers, stub gateway behind the final interface | Stripe, webhooks, 3-D Secure, Resilience4j |
| `order` | Full orchestration, find-or-create, server-side pricing, receipt tokens, outbox relay with `SKIP LOCKED`, compensating refund | `PaymentSettledEvent` listener, `/checkout/resume` |
| `notification` | Rabbit topology + DLX, insert-then-send consumer, PDFBox tickets, HTML email, Mailpit | Failure classification, DLQ inspection, admin resend, refund-notice template |
| `saleflow` | `GET /sale/{id}/state`, failing soft per section | — |

### What gets replaced later — and it is almost nothing

Four method bodies and one bean. Every interface is already final:

| Today | Later | Blast radius |
| :--- | :--- | :--- |
| `CatalogFacade.tryReserve` — one SQL statement | `hold_reserve.lua` | the method body |
| `HoldFacade.discardTimer` — a no-op | `DEL hold:{token}` | the method body |
| `StubPaymentGateway` | `StripeGateway` | one `@Bean` |
| `LoggingOutboxPublisher` | already switchable by property | none |

That is the point of the sequencing: the transaction boundaries, the claim, and every constraint are
the final versions. Speed arrives later; correctness did not wait for it.

---

## 5. Where each invariant actually lives

Guarantees are enforced by constraints and single statements, not by careful call-site discipline.

| Invariant | Enforced by |
| :--- | :--- |
| Never oversold | `TierInventoryRepository.tryReserve` — one `UPDATE … WHERE remaining >= :q`, backed by `CHECK (remaining >= 0)` |
| Seats restored **exactly once** | `TicketHoldRepository.settle` — `UPDATE … WHERE status = 'ACTIVE'`; only `rowcount = 1` restores |
| One hold never becomes two orders | `UNIQUE(hold_token)` on `orders` |
| One live hold per session per event | Partial unique index `idx_holds_one_active_per_session` |
| Grace granted once per hold | `AND extended_count = 0` inside the update itself |
| Confirmed order ⇒ fulfilment queued | The outbox row is written in the same transaction |
| No event published twice | `FOR UPDATE SKIP LOCKED` in `OutboxEventRepository.claimPending` |
| No duplicate ticket email | `UNIQUE(order_number, kind)` + insert-then-send |
| Module boundaries | `ModularityTests` — fails the build |

---

## 6. Conventions

Every module has the same shape, so reading one is reading all nine:

```
com.flashseats.<module>/
├── config/       exactly one <Module>Properties — no @Value scattered around
├── controller/   thin: parse, delegate, map. Never @Transactional
├── dto/          request/response records
├── event/        Spring application events (records)
├── exception/    extends shared FlashSeatsException, carries an ErrorCode   [@NamedInterface]
├── facade/       interface + package-private Impl + record DTOs             [@NamedInterface]
├── model/        JPA entities + enums                                        internal
├── repository/   Spring Data; conditional UPDATEs return int (rowcount)      internal
└── service/      all business logic; owns every @Transactional               internal
```

Rules that hold everywhere:

- **Services own transactions; facades never open one.** Two facade methods declare
  `Propagation.MANDATORY` — they *require* the caller's transaction rather than assuming it.
- **Every claim is a conditional `UPDATE` returning `int`.** Never `SELECT` then update.
- **Records across boundaries, never entities.**
- **A slow call never sits inside a transaction.** Gateway calls, PDF rendering and SMTP are all
  bracketed by two short transactions on a separate bean — separate because Spring's proxy does not
  intercept self-invocation, so a `@Transactional` method called from its own class runs with no
  transaction at all, silently.

---

## 7. What implementation actually taught us

Findings that cost real time and would cost it again.

| Finding | Detail |
| :--- | :--- |
| **`@Modifying(clearAutomatically = true)` detaches unrelated entities** | The hold's settle claim runs inside the order transaction. Clearing the persistence context detached the `Order` loaded moments earlier, so its status change was **silently discarded** — the hold was consumed and the outbox written, but the order stayed `PENDING`. Removed everywhere; the settle query documents why it must stay off. |
| **Replay ordering** | Checking the hold before checking for an existing confirmed order made the idempotent-replay branch unreachable: a resubmission got `410 HOLD_EXPIRED` instead of `200` + receipt. The confirmed-order check is now step 0. |
| **Boot 4 ships Jackson 3** | `tools.jackson.databind.ObjectMapper` is the autoconfigured bean. Jackson 2 is on the classpath transitively but has no bean — the symptom is a missing-bean error naming a class that is obviously present. Jackson 3 also throws unchecked, so serialisation needs no try/catch. |
| **Boot 4 split Flyway out** | `flyway-core` alone puts migrations on the classpath and runs none of them. Needs `spring-boot-starter-flyway`; the first symptom is Hibernate reporting a missing table, which reads like a mapping bug. |
| **Boot 4 moved `@EntityScan`** | Now `org.springframework.boot.persistence.autoconfigure.EntityScan`. |
| **The bootstrap package costs two annotations** | `FlashseatsApplication` lives in `com.flashseats.flashseats` while modules are `com.flashseats.*`, so `scanBasePackages`, `@EntityScan` and `@EnableJpaRepositories` are all widened explicitly — and `@SpringBootTest` must name `classes = FlashseatsApplication.class`, or tests outside that package cannot find it. |
| **Modulith treats nested packages as internal** | `facade` and `exception` need `@NamedInterface` or no other module may reference them. |
| **Docker Engine 29 vs Testcontainers** | The daemon answers `/info` with HTTP 400 and an empty body, so Testcontainers reports "Could not find a valid Docker environment" on a machine where `docker ps` works. Pinned via `api.version=1.44` in the Surefire config. |
| **`*IT` is Failsafe's convention** | Only Surefire is configured, so the integration tests were silently not running. Includes added — a green build that skips the tests carrying the guarantees is worse than a red one. |

---

## 8. Verification

```bash
./mvnw test        # 25 tests: unit, modularity, concurrency, journey
```

| Test | What it proves |
| :--- | :--- |
| `StockReserveConcurrencyIT` | 50 threads for the last seat → exactly one wins. 100 threads for 30 seats → exactly 30. Four-seat requests against ten remaining → two win, and no partial reservation. |
| `HoldLifecycleIT` | Double consume → `409`. Ten concurrent releases restore **once**. The sweeper reclaims an abandoned hold and does not keep restoring it. One hold per session. A missing counter is `503`, never "sold out". |
| `UserJourneyIT` | The full journey over real HTTP with a real cookie; a spent pass is rejected; a decline retains the hold and the retry succeeds on the same order number; a double submit yields one order and one charge; `/sale/state` tracks the stage. |
| `ModularityTests` | The boundary graph is acyclic and unbroken. |
| `SignedTokenTest`, `AvailabilityBucketsTest` | The signing primitive and the availability rule. |

**Verified by hand against the live stack:** the nine-step journey end to end, a declined card
leaving the hold `ACTIVE`, a replay returning `200` with the same order number, a 1,058-byte PDF
attached to the email in Mailpit, and the stock invariant holding across all three tiers.

---

## 9. Known limitations

Honest list. None of these is hidden behind a passing test.

- **Single replica verified.** Promotion fan-out is implemented over Redis Pub/Sub and is correct by
  construction, but it has not been exercised with `--profile cluster`. That check cannot be made
  from one instance.
- **No load test run.** The k6 harness exists but still uses the pre-ADR-020 pass flow; it needs the
  `/queue/admit` exchange before it will run.
- **Inventory is PostgreSQL-only.** Correct, and roughly two orders of magnitude slower than the Lua
  path. Fine to thousands of requests per second, not to hundreds of thousands.
- **Payment is a stub.** Every idempotency layer is real; the gateway is not.
- **No admin surface** beyond pre-warm. No pause, no stock rebuild, no DLQ replay.
- **`ORDER_REFUNDED` is written but never consumed** — the refund-notice template is deferred.
- **`stock.drift` is asserted in tests, not exported as a metric.** With a PostgreSQL counter it
  cannot diverge from itself; it becomes a live metric when Redis holds the count.

---

## 10. Security posture

**This MVP is not production-ready, and the gaps are deliberate rather than overlooked.** Everything
below is a real exposure someone should close before real money moves through it.

### Must fix before any deployment

| # | Exposure | Detail and fix |
| :-- | :--- | :--- |
| S1 | **Default secrets** | `flashseats.bot.session-secret`, `queue.pass-secret` and `order.receipt-secret` all fall back to `dev-only-change-me`. Anyone who knows that string can forge an `fsid`, a queue pass, an admission session **and** a receipt token — that is total impersonation of any buyer. Generate per-environment secrets and fail startup if the default is still in place. |
| S2 | **Default admin credentials** | `admin` / `admin`, in `application.properties`, guarding pre-warm. Move to a secret, and prefer a real identity provider over an in-memory user. |
| S3 | **`Secure` cookie flag defaults to false** | Correct for `http://localhost` and wrong everywhere else: over plain HTTP the session cookie travels in clear and is trivially stolen. Set `flashseats.bot.cookie.secure=true` wherever TLS terminates, and serve only over TLS. |
| S4 | **Receipt tokens never expire** | A signed capability with unlimited lifetime, sent by email and appearing in a URL — so it leaks through forwarded mail, browser history, and `Referer`. Add an expiry claim and re-issue on demand. |

### Structural weaknesses to weigh

| # | Weakness | Assessment |
| :-- | :--- | :--- |
| S5 | **Session identity is free to mint** | The rate limiter's primary bucket is per-`fsid`, and anyone can discard a cookie to get a fresh one. The IP bucket is therefore the only real backstop — and it is deliberately loose (300 burst) so NAT populations are not blocked. This is the ADR-011 trade working as designed, but it means **the session bucket does not constrain a determined attacker at all.** reCAPTCHA on join is the missing compensating control, and it is deferred. |
| S6 | **CSRF is disabled while a cookie authorises actions** | Justified for a stateless JSON API, and the checkout path is safe because it needs a `holdToken` an attacker cannot guess. But a cross-site `POST /queue/join` or `POST /holds` *would* succeed against a logged-in visitor and could be used to consume their one-hold-per-event allowance. Low impact, non-zero. Require a custom header, or re-enable CSRF for the mutating endpoints. |
| S7 | **Order numbers are sequential** | `TK-00001`, `TK-00002`. Access is properly controlled, so this is not an IDOR — but it publishes exact sales volume to anyone who buys one ticket. Prefer a non-sequential public reference. |
| S8 | **SSE connections are uncapped per session** | The stream is exempt from per-request rate accounting (correctly — it is one connection, not a request stream), and nothing limits how many a single session opens. A few thousand connections would exhaust the container. Cap concurrent streams per session and per IP. |
| S9 | **PII is stored and logged in clear** | `orders.user_email` and `notification_logs.recipient_email` are plaintext, with no retention policy and no deletion path. Whatever regime applies, decide it explicitly. |
| S10 | **The stub gateway accepts anything** | Obvious, but worth stating: it must never reach an environment where a `201` implies money moved. |

### Already handled — do not regress these

- Identity comes only from the signed `fsid` cookie; no endpoint accepts a session id in a body,
  header or query parameter (ADR-010).
- HMAC verification is constant-time (`MessageDigest.isEqual`); a tampered cookie yields a fresh
  identity rather than an error, so a corrupted cookie cannot strand a visitor.
- Order lookup returns `404`, never `403`, for a caller who may not see it — so it cannot be used to
  enumerate valid order numbers.
- Charge amounts are computed server-side from the tier; no client value reaches them (ADR-013).
- `/actuator/health` is public for the container healthcheck; `metrics` and `prometheus` require
  `ROLE_ADMIN`. They describe inventory levels, queue depth and order rates — a live read on how the
  sale is going, and a useful one to anyone attacking it.
- The demo client HTML-escapes every interpolated value, and the email composer escapes
  operator-supplied text before it reaches a mail client's renderer.

---

## 11. What comes next

In dependency order. Each stage leaves a system that is still correct, and none of them requires
reopening a decision made above.

### Stage 1 — Redis fast path (the documented Phase 2)

The only stage that touches inventory correctness, so it goes first and alone.

- `hold_reserve.lua` / `hold_restore.lua`, replacing the body of `CatalogFacade.tryReserve`.
- The `-2` fault path: a missing counter is `503 INVENTORY_UNAVAILABLE`, **never** "sold out". The
  distinction already exists in `HoldService`; it moves into the script.
- `SETNX` pre-warm restricted to `UPCOMING`, and the locked rebuild from the ledger under
  `pg_try_advisory_xact_lock`.
- `hold:{token}` as a TTL timer plus the keyspace listener — a *latency optimisation*. The sweeper
  stays the correctness guarantee, and disabling the listener must change no outcome.
- `flashseats.stock.drift` becomes a live metric, because now the counter can diverge.

**Exit:** 1,000 concurrent requests for 100 tickets sell exactly 100; `FLUSHDB` mid-sale returns
`503` and a rebuild restores the exact count; with the listener disabled the sweeper still restores
every hold exactly once.

### Stage 2 — Real money and real defence (Phase 3)

- `StripeGateway` implementing the existing `PaymentGateway`; the webhook receiver with signature
  verification and `webhook_events` replay protection; `PaymentSettledEvent` → `order`.
- The auto-refund path when a webhook arrives against a hold that is gone (ADR-012) — the code exists
  and is currently only reachable via a commit failure.
- 3-D Secure: `PAYMENT_ACTION_REQUIRED` plus `POST /orders/checkout/resume`.
- Resilience4j around every gateway call — declared as plain beans, since the Boot-3 starter does not
  apply here.
- reCAPTCHA v3 on join, cached per session, **failing open** (ADR-011) — this is S5's compensating
  control, so it belongs with the security fixes above.
- `ip_rules`, `bot_audit_logs` (async, non-`ALLOWED` outcomes only), and `V6__bot.sql`.
- **All four "must fix" items in §10.**

### Stage 3 — Scale and proof (Phase 4)

- Run `docker compose --profile cluster` and verify promotion fan-out across replicas. **This is the
  highest-value unverified claim in the system** and the first thing to check.
- Fix `docker/k6/flash-sale.js` for the ADR-020 admission flow, then the 10,000-VU run: 500 tickets,
  exactly 500 sold, zero overbooking, checkout p99 under 200 ms.
- Nginx in front, Redis Sentinel behind.
- The full metric set and its alarms — `stock.drift`, `hikaricp_connections_pending`,
  `outbox.lag.seconds`, `dlq.depth`, `queue.promotion.rate`, `payment.decline.ratio`,
  `jvm.threads.pinned`.

### Stage 4 — Operability and polish

- Admin surface: pause a sale, rebuild stock, inspect and replay the DLQ.
- The refund-notice template, so `ORDER_REFUNDED` reaches the buyer.
- Notification failure classification (ADR-029): deterministic render failures skip the retry chain.
- `tier-availability` frames in the waiting room (ADR-027); `RANDOM` queue ordering (ADR-024).
- The React SPA against `FE_SPEC.md`, if the demo client is outgrown.

---

## 12. What to examine in the next review pass

Ordered by expected value. The first three are where this build is most likely to be wrong.

1. **Multi-replica behaviour.** Run two instances and check: does every promoted buyer receive their
   pass? Do two sweepers restore a hold once? Do two relays publish an event once? All three are
   correct by construction and none has been observed.
2. **The promotion lock under contention.** ADR-032 accepts that a tick overrunning its 900 ms TTL
   lets two replicas promote in the same second. Measure how long a tick actually takes with a deep
   queue, and confirm the oversubscribe factor absorbs the overlap.
3. **Failure injection.** Kill Redis mid-sale — does the queue fail closed and does checkout keep
   working? Kill PostgreSQL — is the error a clean `503`? Kill the broker — do orders still commit
   and does the outbox drain on recovery?
4. **Transaction-boundary audit.** Grep every `@Transactional` and confirm nothing inside it makes a
   network call, renders, or sleeps. This is the rule most likely to erode as features are added, and
   the damage is invisible until load arrives.
5. **The `AFTER_COMMIT` block.** Everything there must be safe to lose. Confirm that skipping it
   entirely leaves the system correct.
6. **Clock discipline.** Every timer flows from the injected `Clock`. Check that no new code reaches
   for `Instant.now()`, and that every countdown the client renders derives from `serverTime`.
7. **Error-code coverage.** Every failure path should return a registry code, and every registry code
   should be reachable. Both directions are worth checking — an unreachable code is dead contract,
   and a failure without one is a client that cannot branch.
8. **Backpressure.** Where does the system queue when it is overloaded — Hikari, the SSE registry, the
   broker? Under virtual threads nothing errors, so this has to be measured rather than observed.
9. **The demo client against `FE_SPEC.md`.** It implements the four rules and the recovery matrix
   informally. Walk the twelve reload points by hand.

---

## 13. Review passes

Append one section per pass. Record what was examined, what was found, and what changed.

### Pass 0 — initial implementation

- **Scope:** all nine modules to MVP depth, a single-file demo client, and the test suite.
- **Design gaps closed:** ADR-031 (`queue → catalog` edge missing from every diagram), ADR-032
  (an advisory lock cannot guard a Redis-only worker), ADR-033 (one advice via a shared base type).
- **Registry additions:** `INSUFFICIENT_TIME_REMAINING`, `ORDER_REFUNDED`.
- **Defects found and fixed during the build:** the `clearAutomatically` context-clear that silently
  dropped the order status change; the replay-ordering bug that made idempotent checkout
  unreachable; the `Secure` cookie flag that would have broken every local session; Spring Security
  locking the whole sale behind a generated password; missing `claimed_at` on `outbox_events`;
  `/actuator/metrics` and `/actuator/prometheus` exposed without authentication.
- **Result:** 25 tests green; the journey verified by hand end to end.
