# MVP Overview

> **The reference for what exists, what does not, and why.** Read this before changing code; read
> [`00-architecture-decisions.md`](00-architecture-decisions.md) before changing a decision.
>
> A **Review passes** log at the bottom records every pass over this MVP. Append to it; do not
> rewrite history.

**Status:** built and running, two review passes, one cleanup pass and **Stage 1** deep. 112 tests
green, including the concurrency, journey, checkout-recovery, queue-lifecycle, availability,
problem-response, pre-warm, rebuild and Redis-restart suites.

**Inventory lives in Redis** (ADR-046). `catalog:stock:{eventId}:{tierId}` is the live count;
PostgreSQL keeps no copy of it and `tier_inventory` is dropped.

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

`dev` is set by the `spring-boot-maven-plugin`, **not** by `application.properties`. A default
profile in the properties file made `SecretsGuard` opt-in: a packaged jar started with no
`SPRING_PROFILES_ACTIVE` ran on development secrets and seeded a demo catalog, silently (ADR-039).
Running the jar directly therefore requires a profile, which is the intended fail-closed behaviour.

| Where | What |
| :--- | :--- |
| `http://localhost:8080` | The demo client — the whole journey in a browser |
| `http://localhost:8025` | Mailpit — the ticket emails land here |
| [`FE_SPEC.md`](../FE_SPEC.md) §2 | The API contract. There is no generated `/docs` page — springdoc described the shapes and none of the meaning |
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
| `shared` | `ErrorCode` (42 codes), `ProblemDetails`, one global advice, `SessionId`, `Money`, `Clock`, `SignedToken`, `TraceIdFilter` | — |
| `bot` | Redis-backed Bucket4j session + IP buckets (SSE **counted once**, not exempt); reCAPTCHA v3 on join, failing open behind its own circuit breaker; cached `ip_rules`; async `bot_audit_logs`; operator surface; **`flashseats.bot.refusals{outcome}`** | CIDR ranges; audit retention. The `fsid` cookie moved to `shared` in Pass 7 |
| `catalog` | Events, tiers, window derivation, metadata cache, `serverTime`, bucketed availability, **Redis counters + Lua, the `-2` fault path, pre-warm, pause/resume, the Redis-restart guard** | create-event endpoint, `TierAvailabilityChangedEvent` |
| `queue` | `ZADD NX` join, `FIFO`/`RANDOM` ordering, SSE with heartbeats, HMAC passes, admission sessions, promotion worker, **pub/sub fan-out**, measured drain-rate estimates, `tier-availability` frame, **`Last-Event-ID` replay of broadcast frames** | per-event queue metrics |
| `hold` | `ticket_holds` authority, the settle-once claim, atomic reserve **with compensation**, **after-commit restore**, bounded grace, sweeper, all three endpoints, `hold:{token}` Redis timers and the keyspace listener | — |
| `payment` | Real `PaymentFacade`, `payment_transactions`, three idempotency layers, **Stripe behind the same seam, the webhook receiver, 3-D Secure, a hand-declared circuit breaker**, stub gateway as the default | — |
| `order` | Full orchestration, find-or-create, server-side pricing, receipt tokens, outbox relay with `SKIP LOCKED`, compensating refund, **the stock rebuild, the drift gauge and the `PaymentSettledEvent` listener** | — (there is deliberately no `/checkout/resume`; re-POSTing is the retry, ADR-054) |
| `notification` | Rabbit topology + DLX, insert-then-send consumers, PDFBox tickets, HTML email, refund notices, Mailpit | Failure classification |
| `saleflow` | `GET /sale/{id}/state`, failing soft per section | — |

### What gets replaced later

| Today | Later | Blast radius |
| :--- | :--- | :--- |
| `HoldFacade.discardTimer` — a no-op | `DEL hold:{token}` | the method body |
| `StubPaymentGateway` | `StripeGateway` | one `@Bean` |
| `LoggingOutboxPublisher` | already switchable by property | none |

`CatalogFacade.tryReserve` has already made its move, and it cost more than the method body the
first draft of this table predicted — the signature, the transaction boundary either side of it, and
a table. What the prediction got right is what mattered: the claim, the constraints and the checkout
sequence are unchanged, so speed arrived without correctness being reopened (ADR-046).

---

## 5. Where each invariant actually lives

Guarantees are enforced by constraints and single statements, not by careful call-site discipline.

| Invariant | Enforced by |
| :--- | :--- |
| Never oversold | `stock_reserve.lua` — `GET`, compare, `DECRBY` in one atomic step, refusing to go below the requested quantity |
| The counter is watched, not merely trusted | `flashseats.stock.drift` against `capacity − confirmed − held`; `rebuild-stock` repairs it |
| A rolled-back Redis cannot quietly oversell | `catalog:vouch:{eventId}` vs the live `run_id` — holds are refused until a rebuild (ADR-046) |
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
├── facade/       interface + record DTOs — the module's published contract   [@NamedInterface]
├── model/        JPA entities + enums                                        internal
├── repository/   Spring Data; conditional UPDATEs return int (rowcount)      internal
└── service/      all business logic; implements the facade                   internal
```

Rules that hold everywhere:

- **The service implements the facade; there is no separate `*Impl`.** Five existed, every one of
  them pure delegation — `CatalogFacadeImpl` was twelve one-line methods — and all they added was a
  hop between the contract and the code honouring it. The interface still lives in the
  `@NamedInterface` package, the service still lives in an internal package no other module may
  name, and `ApplicationModules.verify()` still proves it: Modulith checks *source-code type
  references*, not runtime bean types.
- **Services own transactions; facades never open one.** One facade method declares
  `Propagation.MANDATORY` — it *requires* the caller's transaction rather than assuming it.
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
./mvnw test        # 228 tests: unit, modularity, concurrency, journey, recovery, queue lifecycle,
                   #             pre-warm, stock rebuild, drift, Redis-restart guard, the metadata
                   #             cache's five rules, the cluster admission allowance, payment and
                   #             webhooks, bot defence, and fulfilment through a real broker.
                   #             Green in alphabetical, reverse and filesystem order (ADR-061)
```

| Test | What it proves |
| :--- | :--- |
| `StockReserveConcurrencyIT` | 50 threads for the last seat → exactly one wins. **1,000 concurrent buyers against 100 seats → exactly 100.** Four-seat requests against ten remaining → two win, and no partial reservation. A tier whose counter is gone reports a fault, not a sell-out. |
| `CatalogPrewarmIT` | Pre-warm creates the counter, is a no-op the second time, and leaves Redis untouched when it refuses an open sale. |
| `StockRebuildIT` | A counter that never existed, one left too high by a restart, and one left too low by a lost restore are all rebuilt to the ledger's number. Drift reads zero when they agree and reports the gap when they do not; a missing counter is counted separately. |
| `StockEpochIT` | A Redis restart refuses to sell rather than overselling; a rebuild releases that sale and only that sale; noticing the restart does not consume it; a sale created afterwards is never in doubt. |
| `HoldLifecycleIT` | Double consume → `409`. Ten concurrent releases restore **once**. The sweeper reclaims an abandoned hold and does not keep restoring it. One hold per session. A missing counter is `503`, never "sold out". |
| `UserJourneyIT` | The full journey over real HTTP with a real cookie; a spent pass is rejected; a decline retains the hold and the retry succeeds on the same order number; a double submit yields one order and one charge; `/sale/state` tracks the stage. |
| `CheckoutRecoveryIT` | A gateway outage keeps the seats **and** the ability to pay for them; it costs none of the three card attempts; a charge genuinely in flight is still refused; an order stranded by a crash resumes once no charge can still be running. |
| `QueueLifecycleIT` | An un-warmed event pauses promotion rather than selling out; a closed sale ends the wait instead of freezing it; a pass for one sale is never offered to another; exhaustion reverses when seats return. |
| `NotificationClaimIT` | The claim blocks a duplicate, is terminal once sent, and releases a dead letter for replay. |
| `NotificationListenerIT` | **Fulfilment through a real RabbitMQ and both real listeners**, the one thing the test profile's `notification.enabled=false` had left unexercised. One ticket per order even when the message is redelivered; a malformed message or a failed send goes to the DLQ after **one** attempt (ADR-029); a replay after the outage sends exactly once (ADR-038); and mail that was sent but not recorded stays `SENT`, so a replay cannot send a second ticket (ADR-042). Checked by mutation: removing that guard fails the test. |
| `BackPressureResponseTest` | A HikariCP timeout is `503 SERVICE_BUSY` with `Retry-After`; any other transaction failure is still `500` (ADR-059). |
| `SessionResetIT` | `POST /session/reset` refuses form and `text/plain` bodies with `415` and expires nothing; a JSON POST still works (ADR-060). |
| `CatalogAvailabilityIT` | A tier with no counter reads `UNKNOWN`, a drained tier still reads `SOLD_OUT`, and the two are never the same answer (ADR-040). |
| `ProblemResponseIT` | Spring's own binding failures are `400` with a registry `code`, not `500` (ADR-041). |
| `RemainingForEventTest` | "Nothing known" is never "nothing left", at the method every admission decision reads: no tiers, a missing counter, genuinely drained and live are four distinct answers (ADR-004, ADR-035, ADR-040). |
| `CatalogMetadataCacheTest` | Every rule that makes a cache in front of `events` safe: an entry stops being served when its TTL passes, a miss is never remembered, a committed change evicts, and the recovery path reads PostgreSQL (ADR-051). |
| `GlobalPromotionBudgetIT` | One allowance is shared by every caller whichever sale it is promoting, a single claim cannot exceed a window, the window refills, and **no open sale is starved by another** (ADR-049). |
| `ModularityTests` | The boundary graph is acyclic and unbroken. |
| `SignedTokenTest`, `AvailabilityBucketsTest`, `TicketPdfRendererTest` | The signing primitive — including domain separation — the availability rule including its fault value, and a ticket that renders whatever alphabet the title is in. |

**Verified by hand against the live stack:** the nine-step journey end to end, a declined card
leaving the hold `ACTIVE`, a replay returning `200` with the same order number, a 1,058-byte PDF
attached to the email in Mailpit, and the stock invariant holding across all three tiers.

---

## 9. Known limitations

Honest list. None of these is hidden behind a passing test.

- ~~**Single replica verified.**~~ **Verified on three** (Stage 3). 30/30 sessions promoted, spread
  10/10/10 across the replicas, through nginx. `docker/scripts/fanout-check.sh` re-runs it in
  seconds. ADR-007's fan-out is no longer a construction argument.
- ~~**No load test run.**~~ **Run** (Stage 3), at 300 and 2,000 VUs: exactly 500 sold, zero
  overbooking, zero inventory 503s, zero rate-limited requests, `stock.drift` `0.0` on every
  replica. The harness needed four fixes first, all in ADR-047.
- **The 10,000-VU run has not happened, and the limit is the host's CPU — not its memory.** This was
  recorded as a memory ceiling ("three JVMs take ~6 GB of the 7.65 GB this machine gives Docker"); the
  Pass 8 runs measured it and that is **wrong**. Each replica uses **~350 MiB**, about 1 GB between
  them, while each sits at **114–142 % of one core** with a 2,000-VU k6 competing for the same ten.
  2,000 VUs is still the ceiling here, but a 32 GB machine would not move it — a machine where the
  load generator is not sharing cores with the system under test would.
- **Checkout p99 is 129 ms at 300 VUs across five sales (run H, Pass 12). That is the first run to
  meet the 200 ms exit criterion.** It is one run on one host, with nothing else running on it
  (§11), so it is a data point and does not close the criterion. The earlier five-sale figures —
  9.3 s, then 6.4 s after Pass 9 removed a checkout transaction, then 4.8 s in run G — were measured
  with other work on the same laptop. Most of the gap is the host, not the code. **At 2,000 VUs p99
  is still ~30–45 s**, and that is the host too: ten cores shared between three JVMs and the load
  generator, with `connections_pending` peaking at 10 of 90 in the run that sold 76 % of capacity.
  Where this turns over between 300 and 2,000 VUs is the Pass 13 sweep in §11.
- ~~**A pool timeout surfaces to a buyer as `500 INTERNAL_ERROR` mid-checkout.**~~ **Fixed (Pass 13,
  ADR-059):** it is now `503 SERVICE_BUSY` with `Retry-After: 1`, classified by HikariCP's own
  `SQLTransientConnectionException` in the cause chain so a database that is genuinely down still
  answers `500`. The original finding, kept for the record: not seen in the runs that
  sell out — `pending` stays at zero there — but with `connection-timeout=3000`, CPU starvation produced
  1,218 of them across three replicas in the 2,000-VU run; one order was left `FAILED`. The compensation held — that tier's `sold + held + redis` was still
  exactly 500, so no seats were stranded — and a buyer's documented recovery (re-POST the same body)
  works because checkout is find-or-create. But `INTERNAL_ERROR` is the least actionable code in the
  registry for what is really back-pressure, and `503` with a `Retry-After` would be the honest answer.
- **`stock.drift` will read non-zero transiently under live traffic.** Redis and PostgreSQL are not
  read in one snapshot, so a hold created between the two reads shows as a momentary gap. The two
  SQL sums *are* one snapshot, which removes the only drift the measurement can manufacture by
  itself. Alarm on sustained non-zero, not on a single sample.
- **A rebuild during live traffic can still under-count** by any hold created inside its settling
  window. That is the deliberate direction (ADR-046), and a second rebuild recovers it — but the
  sanctioned use is recovery from a counter that is missing or known wrong, not routine maintenance.
- **The Redis-restart guard halts selling for every affected event** until each is rebuilt. That is
  what `catalog.md` has always demanded after a restart; it is now enforced rather than remembered,
  and an unattended restart therefore stops a sale.
- ~~**Payment is a stub.**~~ **Fixed (Pass 9):** Stripe is behind the same seam, with a circuit
  breaker, the webhook receiver and 3-D Secure (ADR-052-054). The stub survives as the **default**,
  so `dev`, `test`, the load harness and every drill still drive the whole journey with no keys — and
  it is now the only deterministic coverage of decline, outage and challenge. What that means is that
  **the suite proves this system's behaviour, not the provider's**: `docker/scripts/stripe-check.sh`
  is the only thing that checks the real account, the real status mapping and the real webhook secret
  agree, and it is a script someone has to run rather than a test that fails on its own.
- ~~**No admin surface** beyond pre-warm.~~ **Built** (Stage 4, ADR-048): pause/resume, the DLQ
  listing, a ticket resend, and an operator order view. `rebuild-stock` shipped in Stage 1. Still
  a single in-memory operator account — now stored bcrypt-hashed rather than in plaintext, with a
  real identity provider deferred until a second operator exists (§10 S12).
- ~~**`ORDER_REFUNDED` is written but never consumed.**~~ **Fixed:** refund notices now have a
  dedicated consumer and template.

- ~~**The outbox relay publishes without confirms.**~~ **Fixed** (Stage 4, ADR-048). A row is marked
  `PROCESSED` only once the broker acknowledges it — and, just as importantly, only once it was
  *routed*: a confirm means "the broker has this", not "a queue has this", so `mandatory` plus
  publisher-returns is what stops every ticket being confirmed into an exchange bound to nothing.
  `OutboxPublisher` is batch-shaped, so an unhealthy broker costs one timeout per batch rather than
  one per message. Unconfirmed rows stay `PROCESSING` and the stale-claim sweep retries them.
- ~~**`QueueBroadcaster` does 4 sequential Redis round trips per connection per 2 s tick.**~~
  **Fixed — it is one.** `getQueueState` now issues the admission `GET`, its `TTL`, the pass `GET`, the
  waiting `ZRANK` and (when not hoisted) the exhausted `EXISTS` in a single pipelined round trip, and
  the state machine decides over the values rather than between the calls. The reads were always
  independent; only the decision was ordered.
  **What made it worth doing was the volume, not the sweep.** `GET /queue/status` shares this code and
  is called **~90,000 times per replica** in a 300-VU five-sale run, against ~1,100 checkouts — 80×
  the traffic of anything else, and the largest single consumer of the cluster's CPU. `CLOSED` is
  checked before the read, so a finished sale's polling clients cost no Redis at all.
  **No p99 claim is attached to it.** Two runs of the identical build measured checkout p99 at 1,880 ms
  and 1,242 ms, so this host's variance is ±50 % and swamps the change. Four round trips becoming one
  is a structural fact; the latency it buys is not measurable here.
- **The emitter registry is a flat map keyed by session id.** "Sessions watching event X" streams the
  whole map and allocates a `Set`, so a sweep costs `O(connections × events)` traversals before it
  makes a single Redis call. A per-event index removes it.
- ~~**`notification.order-refunded.queue` has no consumer.**~~ **Fixed:** `OrderRefundedConsumer`
  consumes the durable queue and sends `REFUND_NOTICE` mail.
- **Checkout does not survive a Redis outage.** It opens with `SETNX payment:inflight:{holdToken}`,
  and `POST /holds` verifies admission against Redis. Both fail closed, which for a payment is the
  right direction — but §12's "does checkout keep working?" now has a written answer: no.

**Found by running the Pass 7 drill, both FIXED:**

- **Docker Compose silently corrupted the admin bcrypt hash, so the operator surface could never
  authenticate.** A digest is `$2y$12$<salt><hash>` — three `$`, each of which Compose reads as the
  start of a variable name in a `.env` *value* and substitutes the empty string for. The container
  received **57 characters and two `$`** where the file held 68 and three. Nothing caught it: the
  value still begins `{bcrypt}`, so `SecretsGuard` — which refuses `{noop}` and dev defaults — waved
  it through, the app booted happily, and every admin call returned `401` looking exactly like a
  typo. ADR-043 calls the operator surface a correctness dependency; it had been unusable since the
  day `gen-env.sh` first hashed a password. `gen-env.sh` now escapes `$` as `$$`, which Compose
  un-escapes on the way into the container.
- **`seed-concurrent.sql` had an ambiguous `id`** in its confirmation query — `events` and
  `ticket_tiers` both have one — so the seeder failed at its last statement.

**Found in Pass 7 (the plan-correctness pass), with current status:**

- ~~**Admission was budgeted per sale against a shared pool.**~~ **Fixed** (ADR-049): `queue:budget`
  is one cluster-wide allowance, claimed atomically before anyone is promoted, with the per-event
  batch kept as the secondary cap. The open-event order is shuffled every tick — the first
  implementation iterated ascending on every replica, so the lowest event id took the whole allowance
  and the other four sales stood still.
- **A checkout costs *nine* sequential database transactions** — `PaymentTransactionStore`'s two
  `REQUIRES_NEW` transactions bracket the gateway call, and every listing collapsed them into "the
  payment store" — and a full buyer session about fifteen, not the ~1 that ADR-028's "capacity to
  serve" model implicitly prices. Both limits were therefore generous even at `E = 1`.
- ~~**Nothing is cached.**~~ **Fixed** (ADR-051): event rows for 1 s, tier lists for 60 s, behind
  `CatalogService`, with live stock still read from Redis on every availability path. The first
  implementation had **no TTL**, which made an operator's pause a no-op on every replica but the one
  that served it; loaded inside `computeIfAbsent`, which pins carrier threads; and was read by
  pre-warm and the rebuild, which must read the authority. All four rules are now tests.
- ~~**The write-only `queue:hb:{sid}` key.**~~ **Gone**, along with the `touchHeartbeat` call on the
  hottest polling path. The queue drains by promotion and never by evicting abandoned sessions.
- ~~**`sumActiveQuantityForTier` has no supporting index.**~~ **Fixed:** `V9` adds
  `idx_holds_active_tier` on active holds, `INCLUDE (quantity)` so the gauge's sum is an Index Only
  Scan. **But `V9`'s comment block was then rewritten in place**, and Flyway checksums the whole file:
  every replica refused to start with `Validate failed: checksum mismatch for version 9` against DDL
  that had not changed by one character. It cost the first two attempts at the Pass 8 drill and a
  hand-repair of `flyway_schema_history`. A migration is immutable once any database has run it;
  `CLAUDE.md` now carries the rule.
- **The drift gauge is computed three times to produce one global answer** — deliberately, and no
  longer listed as something to fix. Each replica reporting its own measurement is what makes the gauge
  truthful wherever it is scraped, and the duplication costs under 4 indexed queries a second
  cluster-wide. See §11 Stage 4c item 5 for why making it a singleton would make it worse.
- ~~**There is no way to retrieve a ticket.**~~ **Fixed:** `GET /orders/{orderNumber}/ticket.pdf`
  serves the same renderer used by notification, authorised by matching session or receipt token.
- ~~**Dead facade surface.**~~ **Fixed:** the unused order summary and hold release facade paths are
  gone; the remaining facades are the module contracts production uses.
- ~~**Session identity spans `bot` and `shared`.**~~ **Fixed:** identity lives under
  `shared/identity`, with `flashseats.session.*` configuration and module boundaries verified by
  `ApplicationModules.verify()`.
- **The operator surface is curl-only.** ADR-043 calls it a correctness dependency; one that can only
  be driven by hand-written Basic-auth curl during an incident is half-built.

**Found in Pass 11, reading the frontend merge (PR #16):**

- ~~**`./mvnw test` does not pass in the default order.**~~ **Fixed, root cause found (Pass 13,
  ADR-061).** The symptom was that, in some class orders, every `/queue` request in the shared test
  context answered a bare `500` (`timestamp/status/error/path`, no registry `code`). Pass 12 ruled
  out everything inside the application. The cause was outside it: **Spring Framework 7 pauses a
  cached test context whenever a test class switches to a different context**, and restarts it when
  a later class uses it again. The contexts that `BotDefenceIT` and `RecaptchaFailOpenIT` create
  caused that switch. Adding `NotificationListenerIT`, a third such context, turned "needs most of the
  suite" into a three-class reproduction: `HoldLifecycleIT` → `NotificationListenerIT` →
  `CheckoutRecoveryIT`. A temporary highest-precedence servlet filter then showed that the failing
  requests **never reached the resumed context's filter chain**, which matches Pass 12's finding
  that `GlobalExceptionHandler` never saw them. `spring.test.context.cache.pause=never` in
  `src/test/resources/spring.properties` fixes the reproduction. The full suite is green in
  `alphabetical`, `reversealphabetical` and `filesystem` order. The pom keeps `alphabetical` pinned,
  now only for a stable order. **Not established:** *why* a resumed context's embedded Tomcat
  answers without running its filters. The fix does not depend on the answer, and it is recorded
  as open in ADR-061.
- ~~**SSE reconnect replay never fired.**~~ **Fixed** (ADR-058). Live frames carried a
  per-connection `"local-N"` id, retained frames carried a Redis sequence, and the replay parsed the
  header as a number — so the normal case, where the last frame received was a two-second position
  update, replayed nothing. Only replayable frames carry an `id` now.
- ~~**The replay log retained promotion frames, and they carry a `passToken`.**~~ **Fixed**
  (ADR-058). A single-use 120 s capability was being written into a per-event ZSET that outlives the
  sale. Broadcasts only are retained; a promoted buyer's reconnect re-reads the live pass instead.
- ~~**The SPA could not start without a Stripe publishable key**~~ — while the backend defaults to
  the stub gateway, so the configuration everyone actually runs was the one the client could not
  drive. **Fixed** (ADR-058): no key means stub mode, with the stub's magic tokens offered in the UI.
- **The committed `node_modules` was not a working install.** Beyond the 57 MB, git had dropped the
  `.bin` exec bits and at least one package file (`vite/dist/node/module-runner.js`), so `npm test`
  failed on a fresh clone with a module-not-found error. `rm -rf node_modules && npm install` is the
  fix, and the directory is untracked now.
- **The SPA is dev-only.** `npm run dev` on `:5173` proxying to `:8080`. There is no compose service
  and nginx serves no static root, so the cluster still serves the demo client at
  `src/main/resources/static`. Wiring it is a separate stage (ADR-058).

---

## 10. Security posture

**This MVP is not production-ready, and the gaps are deliberate rather than overlooked.** Everything
below is a real exposure someone should close before real money moves through it.

### Closed in Pass 1 (and later)

| # | Was | Now |
| :-- | :--- | :--- |
| S1 | **Default secrets** — one leaked string forged an `fsid`, a queue pass, an admission **and** a receipt token, and `receipt-secret` defaulted to the *session* secret's env var so the two were the same value | Three separate secrets (`FLASHSEATS_SESSION_SECRET`, `FLASHSEATS_QUEUE_PASS_SECRET`, `FLASHSEATS_RECEIPT_SECRET`), every token domain-separated by a length-prefixed `kind`, and `SecretsGuard` **refuses to start** on any profile but `dev`/`test` while a default is in place (ADR-039) |
| S2 | **Default admin credentials** `admin`/`admin` | Same guard covers the admin password. Still an in-memory user — a real identity provider remains the right answer, and is still deferred |
| S3 | **`Secure` cookie defaults to false** | Now `${FLASHSEATS_COOKIE_SECURE:false}`, so it is set per environment rather than edited in a properties file. The default stays `false` because a `Secure` cookie is silently dropped over `http://localhost` and would break every local session |
| S4 | **Receipt tokens never expire** and were `sign(orderNumber)` — deterministic, so derivable by counting against sequential order numbers | Payload is `orderNumber:expiry:nonce`, mirroring `QueueTokens`. Default lifetime 90 days (`flashseats.order.receipt-token-ttl-days`) |
| S11 | **`X-Forwarded-For` trusted from any client** — anyone could rotate a fake address for unlimited fresh IP buckets, or poison a real one. With the session bucket already free to mint, this left *no* effective rate limit for a cookie-less caller | Honoured only from a peer in `flashseats.bot.trusted-proxies`, **empty by default** (ADR-039) |
| S13 | **`POST /session/reset` discarded the caller's identity on a cross-site form post** — under S6 a hidden form on any site could throw a waiting buyer out of the queue and cut them off from their live hold | **Closed in Pass 13** (ADR-060): the endpoint `consumes` `application/json` only. A form can send only `urlencoded`/`multipart`/`text/plain`, and a cross-origin JSON `fetch` needs a preflight nothing grants, so anything else is `415` and expires nothing. The demo page already sent JSON; kept on every profile because the cluster serves that page. **S6 itself is unchanged** for the other mutating endpoints |

### Must fix before any deployment

| # | Exposure | Detail and fix |
| :-- | :--- | :--- |
| S12 | **Admin auth is an in-memory user** | HTTP Basic against one hardcoded account guards pre-warm and the metrics endpoints. The password is no longer a published default, but this is not an identity system. Replace the `UserDetailsService` bean before anyone else needs access. |

### Structural weaknesses to weigh

| # | Weakness | Assessment |
| :-- | :--- | :--- |
| S5 | **Session identity is free to mint** | The rate limiter's primary bucket is per-`fsid`, and anyone can discard a cookie to get a fresh one. The IP bucket is therefore the only real backstop — and it is deliberately loose (300 burst) so NAT populations are not blocked. This is the ADR-011 trade working as designed, but it means **the session bucket does not constrain a determined attacker at all.** Pass 1 made the IP bucket real (S11). **Pass 9 built the compensating control** — reCAPTCHA v3 on join, failing open, plus `ip_rules` for the manual case (ADR-055). **It is off by default**, because `flashseats.bot.recaptcha.secret` is blank in a clean checkout, so this closes only where someone sets the secret. ADR-044's verified accounts remain the other route: an account costs something to mint, a discarded cookie costs nothing. |
| S6 | **CSRF is disabled while a cookie authorises actions** | Justified for a stateless JSON API, and the checkout path is safe because it needs a `holdToken` an attacker cannot guess. But a cross-site `POST /queue/join` or `POST /holds` *would* succeed against a logged-in visitor and could be used to consume their one-hold-per-event allowance. Low impact, non-zero. Require a custom header, or re-enable CSRF for the mutating endpoints. |
| S7 | **Order numbers are sequential** | `TK-00001`, `TK-00002`. Access is properly controlled, so this is not an IDOR — but it publishes exact sales volume to anyone who buys one ticket. It was worse in combination with S4: a deterministic receipt token over a countable order number meant one leaked secret enumerated every buyer's email. The nonce closes that; the volume leak remains. Prefer a non-sequential public reference. |
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

### Stage 1 — Redis fast path — **DONE** (ADR-046)

`catalog:stock:{eventId}:{tierId}` is the live count. `stock_reserve.lua` / `stock_restore.lua`,
the `-2` fault path end to end, `SETNX` pre-warm restricted to `UPCOMING`, the rebuild from the
ledger, `flashseats.stock.drift` as a live gauge, and `catalog:vouch:{eventId}` refusing to sell
from counters a Redis restart may have rolled back. `tier_inventory` is dropped.

~~**Deferred to Stage 3:**~~ the `hold:{token}` TTL key and the keyspace-expiry listener **shipped in
Stage 4** (ADR-048), two stages later than planned. Latency only — the sweeper is unchanged and still
the guarantee — and the multi-replica claim is now proven by `docker/scripts/hold-expiry-check.sh`:
restored exactly once, in 339 ms, across three replicas.

*Correction for the record:* this section previously said the timer had been "built, tested and
removed". `git log -S` across every commit finds no keyspace listener and no `hold:` key literal
ever committed, so that work lived only in an uncommitted tree and none of it was recoverable.

### Stage 2 — Real money and real defence (Phase 3) — DONE (Pass 9)

- ~~`StripeGateway` implementing the existing `PaymentGateway`~~ — **built** (ADR-052). Server-confirmed
  PaymentIntents, so `FE_SPEC` §2's checkout body and ADR-001's ordering are unchanged.
- ~~the webhook receiver with signature verification and `webhook_events` replay protection;
  `PaymentSettledEvent` → `order`~~ — **built** (ADR-053). The claim is released when settlement
  fails, so a redelivery retries rather than being dismissed as a duplicate.
- ~~The auto-refund path when a webhook arrives against a hold that is gone (ADR-012)~~ — **built and
  now reachable**, with its first test. A refund the provider *refuses* is also no longer recorded as
  a refund: it is counted on `flashseats.payment.refund.failed` and written into `failure_reason`.
- ~~3-D Secure: `PAYMENT_ACTION_REQUIRED` plus `POST /orders/checkout/resume`.~~ — **built, without
  the resume endpoint** (ADR-054). `FE_SPEC` §2 was right and this line was wrong: re-POSTing the
  same body is the retry, and the server retrieves the pending intent rather than charging again.
- ~~Resilience4j around every gateway call — declared as plain beans~~ — **built** as a decorator that
  counts transport failures only.
- ~~reCAPTCHA v3 on join, cached per session, **failing open** (ADR-011)~~ — **built** (ADR-055).
  Off unless a secret is configured, which is deliberate and is also the limit of what it closes.
- ~~`ip_rules`, `bot_audit_logs` (async, non-`ALLOWED` outcomes only), and `V6__bot.sql`.~~ —
  **built**, as `V11__bot.sql`: `V6` has been `V6__pass1_corrections.sql` in every database that has
  run this schema, and a migration is immutable once applied.
- The remaining §10 "must fix" item. (Pass 1 closed S1–S4 and S11; Stage 4 hashed the admin
  credential, so what is left of S12 is a real identity provider, wanted only once a second
  operator does.)

### Stage 3 — Scale and proof (Phase 4) — **done, with two items carried forward**

Full account in **ADR-047**. The stage was scoped "infrastructure only, no application code", and
that held — but only after four unlisted blockers, because the cluster could not start, had no sale
to run, and would have rate-limited its own load harness to nothing.

**Done:**

- ~~Verify promotion fan-out across replicas.~~ **30/30 sessions promoted, 10/10/10 across the three
  replicas.** The highest-value unverified claim in the system, now verified and re-runnable in
  seconds via `docker/scripts/fanout-check.sh`.
- ~~Verify the Redis-restart guard across replicas.~~ All three independently refused to sell, and a
  rebuild on **one** released the event on **all three** — ADR-046's per-event, recomputed-each-tick
  design, observed rather than reasoned about.
- ~~Fix `docker/k6/flash-sale.js` for the ADR-020 admission flow.~~ It had five defects, not one: no
  `/queue/admit` exchange, the wrong success code for join (202, not 200/201), `body.state` for a
  field named `phase`, a read-only mount its own summary write needed, and a `cookies` option k6 has
  never had. Plus the per-VU `X-Forwarded-For` without which the run measures the rate limiter.
- ~~Killing one replica mid-sale.~~ 485 sold + 11 active holds + 4 remaining = 500, exactly.
- ~~Nginx in front.~~ And a defect in it that made **every** proxied API request answer a bare HTTP
  400 — `proxy_set_header` replaces the inherited set rather than merging, so `Host` fell back to the
  upstream name and Tomcat rejected the underscore.
- The load run at the host's ceiling: exactly 500 sold, zero overbooking, zero inventory 503s,
  `stock.drift` `0.0` everywhere, 677 notifications with zero duplicates and an empty DLQ.

**Carried forward, deliberately:**

- ~~**Redis Sentinel**, deferred in ADR-047.~~ **Built in Pass 11** (ADR-058): one primary, two
  replicas, three sentinels in the `cluster` profile, with `sentinel-failover-check.sh` proving
  promotion and rejoin. **The topology is proven and the inventory half is not** — a failover changes
  Redis's `run_id`, so `StockEpoch` distrusts every managed event and the sale stops until an
  operator rebuilds. That is ADR-046 working as designed, and it is untested end to end.
  **No capacity number in this document has been re-measured through Sentinel.**
- **The 10,000-VU run and the p99 number.** Both need a host where the load generator is not
  competing with the system under test; see §9.
- ~~The `hold:{token}` timer and the `__keyevent@0__:expired` listener.~~ **Built in Stage 4**, and
  proven on the rig Stage 3 left behind: restored exactly once, in 339 ms, across three replicas.
- ~~The rest of the metric set and its alarms: `outbox.lag.seconds`, `dlq.depth`,
  `queue.promotion.rate`, `payment.decline.ratio`, `jvm.threads.pinned`.~~ **Mostly built in
  Pass 11** (ADR-058): `outbox.lag.seconds`, `dlq.depth`, `payment.decline.ratio`,
  `payment.webhook.received{type}`, `bot.refusals{outcome}` and `notification.delivered` / `.failed`
  all emit; `queue.promotion.rate` shipped earlier as the untagged `queue.admissions`. What is left
  is `queue.depth{event}`, `hold.conversion.ratio{event}` and `sse.connections.active` — all
  per-event or per-connection shapes — plus `jvm.threads.pinned`. `stock.drift` and
  `hikaricp_connections_pending` are exported and were read per replica throughout.

### Stage 4 — The operator surface (ADR-043) — **done**

**It was not optional, and the reason was concrete.** ADR-029 sends deterministic failures straight
to the DLQ with no retries — correct only if someone can replay them — and ADR-038 went to real
trouble making a dead-lettered claim re-claimable *so that a replay would send*. Nothing could
trigger one. Pass 2 found the consequence: a PDF font failure dead-lettered a **paid** buyer's
ticket into a black hole.

Endpoints live in the module that owns the state; there is no `admin` module, because one would have
to read every other module's internals. Full account in **ADR-048**.

| Endpoint | Module | Note |
| :--- | :--- | :--- |
| `POST /admin/events/{id}/pause` · `/resume` | `catalog` | `EventStatus.PAUSED`; every gate closes through `SaleWindows` with no new code |
| `GET /admin/notifications/dlq` | `notification` | paged, capped, on a partial index (`V8`) |
| `POST /admin/notifications/resend/{orderNumber}` | **`order`** | the payload lives in `outbox_events`, not `notification_logs` |
| `GET /admin/orders/{orderNumber}` | `order` | a distinct DTO that withholds `receiptToken` |
| `POST /admin/events/{id}/rebuild-stock` | `order` | shipped in Stage 1 |
| `POST /admin/events/{id}/prewarm` | `catalog` | shipped in the MVP |

Three things worth carrying forward as design, not trivia:

- **A resend is one new outbox row.** The relay publishes it and the consumer's `claim()` already
  falls through to `reclaimDeadLettered`. No DLQ draining, no shovel, no new facade edge — and
  resending something that already worked sends nothing, because a `SENT` row is not `DLQ`.
- **`PAUSED` split the event query four ways.** A paused sale leaves the promotion loop and the
  browse list, but stays in `findManagedEventIds`, which drives the drift gauge *and* `StockEpoch`.
  Pausing is what an operator does while investigating a counter; a paused event whose counters a
  Redis restart rolled back must be flagged then, not when someone resumes and starts selling from
  them. Verified against the cluster.
- **The admin credential is hashed, not replaced.** §10's S12 says replace the in-memory bean
  *"before anyone else needs access"*; nobody does, and one row does not want a user-management
  surface. What was wrong — plaintext storage — is fixed, and `SecretsGuard` now refuses any
  `{noop}` value rather than one known string. 401/403 finally carry a registry `code`.

A console is presentation and can wait. The endpoints are the capability.

### Stage 4b — Fulfilment and client polish

- ~~The refund-notice template, so `ORDER_REFUNDED` reaches the buyer — and a consumer for
  `notification.order-refunded.queue`.~~ Built.
- Notification failure classification (ADR-029): transient failures earn the retry chain; the
  deterministic ones already skip it.
- `tier-availability` frames in the waiting room (ADR-027) and `RANDOM` queue ordering (ADR-024) are
  built; keep the next UI work focused on browser coverage rather than another API-only proof.
- ~~The React SPA against `FE_SPEC.md`, if the demo client is outgrown.~~ **Built** (PR #16), and
  **dev-only**: `cd frontend && npm install && npm run dev` serves it on `:5173` with `/api` proxied
  to `:8080`. All six views, per-event namespaced storage, `serverTime`-derived countdowns and a
  keyless stub-payment mode so decline, outage and 3-D Secure are walkable in a browser (ADR-058).
  **It is not wired into the cluster** — no compose service, no nginx static root — so
  `--profile cluster` still serves the demo client. Wiring it, and checking it against §9's recovery
  matrix in a real browser, is the next client stage.
- **The Playwright suite specified in `FE_SPEC.md` §8.** Every one of the four client rules is a
  browser behaviour — a skewed clock, a real reload, a live `EventSource` — so none of them is
  reachable from the API suite, and the twelve reload points are checked by hand today. Two of the
  defects Pass 1 fixed were reload-path defects. The spec is written; the implementation is not.

### Stage 4c — Correctness cleanup and concurrent sales (Pass 7 findings)

**The gate:** Pass 7 was a plan-correctness pass and changed no code. These are its findings, in
dependency order. Everything in the first two groups is cheap; the third is the real work.

**Delete what nothing uses** (no behaviour change):

- ~~`OrderFacade.getOrderSummary` (zero callers) and `HoldFacade.releaseHold` +
  `HoldReleaseReason` + the service method behind them (test-only).~~ Built.
- ~~The `ORDER_REFUNDED` decision: build the Stage 4b consumer, or stop writing the rows.~~ Built as
  `OrderRefundedConsumer`.

**Fix the bugs:**

- ~~`V9`: `CREATE INDEX idx_holds_active_tier ON ticket_holds (tier_id) WHERE status = 'ACTIVE'`.~~
  Built, with `quantity` included.
- ~~`GET /orders/{orderNumber}/ticket.pdf`, with `TicketPdfRenderer` moved to `shared`
  (**ADR-050**).~~ Built.
- ~~Move the `fsid` filter from `bot` to `shared/identity`; rename to `flashseats.session.*`.~~
  Built, with `ApplicationModules.verify()` as the check.

**Then concurrent sales** (**ADR-049**), in leverage order, each measurable on its own:

1. ~~Cache `events` + `ticket_tiers` behind `CatalogService`.~~ **Built and measured** (ADR-051) —
   TTL-bounded, rows not entities, misses not cached, recovery paths uncached, evicted after commit.
2. ~~The global admission budget, with the per-event batch as a secondary cap.~~ **Built and
   measured** (ADR-049) — one atomic claim on `queue:budget`, shuffled event order, fails closed.
3. ~~Hoist the exhausted `EXISTS` out of the per-session loop; pipeline the rest of the sweep.~~
   **Both built.** One pipelined round trip per session now, down from four.
4. ~~A per-event index in the emitter registry.~~ **Built**, with one race fixed afterwards: the index
   removed an event's session set once empty while a concurrent connect had already added itself to
   that instance, leaving a live connection in a set nothing iterates.
5. ~~Make the drift gauge a singleton under the promotion tick's Redis-lock pattern.~~
   **Will not do, and the reasoning matters more than the item.** `worstDrift` and `countersMissing`
   are *per-replica* gauges and `pool-pressure.sh` scrapes each replica in rotation. Under a lock only
   the winner updates its gauge and the other two report **0.0 for ever** — so an operator reading one
   replica at random would be told drift is zero two times out of three, on the system's correctness
   canary. It is also the shape ADR-046 explicitly rejected for the Redis-restart guard: *"the verdict
   is derived, never consumed… each replica reaches the same conclusion independently."*
   The cost it would save is nothing: `1 + 2 × tiers` queries per event per minute is **under 4 a
   second cluster-wide** at the top of the `E = 3..10` envelope, on the Index Only Scan `V9` added for
   it, in a run whose slow-query log was empty. The item predates both that index and any measurement.
   **If the triple computation ever does need removing, the only safe form is compute-once-publish-to-all**
   — one replica measures and the others report *its* number — never compute-once-and-let-the-others-lie.
6. ~~Tune the allowance.~~ **Done:** 45 per tick, measured. Five sales now sell out with `pending` at
   zero; `denied` fell from 13,349 to 1,044, so the allowance shapes the opening burst rather than
   capping the sale.

**The drill — built in Pass 7, and NOT YET RUN.** Every other instrument here runs one event, and so
did every measurement the capacity numbers rest on.

```bash
docker/seed/seed-concurrent.sh                 # 9001..9005, all opening at once
docker/scripts/pool-pressure.sh 300 &          # THE instrument for pool pressure
docker compose --profile loadtest run --rm -e VUS=300 k6-concurrent
docker/scripts/sold-count.sh                   # THE instrument for "did it sell, and did it oversell?"
```

**`VUS=300`, not 2,000, on a ten-core host.** At 2,000 the load generator competes with the three JVMs
for the same cores and every number becomes a statement about the host: the same build sold 6 % at 2,000
VUs and **76 %** at 300. Use 2,000 to look for pool saturation, 300 to measure a sale.

**And read `sold-count.sh`, not k6's summary, for what was sold.** k6 counts responses that arrive and
abandons in-flight requests at its 60 s timeout and at ramp-down, so it under-reported by 8× in the run
where latency was worst. The ledger is the authority, and it is also where the no-oversell check lives.

`k6-concurrent` asserts no-oversell **per event** — a global cap would pass while one sale oversold
and another undersold by the same amount. `pool-pressure.sh` samples
`hikaricp_connections_pending`, `hikaricp_connections_active` and `flashseats_stock_drift` **per
replica** (nginx routes only `/actuator/health`, so a load balancer would hand you one at random)
and exits non-zero on sustained pool pressure.

**The pair is the drill, and running k6 alone is worse than not running it.** At `E` sales the
expected failure is requests queuing on HikariCP while p99 collapses — under virtual threads that
produces no error, no 500 and no drift, so the harness reports a green run over the exact condition
it was built to find.

**Status: RUN, and ADR-049 is confirmed with numbers.** 5 sales x 500 seats, 2,000 VUs, three
replicas, 12 Sept 2026.

| Measure | Single sale, 2,000 VUs | **Five sales, 2,000 VUs** |
| :--- | :--- | :--- |
| checkout p99 | 4.7 s | **31.3 s** |
| `hikaricp_connections_pending` peak | (not measured) | **202**, against a pool of 30 |
| tickets sold | 500 / 500 | **370 / 2,500** |
| inventory 503s | 0 | 0 |
| rate limited | 0 | 0 |

**The pool is the bottleneck, exactly where ADR-049 said it was.** Pending peaked at 202, 146 and
109 on the three replicas in turn while `active` sat at the pool maximum of 30. Nothing errored —
that is the whole point, and it is why `pool-pressure.sh` exists rather than the load harness
answering this. p99 went from 4.7 s to 31.3 s for the same VU count spread over five sales, against
a 200 ms exit criterion.

**The system could not drain its own queues.** 370 tickets sold out of 2,500 available: buyers were
admitted faster than checkout could serve them, so they sat in a saturated pool until their holds
expired. Five sales with plenty of stock left behind ended up selling less than one sale did.

**And the saturation cost real inventory.** `flashseats.stock.drift` reached **7**, with **14 seats
invisible** across three of the five tiers:

```
tier   cap  confirmed  held  redis   sum   drift
9001   500         54     3    443    500    +0
9002   500        102    14    378    494    -6
9003   500        108    13    378    499    -1
9004   500        120    27    346    493    -7
9005   500         79    20    401    500    +0
```

**The sign is the reassuring part.** Drift is *negative* — under-counted, never phantom. That is
invariant 12 holding under the exact pressure it was written for: a reserve took seats from Redis and
its hold row then failed to commit ambiguously, so the compensation correctly declined to return
them (ADR-046). Invisible seats are lost revenue a rebuild recovers; phantom seats would be an
oversell nothing recovers.

**The documented recovery works.** `POST /admin/events/{id}/rebuild-stock` on the three affected
events returned the seats, and drift read `0.0` on all three replicas at the next gauge interval.
No oversell at any point, on any tier.

**So the argument for ADR-049 is no longer latency alone.** Pool saturation under concurrent sales
*loses inventory* — recoverable only by an operator who notices the gauge and runs a rebuild. That is
a much stronger reason to bound admission globally than p99 was.

**Two bugs the drill found on its way to the answer**, both in §9.

### Stage 4c, measured — the Pass 8 runs (12 Sept 2026, evening)

Both fixes are property-driven, so the drill was run three times on one host, back to back, to separate
them. 5 sales × 500 seats, 2,000 VUs, three replicas.

| Run | VUs | Configuration | peak `pending` | admitted | **seats sold (ledger)** | checkout p99 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| *(Pass 7)* | 2,000 | no cache, per-event cap only | **202** | — | 370 *(client count)* | 31.3 s |
| **A** | 2,000 | cache **off**, allowance unlimited | **1,004** | 857 | not measurable | 30.5 s |
| **B** | 2,000 | cache **on**, allowance unlimited | **330** | 246 | not measurable | — |
| **C** | 2,000 | cache **on**, allowance 11/tick | sampled 0, logged **217** | 352 | 162 | 34.6 s |
| **D** | 2,000 | + promoter off the pool | **54** | 308 | 203 | 45.0 s |
| **E** | **300** | same as D | **10** | 1,414 | 1,904 / 2,500 — 76 % | 9.3 s |
| **F** | **300** | **allowance 45/tick** | **0** | **2,002** | **2,496 / 2,500 — 99.8 %** | 10.0 s |
| **G** | **300** | + one-round-trip queue read | **2** | — | **2,500 / 2,500 — 100 %** | 4.8 s |
| **H** | **300** | **+ Sentinel topology** (Pass 12) | **0** | — | **2,499 / 2,500 — 99.96 %** | **129 ms** |

**Runs F and G are the answer: five sales open at once sell out, and nothing oversells.** Run G took
every seat — 500 of 500 on all five tiers — with `sold + held + redis == 500` throughout and
`hikaricp_connections_pending` never above 2 of 90.

**Run H (Pass 12) is the first measured through Sentinel**, and answers the question the topology
change raised: it does not regress. 2,499 of 2,500 across the five tiers, `sold + held + redis == 500`
exact on every one, zero inventory 503s, zero rate-limited requests, and
`hikaricp_connections_pending` **0** across 54 samples on three replicas with drift `0.0` on every
sample. The single unsold seat is an artefact of the run, not the build: the failover test earlier in
the same session rebuilt 9001's counter, and one seat was still held when the drill started.

**Read H's 129 ms against G's 4.8 s with care — the host is most of that difference, not the code.**
G was measured with other work on the same laptop; H ran with nothing but the cluster. It is
nonetheless the first time the **200 ms checkout p99 exit criterion has been met at all**, and the
first evidence that the criterion is reachable on this design rather than only on a bigger machine.
One run, one host: treat it as a data point, not as the criterion being closed. Sampling covered the
ramp and steady state; the last ~45 s of ramp-down was not sampled.

Run F's table below is kept because it is the one that exposed the tail-of-sale edge, four seats short:

```
event  tier  cap  sold  held  redis   sum
9001   9001  500   499     0      1    500
9002   9002  500   500     0      0    500      <- sold out, exactly
9003   9003  500   497     0      3    500
9004   9004  500   500     0      0    500      <- sold out, exactly
9005   9005  500   500     0      0    500      <- sold out, exactly
```

Three tiers at **exactly 500 of 500** — the case where oversell would show, and the counter reached zero
and stopped. `sold + held + redis == 500` on all five. Zero inventory `503`s. And
`hikaricp_connections_pending` peaked at **0**: the cluster sold 2,496 seats across five simultaneous
sales without the pool ever making a caller wait.

**Run F's four unsold seats are worth explaining, because the reason is a real edge and not the clock**
— and it is still there in run G, which simply did not hit it. At the end of run F they were
*available* — no active holds — and **48 to 60 buyers were still queued for
every sale**. They were not promoted because admission accounting counted them as already claimed:

```
admittable = floor(remaining × oversubscribeFactor) − pendingPasses − liveAdmissions

9001:  floor(1 × 1.5) − 0 − 2  =  -1      1 seat, 2 live admission sessions
9003:  floor(3 × 1.5) − 4 − 0  =   0      3 seats, 4 outstanding passes
```

Those passes and sessions belong to buyers who had not bought. So the seats were reserved *notionally*
for people who never took them, and the next buyer in line could not be let through until a pass expired
(120 s) or an admission lapsed (600 s) — not one more tick, and **not a seat that could never sell.** In
a real sale they sell within ten minutes.

**It does expose where the oversubscribe factor stops working: the tail.** 1.5 exists because
conversion is below 100 %, but `floor(1 × 1.5) = 1`, so at one seat remaining the factor grants no slack
at all — exactly where a single unused pass can idle the last seat for its full TTL. A small floor at
the tail would close it, at the cost of admitting buyers who may arrive to find it gone, which ADR-008
exists to prevent. **Left as is, deliberately**, and now written down: at 99.8 % the trade is sound, and
the alternative trades a measurable last seat for an unmeasurable number of wasted journeys.

**What changed between E and F was one number**, and the way it was wrong is the more useful finding.
ADR-049 said to derive the allowance from the per-buyer connection cost, which became
`90 connections ÷ 8 transactions = 11`. **Those units do not compose:** 90 is a concurrency, 8 is a
count over a session lasting minutes, their quotient is neither — and it was then spent as a per-second
rate. Run E's instruments said so plainly: `pending` at 10 of 90 while `denied` refused ten admissions
for every one granted. The fix is a single property that *is* a rate,
`global-admission-budget-per-tick`, at ADR-028's own 45 — re-scoped from one sale to the cluster, which
is the only thing ADR-049 ever needed to change. In run F, `denied` fell from 13,349 to 1,044: the
allowance now shapes the burst instead of capping the sale.

**So the 6–8 % figures in runs A–D are not results about this system.** They are what happens when k6
with 2,000 VUs and three JVMs contend for ten cores: every request takes tens of seconds, holds expire
before their buyer can pay, and the funnel never fills. At 300 VUs the same build sells out.

**Run E also confirms the harness defect from the other direction.** Its client count (1,898) and the
ledger (1,904) agree to within six seats, because almost nothing timed out. The 8× gap in run C was
entirely in-flight purchases k6 abandoned.

**The pool stopped being the bottleneck, which is what ADR-049 claimed.** `pool-pressure.sh` exits 2 on
sustained pressure: it did for A and B and **not** for C. The allowance was doing the work the counters
say it was — `flashseats.queue.admissions` totalled **352** across the cluster against **3,358** on
`admission.budget.denied`.

**Caching alone is worth about 3×** on pool pressure (1,004 → 330) and nothing else on its own: B still
saturated, just later. That is the ordering the plan assumed, confirmed rather than argued.

**Two things this run first reported wrongly, both now fixed.**

**1. "Tickets sold" was a client-observed floor, and it under-reported by 8×.** k6 counts checkout
responses that *arrived*; its default request timeout is 60 s and the scenario ends with a graceful
ramp-down, so every request still in flight when either fires is scored as a failure and counted as
nothing — while the server went on to commit the order. The run logged **757 interrupted iterations**.
k6 said 21 tickets. The ledger held **109 confirmed orders and 162 seats**, and the invariant was exact
on all five tiers:

```
event  tier  cap  sold  held  redis   sum
9001   9001  500    39     0    461   500
9002   9002  500    39     0    461   500
9003   9003  500    37     0    463   500
9004   9004  500    16     0    484   500
9005   9005  500    31     0    469   500
```

`docker/scripts/sold-count.sh` now reads that from PostgreSQL and Redis and checks the invariant, and
the k6 summary labels its own number as a floor and points at it. A and B are "not measurable" because
each run's seed deletes the previous one's orders — only the last run's ledger survives, which is its
own lesson about the drill.

**2. "`pending` read 0.0 at every sample" overstated the instrument.** `pool-pressure.sh` rotates one
replica per five-second tick, so each replica is sampled every ~15 s. The application logs from the same
run show `waiting` reaching **217**. The honest claim is *no sustained pressure at 15-second
resolution*, which is what the script is built to detect and is still the ADR-049 result — but the
spikes were real and the sampler cannot see them.

**And the residual latency is CPU starvation, now with evidence rather than inference.** 1,218
HikariCP timeouts across the three replicas in run C, and their pool state at the moment of throwing:

```
timed out after 9821ms (total=30, active=5, idle=25, waiting=75)
```

**Twenty-five of thirty connections idle while seventy-five threads wait**, and a timeout configured at
3,000 ms firing at 9,821 ms. A pool with idle connections does not make callers wait; an unscheduled
thread does. `docker stats` agrees — each replica at **114–142 % of one core** on **~350 MiB of
7.65 GiB** — but the Hikari numbers are the proof: ten cores shared between three JVMs and a 2,000-VU k6
inside the same Docker VM.

**That corrects a claim this section has carried since Stage 3.** "Three JVMs take ~6 GB of the 7.65 GB
this machine gives Docker" is wrong — measured, they take about **1 GB between them**. The 10,000-VU
run and the real p99 are blocked on **CPU**, not memory, so a bigger machine is the wrong fix and a
machine where the load generator is not competing for the same ten cores is the right one. Freeing host
RAM changes nothing; Docker's allocation is a fixed VM size either way.

**What the runs license.** Five concurrent sales **sell out** — 2,496 of 2,500 — with no oversell, no
drift, no inventory `503`s and `hikaricp_connections_pending` at zero throughout. That is the exit
criterion this stage existed for, and ADR-049 and ADR-051 are the two changes that get there.

**What they still do not license is the latency number.** Checkout p99 is 10 s against a 200 ms
criterion, and `pending` at zero says it is not the pool. The single-sale run at the same 300 VUs was
682 ms, so the 15× is the cost of five concurrent sales somewhere other than the database — and finding
it needs a host where the load generator is not sharing ten cores with three JVMs. That is the one
open number, and it is stated as open rather than implied by a sellout.

**Nothing in the sale path ever failed, in any run.** In run C's 2,000-VU conditions, 145 holds were
created and **every one was a `201`** — no `409`, no `503` — and 109 of them became orders. The funnel
was starved at the top, not broken in the middle. Run E's 76 % is the same code with the host out of
the way.

**Two hypotheses were tested and killed on the way to that, which is worth recording so nobody retests
them.** Neither was the limiter:

- **Scheduler serialisation.** Nine `@Scheduled` jobs share one scheduler; if it had a single thread,
  a slow promotion tick would stall the outbox relay and the sweeper too. `/proc/1/task/*/comm` in a
  live container shows **nine** scheduler threads for nine jobs. They do not serialise.
- **The promoter's own database dependency.** Real, fixed, and **not worth a throughput number**:
  admissions went 352 → 308 across runs C and D, which is noise. The fix stands on its availability
  argument alone (ADR-051's amendment) — a component that bounds admission must not be able to queue
  behind the buyers it admits — but it did not unlock anything, because the pool was not what was
  blocking at 2,000 VUs. CPU was.

**One transient drift sample of 1.0** appeared in run C and read `0.0` on all three replicas afterwards
with no rebuild — the documented behaviour for a gauge that does not read Redis and PostgreSQL in one
snapshot. `pool-pressure.sh` exits 1 on any drift by design, which is the right default for an
instrument and means run C's exit code reports the transient rather than the pool.

**A second-order observation worth keeping.** With admissions living 600 s (ADR-020), a cluster too slow
to convert them starves its own queue: the inventory bound is
`floor(remaining × 1.5) − pendingPasses − liveAdmissions`, so buyers admitted and then unable to finish
hold the allowance down for ten minutes. It is correct behaviour and it is why C admitted 352 rather
than the ~2,300 its allowance permitted.

### Stage 5 — Buyer accounts, as an overlay (ADR-044)

**`fsid` stays the only session identity; ADR-010 does not change.** An account is a second,
*durable* identity that attaches to purchases and to nothing else — never to queue position, hold
ownership or rate limiting, all of which must keep working for a visitor who has never signed in.

- New leaf module `com.flashseats.account`; `order` and `saleflow` depend on it, it depends on
  nothing, the graph stays acyclic.
- `orders.account_id`, nullable, stamped **at checkout only** inside the existing transaction.
- An anonymous purchase can be **claimed** later by presenting its `receiptToken` — that capability
  already exists and already proves possession, so claiming needs no new mechanism.

The concrete gap it closes: the `fsid` cookie's `max-age` is **86 400 seconds**, so order access by
cookie works for exactly one day. After that a buyer's only route to their own order is the receipt
link in their email — lose the email, lose the ticket. `fs.recentOrders` in `localStorage` is a
per-browser hint, not a record.

It is also §10 S5's missing compensating control: a verified account is a rate-limit bucket that
costs something to mint, where a discarded cookie costs nothing.

**Whether login gates the queue is a product decision, not a technical one** — the design supports
either, and the default is not to gate it.

**S9 stops being deferrable here.** Plaintext `user_email` with no retention policy is one thing for
an anonymous transaction and another hanging off a named account: a deletion path and a stated
retention period ship *with* this stage.

### Observability: already the right shape, still thin (ADR-045)

`/actuator/health` is public because it is the container healthcheck target; `metrics` and
`prometheus` are behind `ROLE_ADMIN` because they are a live read on how the sale is going. **Both
are correct and neither needs changing.** What is thin is what they report:
`flashseats.stock.drift` — the one alarm that should page — is asserted in tests and not exported,
because a PostgreSQL counter cannot diverge from itself. It becomes real in Stage 1; the rest of the
§9 alarm set lands in Stage 3. Do not mistake a green `/actuator/health` for observability.

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
   informally. Walk the twelve reload points by hand — or build the Playwright suite specified in
   `FE_SPEC.md` §8, which exists to stop that being a manual job.

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

### Pass 1 — first review of the built MVP

- **Scope:** an end-to-end review of all nine modules, the demo client, the infrastructure config and
  the test suite, from the user's perspective and the attacker's. Three findings were reproduced by
  running them before anything was changed.
- **Method:** five independent passes over one shared reading of the codebase — checkout state
  machine, queue and SSE lifecycle, async fulfilment, security, and a walk of the nine-step journey.

**Verified defects, each now closed by a stated rule.**

| Found | Rule |
| :--- | :--- |
| A gateway error left the order `PENDING` forever, and `PENDING` answered every retry with `409 DUPLICATE_PAYMENT` — so a buyer holding live seats was told to retry and could not, on any card | **ADR-034** — a `PENDING` order is in-flight, never terminal |
| `COALESCE(SUM(remaining), 0)` made an un-warmed event look sold out; the promotion worker's `COUNTER_UNAVAILABLE` guard was therefore unreachable, and its response to "sold out" was to broadcast `sale-exhausted` **and delete the waiting ZSET** | **ADR-035** — "no counter" is never "zero", and `EXHAUSTED` is derived, not destructive |
| Rehydration returned only `PENDING` orders, so reloading after a purchase showed the landing page and invited the buyer to queue for seats they already owned | **ADR-037** — `/sale/state` reports the latest order whatever its status |

**Also found and fixed.**

| Found | Rule |
| :--- | :--- |
| A sale closing on the clock froze its waiting room: rank was checked before the window, and both the promoter and the broadcaster iterated only *open* events. `sale-closed` had no producer and `QueuePhase.EXHAUSTED` was never returned | **ADR-036** |
| `queue:pass:{sessionId}` was not event-scoped, so one visitor in two concurrent sales had one promotion overwrite the other | **ADR-036** |
| `queue:passes` and `queue:admissions` were never trimmed and had no TTL, under `noeviction` | **ADR-036** |
| A dead-lettered email permanently consumed its own claim, so a DLQ replay acknowledged without sending | **ADR-038** |
| `X-Forwarded-For` was trusted from any client, leaving no effective rate limit for a cookie-less caller | **ADR-039** |
| The receipt secret defaulted to the *session* secret's env var; no token was domain-separated; receipt tokens were deterministic and unexpiring | **ADR-039** |
| `ticket_holds.quantity CHECK (… <= 6)` hardcoded one input of a configurable limit, and `HoldService` reported *every* constraint violation on the table as `HOLD_LIMIT_EXCEEDED` | `V6__pass1_corrections.sql`; the catch now matches the index name |
| `flashseats.queue.ordering=FIFO` had no backing field and was silently ignored | Removed, with a note that it returns with ADR-024 |
| `HoldFacade.releaseHold` accepted a reason and discarded it, recording every release as `USER_CANCEL` | `HoldReleaseReason` enum, `SettleReason.ORDER_ABORT` |

**Found while fixing, not in the original review.**

- **`NotificationLogService.claim` could not return `false`.** A flush that violates a constraint
  marks the transaction rollback-only, so the catch block's "already handled" threw
  `UnexpectedRollbackException` at commit and the consumer read it as a delivery failure — meaning
  **every redelivered message went to the DLQ** instead of being quietly acknowledged. Now
  `INSERT … ON CONFLICT DO NOTHING`, a rowcount like every other claim in the system (ADR-038,
  global standards §3 rule 8).
- **The first cut of domain separation was ambiguous.** A space delimiter makes
  `("pass", "admit x")` and `("pass admit", "x")` sign identical bytes. Caught by the test written
  for it; the kind is now length-prefixed.
- **`ModularityTests` rejected the first `SecretsGuard`**, which read three modules' `config`
  classes. It reads the `Environment` instead — which is also closer to what it means.

**Verified correct, so the next pass need not re-derive it.**

- Zero `Instant.now()`, `System.currentTimeMillis()` or `LocalDate.now()` in `src/main/java` (§12.6).
- No `synchronized` anywhere in main; the virtual-thread pinning rule holds (§12.4).
- Every `@Transactional` contains SQL only, and the three bean splits are real proxies (§12.4).
- `AFTER_COMMIT` is safe to lose: skipping `OrderPostCommitTasks` entirely leaves the system
  correct (§12.5).
- ~~Eleven registry codes are unreachable~~ — it was ten, and Stage 4 reached three of them
  (`NOTIFICATION_LOG_NOT_FOUND` remains forward contract). The rest are still deferred stages.

**Deferred, with reasons, to Stage 3:** RabbitMQ publisher confirms and the `QueueBroadcaster`
fan-out cost. Both are load-path concerns and neither can be validated until the k6 harness runs.
Recorded in §9 rather than left implied.

- **Result:** 39 tests green, up from 25. The three verified defects each have a test that fails
  against the old behaviour.

### Pass 2 — second review of the built MVP

- **Scope:** a full PR-style review of all nine modules, the demo client, the infrastructure config
  and the test suite, read against the whole documentation pool first — 39 ADRs, the global
  standards, `FE_SPEC.md`, the end-to-end flow and the nine module specs — and then against the code.
- **Method:** documentation as the context pool, then a module-by-module audit, then two sweeps the
  first pass had not done explicitly: every call site of a method that can return a *fault code*, and
  every error path that never reaches a registry `code`.

**Verified defects, each now closed by a stated rule.**

| Found | Rule |
| :--- | :--- |
| `CatalogService.toTierResponse` clamped the counter with `Math.max(remaining, 0)`, so a tier with no `tier_inventory` row was published to every visitor as `SOLD_OUT` — and the client renders a `SOLD_OUT` tier **unclickable**. ADR-004's failure mode on the browse path; ADR-035 had closed the promoter and the reserve path and missed the third caller. The dev seeder demonstrates it out of the box | **ADR-040** — an unreadable counter is `UNKNOWN`, never a bucket |
| `@ExceptionHandler(Exception.class)` matched Spring's own binding exceptions before `DefaultHandlerExceptionResolver` could, so a missing query parameter answered **`500 INTERNAL_ERROR`** with no `code` and an `ERROR` stack trace. Reproduced before the fix on `/queue/status` and `/events/{bad-id}` | **ADR-041** — a catch-all advice must name what the framework throws first |
| The notification consumer's single `catch` spanned send, `markSent` and `basicAck`, so a failure *after* the mail server accepted the message marked the row `DLQ` — which ADR-038 makes re-claimable, sending a second ticket | **ADR-042** — `DLQ` means the work did not happen |

**Also found and fixed.**

| Found | Fix |
| :--- | :--- |
| `TicketPdfRenderer` drew operator-supplied text with a standard-14 font, which throws on anything outside WinAnsi. Deterministic, so ADR-029 correctly skips the retry chain — and with no admin replay endpoint, one Hebrew or CJK character in an event title cost a **paid** buyer their ticket permanently | Text is sanitised before drawing: accents transliterate, the rest degrades to `?`, and a warning is logged. A Unicode TTF is the Stage 4 answer |
| `FE_SPEC.md` §3 listed `INSUFFICIENT_TIME_REMAINING` as "seats gone" and the client cleared the hold and re-routed. The server keeps the hold (ADR-030), so rehydration returned to the same checkout screen and the same `409` — a loop on the payment screen | Spec row corrected; the client now offers *Release seats* and disables Pay |
| `POST /queue/admit` took `eventId` as a query parameter where `FE_SPEC.md` §2 specifies a body; `DELETE /holds/{token}` returns `204` where the spec said `200` | `admit` moved to a body (`AdmitRequest`); the spec corrected to `204` |
| `spring.profiles.active=dev` was compiled into `application.properties`, so a jar started with no `SPRING_PROFILES_ACTIVE` ran on development secrets with `SecretsGuard` silent and the dev seeder active | Removed. `dev` is set by the `spring-boot-maven-plugin` for `./mvnw spring-boot:run`; the artefact defaults to refusing (ADR-039) |
| `/api/v1/queue/stream` was skipped by `RateLimitFilter` entirely, where ADR-011 says "counted once at connect" | The stream is filtered and charged once at connect; per-frame accounting was never possible anyway, since frames are server-pushed |
| `OutboxEventRepository.markProcessed` had no status guard, so a relay finishing after its claim had been swept back to `PENDING` could mark a row processed that another claim now owned | `AND status = PROCESSING`, like every other claim in the system |
| `SseEmitterRegistry.sendPosition` emitted `-1` for an unknown estimate where `QueueStatusResponse` and `FE_SPEC.md` both use `null`; `closeAll` removed by key alone, so a reconnect racing a terminal sweep lost its fresh emitter | `null` on the wire; two-arg `remove(key, value)` |
| `GET /events/{id}` issued one counter lookup per tier — an N+1 on the single hottest endpoint | One `findRemainingByEvent` per request |

**Removed rather than fixed.**

- `shared/money/Money.java` and `bot/exception/RateLimitExceededException.java` — zero usages each.
  The rate-limit filter writes its problem document directly, because a filter runs before any
  advice can see it.
- `stripe-java`, `resilience4j-circuitbreaker`, `resilience4j-retry`, `spring-boot-starter-thymeleaf`
  — declared ahead of the stages that use them and on the classpath for no reason yet. Thymeleaf also
  autoconfigures a view resolver into an API that returns only JSON. Versions are recorded in the pom
  comment so re-adding each is one line.

**Verified correct, so the next pass need not re-derive it.**

- The checkout sequence matches ADR-001/023/030/034 exactly, including that `markAbandoned` only
  touches a still-`PENDING` row, which is what makes the blanket `catch` safe.
- The settle-once claim is the sole path to stock restoration, in all four endings.
- `consumeHold` and `tryReserve` are both `Propagation.MANDATORY` — they cannot silently run
  without the caller's transaction.
- Still zero `synchronized` and zero `Instant.now()` in `src/main/java`.
- Every `@Transactional` still contains SQL only; the three bean splits are still real proxies.
- Token domain separation is length-prefixed and covers all four kinds.

- **Result:** 49 tests green, up from 39. Each of the three verified defects has a test that fails
  against the old behaviour; the `500`-instead-of-`400` one was reproduced by reverting the handler.

### Pass 3 — cleanup and simplification, no behaviour change

- **Scope:** all nine modules, `src/main` only. An explicitly **zero-logic-change** pass: no
  workflow, state transition, validation rule, schema, facade signature, REST mapping, DTO shape or
  event payload was altered. `src/test` was not touched, and the same 53 tests pass before and after.
- **Method:** mechanical scans first — unused imports, unreferenced private and package-private
  methods, unreferenced public methods that no framework invokes, `System.out`/`printStackTrace`,
  commented-out code, `TODO`/`FIXME` — then a module-by-module read.

**What the scans found: almost nothing.** Zero unused imports, zero dead private methods, zero
print statements, zero commented-out blocks, zero `TODO`s. Two passes of review had already removed
the usual material. What follows is the remainder.

**Removed.**

| Removed | Why |
| :--- | :--- |
| `SseEmitterRegistry.isLocal` | No caller anywhere, in `main` or `test` |
| `QueueBroadcaster.drainRatePerSecond` | Documented as "exposed for tests"; no test ever used it. `QueueDrainRateTracker.perSecond` is public and reachable directly |
| `SaleStateAssembler.ThrowingSupplier` | A private functional interface declaring `T get()` and throwing nothing — `java.util.function.Supplier` exactly, under a name that promised otherwise |

**Deduplicated.**

- `QueueTokens.notExpired` and `ReceiptTokens.notExpired` were character-identical: parse an epoch
  second, compare against the clock, and treat a `NumberFormatException` as *not valid* rather than
  letting it escape. That last part is the reason it is now one method — a second copy that let the
  exception through would turn a tampered token into a `500`. Extracted to
  `shared/time/Expiry.notPassed(Clock, String)`, alongside `ClockConfig`, on the same footing as
  `SignedToken`: a primitive with no state and no business rule, used by more than one module.

**Boilerplate and readability.**

| Change | Effect |
| :--- | :--- |
| `@Slf4j` in place of `private static final Logger log = LoggerFactory.getLogger(X.class);` | 20 classes, three lines each. Lombok was already a dependency with annotation processing configured for both compile phases; it had been confined to JPA entities |
| Fully-qualified types replaced with imports | `java.time.Clock` in `QueueBroadcaster`, `amqp.core.Message` and `StandardCharsets` in `RabbitOutboxPublisher`, `EventStatus` in `EventRepository` |
| Import blocks sorted | Ten files where `tools.jackson.*` or `ConditionalOnProperty` sat out of order at the top |
| `FlashseatsApplication` re-indented | The only tab-indented file in `src/main` |
| `RateLimitFilter.doFilterInternal` | A reassigned `allowed` flag collapsed into one short-circuit expression. Same evaluation order, so a request already refused by its session bucket still does not spend an IP token — now stated in a comment rather than implied by control flow |
| `HoldService` | The Javadoc for `requireAdmission` sat stacked above the Javadoc for `isOneActiveHoldPerSession`, so the tool-rendered docs attributed it to the wrong method. Moved to the method it describes |
| `HoldFacadeImpl` | `toSummary` narrowed to `private`; the two mappers moved below the `@Override`s so the interface implementations read in declaration order |

**One efficiency fix.**

- `TicketPdfRenderer.drawable` called `WIN_ANSI.newEncoder()` **inside** its per-character loop —
  one `CharsetEncoder` allocated per character of every event title, venue and tier name on every
  ticket page. Hoisted to one per call. It cannot become a static constant: `CharsetEncoder` is
  stateful and not thread-safe, and this method is `static` on a shared bean.

**Deliberately left alone.**

- `TicketHoldRepository.sumActiveQuantityForTier` and `OrderRepository.sumConfirmedQuantityForTier`
  have no callers, but both are the documented scaffolding for the `flashseats.stock.drift` metric
  (invariant 1, ADR-045). Unreferenced is not the same as unwanted.
- The `*Properties` getters that only Spring's binder and the `configprops` endpoint read.
- JPA entities keep `@Getter`/`@Setter`/`@NoArgsConstructor`. Hibernate requires a mutable class
  with a no-arg constructor; they are not candidates for records.
- `PDType1Font` is still constructed per line rather than hoisted. PDFBox 3 made the standard-14
  fonts non-static precisely because sharing one across documents is unsafe.
- No ADR was added or amended. Nothing here is a decision.

- **Result:** 53 tests green, unchanged from before the pass. 96 insertions, 156 deletions across
  30 files, plus one new 33-line class.

### Pass 4 — Stage 1, the Redis fast path

- **Scope:** moving the live inventory count from PostgreSQL to Redis without weakening a guarantee,
  then a review of the result against the question "is this a Redis-first design anyone could read".
- **Method:** built in ordered steps, each left green — the counter primitives, then the flip and
  its test fixture, then the rebuild and the drift gauge. A design review before the first line of
  code found five failure modes worth the rework, and a review after it found three more.

**What the design review found before implementation.**

| Found | Consequence |
| :--- | :--- |
| The sweeper settles up to 500 holds in one transaction | An inline `INCRBY` would return every earlier hold's seats and leave those holds `ACTIVE` on any failure. Restoration moved to `AFTER_COMMIT` |
| Compensating a reserve on *any* exception | A commit failure is ambiguous; returning seats that may still be held is an oversell. Compensate only on the constraint rejection |
| `catalog.md`'s rebuild filters `expires_at > now()` | An expired-but-unswept hold is subtracted by nobody and restored by the sweeper — the same seats twice |
| A rebuild reading the ledger once | Misses a hold whose `DECRBY` has landed and whose row has not, and writes a count that is too high. Two snapshots, smaller wins |
| Nothing detected a Redis restart | AOF `everysec` brings counters back **high**. `StockEpoch` and `catalog:vouch:{eventId}` |

**What the review after implementation found.**

| Found | Fix |
| :--- | :--- |
| `tier_inventory` had become write-only — pre-warm and rebuild wrote it, nothing read it, and the rebuild derives from the ledger without it | Dropped (`V7`). A stale column named `remaining` reads exactly like the truth. Losing V1's `CHECK (remaining >= 0)` is a real trade, recorded in ADR-046 |
| The restart guard consumed its own signal: one in-memory flag plus one shared stamped key, so whichever replica noticed first protected only itself | Per-event vouched `run_id` in Redis, verdict recomputed each tick. Also less code — no global key, no flag, no `SCAN` |
| `StockCounterRepository` published the Lua return codes, so `-1` meant "sold out" there and "no counter" one layer up | `reserve()` returns `ReserveResult`, `restore()` returns a boolean; the numbers are private |

**Cut after building it.** The `hold:{token}` timer and keyspace listener were built, tested and
removed. Proving the listener fires needs the sweeper slowed and proving the sweeper suffices needs
the listener off — two `@TestPropertySource` classes, so two extra Spring contexts, so three sets of
schedulers over shared containers. `HoldLifecycleIT` then failed in the suite while passing alone:
exactly the failure `SaleFixture`'s own comments warn about. It is a latency optimisation whose one
real claim is a multi-replica one, so it moved to Stage 3 intact.

**Verified by hand against the live stack.** The existing dev database was the true cutover case —
counters in SQL, none in Redis — and reported every tier `UNKNOWN` rather than `SOLD_OUT`, which is
ADR-040 holding through the change. `rebuild-stock` then reconstructed event 2 as `200 − 5 sold =
195` from three genuine confirmed orders, before and after the table was dropped.

**Cutover note.** A database predating Stage 1 has no counters, so holds answer `503` until
`rebuild-stock` runs once per open event. That is the designed behaviour: the alternative is seeding
from capacity, which is precisely ADR-004's prohibition.

- **Result:** 68 tests green, up from 53.

### Pass 5 — Stage 3, the cluster under load

- **Scope:** infrastructure only — Nginx, k6, the multi-replica drills — with no `src/main/java`
  change, no new dependency, and no module-spec change. Full rationale in **ADR-047**.

**The stage's own premise was the first thing to fail.** "Infra only, no app code" held, but
"existing infra just needs running" did not: the cluster could not start, had no sale to run, and
would have throttled its own load harness.

| Found | Fix |
| :--- | :--- |
| **nginx `proxy_set_header` replaces the inherited set rather than merging.** The three locations that set `Connection ""` dropped `Host` and `X-Forwarded-For`; `Host` fell back to `$proxy_host` = `flashseats_app`, and Tomcat rejects underscores in a domain name. **Every proxied API request answered a bare HTML 400** — below Spring, so no `ProblemDetail` and no `code` | The shared set moved to `docker/nginx/proxy-headers.conf` and is `include`d by every proxying location, so adding a location cannot reintroduce it |
| `isTrustedProxy` takes exact addresses (ADR-039, on purpose), but nothing gave the proxy a fixed one. Unset, all 10k buyers share nginx's one IP bucket | Explicit compose subnet; nginx pinned to `172.28.0.10`, which `FLASHSEATS_TRUSTED_PROXIES` names |
| **k6 is one container, so 10k VUs are one IP bucket** — capacity 300, refill 150/s. The run would have measured the rate limiter | Per-VU synthetic `X-Forwarded-For`. The faithful simulation, not a bypass: 10k real buyers do come from many addresses |
| `CatalogDevSeeder` is `@Profile("dev")` and there is no create-event endpoint, so the `docker` profile has an empty catalog | `docker/seed/seed.sql` seeds reserved event **9001** as `UPCOMING`; `seed.sh` pre-warms it — the only sanctioned way to write a counter (ADR-004) — and waits for `OPEN` |
| Seeding id 1 with `ON CONFLICT DO NOTHING` silently deferred to the dev seeder's 700-seat event while the test asserted a capacity of 500 | Reserved id, and the seed resets its own event in PostgreSQL *and* Redis so a second run is a real second run |
| `.env` shipped byte-identical to `.env.example`, so `SecretsGuard` refused all three replicas | `docker/secrets/gen-env.sh`, idempotent — it never rotates a secret already set |
| k6 sent the pass to `/holds` as `X-Queue-Pass-Token`; `/holds` reads `X-Admission-Token`, so no run ever reached checkout | The `/queue/admit` exchange, where ADR-020 revokes the pass |
| k6 scored join on `200\|\|201` (it is **202**) and read `body.state` (the field is **`phase`**), so `join_success_rate` was always 0 and sold-out was never detected — every VU burned its full 180 s deadline | Both corrected; `checkout_duration_ms` threshold also moved from 2000 ms to the roadmap's actual 200 ms |
| `handleSummary` wrote to a `:ro` mount, and `cookies: {enabled:true}` is not a k6 option | Mount made writable; the bogus option dropped — k6 gives every VU its own jar by default |

**What the rig then proved.**

| Claim | Result |
| :--- | :--- |
| **Promotion fan-out across replicas (ADR-007)** | **30/30 promoted, 10/10/10 across the three upstreams.** The highest-value unverified claim in the system |
| No overbooking under load | **Exactly 500 sold** at 300 and 2,000 VUs. Zero inventory 503s, zero rate-limited requests |
| `confirmed + active_holds + remaining == total_capacity` | Held **exactly** on every check, including mid-drain: 485 + 11 + 4 = 500 immediately after a replica was killed, and 485 + 5 + 10 = 500 as the sweeper returned seats |
| Redis restart mid-sale (ADR-046) | All three replicas independently refused to sell; a rebuild on **one** released the event on **all three** |
| Killing a replica mid-sale | No stock lost or invented. Five orders left `PENDING` — the in-flight case ADR-034 exists for, recoverable rather than stranded |
| Fulfilment | 677 notifications `SENT`, **zero** `(order_number, kind)` duplicates, DLQ empty |
| `stock.drift` | `0.0` on all three replicas after every run |
| `QueueBroadcaster` sweep cadence | Median gap **2,016 ms against a configured 2,000 ms** at 2,000 VUs — keeping up. The batching fix stays deferred on evidence, not arithmetic |

**Measured, not reached.** The 10,000-VU run and the 200 ms p99 both need a bigger host: three JVMs
hold ~6 GB of this machine's 7.65 GB Docker allocation and k6 costs ~0.33 MB per VU. At the 2,000-VU
ceiling Redis ran at 10.5k ops/s and 29 % CPU, so the system was not what ran out — the laptop was.
Recorded in §9 as open numbers rather than passing ones.

**Two new instruments, both re-runnable.** `docker/scripts/fanout-check.sh` fails loudly if fan-out
ever regresses; `docker/scripts/sse-cadence.sh` measures the broadcaster from the client side, so
the decision to batch its reads stays gated on a number.

- **Result:** 68 tests green, unchanged — nothing here touches `src/main/java`.

### Pass 6 — Stage 4, the operator surface and the two loose ends

- **Scope:** the `/api/v1/admin/**` surface ADR-043 calls a correctness dependency, plus the two
  items Stage 3 deferred into nothing. Full account in **ADR-048**.

**The two loose ends, first, because they were pointing at a stage that had already shipped.**

| Found | Fix |
| :--- | :--- |
| The outbox marked a row `PROCESSED` on a successful **TCP write**. A broker that accepted the frame and died before persisting lost the message with the row already burned | Publisher confirms — and `mandatory` + returns, because **a confirm is not a routing guarantee**: an exchange with no binding acks and discards, and the whole topology sits behind `flashseats.notification.enabled`. A "just switch confirms on" change would have shipped that hole intact |
| Confirms are asynchronous and per-message, but `OutboxPublisher.publish` took one event | Batch-shaped: `List<UUID> publish(List<OutboxEvent>)`. One timeout per batch instead of one per message, and partial success as a return value rather than an exception dance |
| `RabbitOutboxPublisher` had **no test at all** — the suite runs `transport=log` | A plain JUnit test against a Testcontainers broker, no Spring context. Deliberate: a second context means another set of `@Scheduled` relays and sweepers on the shared containers, which is what killed the hold timer last time |
| §9 said the hold timer had been "built, tested and removed" | `git log -S` across every commit finds no keyspace listener and no `hold:` key literal ever committed. It was an uncommitted tree; nothing was recoverable, and the entry is corrected |
| A keyspace listener that trusts the event would tear up a **grace-extended** hold mid-payment | `reclaimExpired` re-reads the row and settles only what the sweeper would have. A hold found alive is **re-armed**, so grace-extended holds keep the fast path |
| `OrderPostCommitTasks` called `discardTimer` and `revokeAdmission` in one `try` | Fine while `discardTimer` was a no-op; it is a Redis `DEL` now, so an unreachable Redis would have skipped the revoke and left a finished buyer holding an admission that denies someone else theirs |
| The test Redis ran stock `redis:7-alpine`, so `notify-keyspace-events` was empty | `--notify-keyspace-events Ex`. Without it the suite would have gone green over a fast path that did not exist |

**The operator surface.**

| Found | Fix |
| :--- | :--- |
| ADR-038's `reclaimDeadLettered` had **zero callers**. The capability to replay a dead letter was built and could not be triggered | `POST /admin/notifications/resend/{orderNumber}`, served by `order` — the payload lives in `outbox_events`, not `notification_logs`. One new outbox row drives the whole existing pipeline |
| `notification_logs` had **no index on `status`**, so listing dead letters was a seq scan over every notification ever sent | A partial index (`V8`) on `status = 'DLQ'` — the rows an operator looks for, not the millions they never will |
| `findOpenEventIds` had **three** callers wanting two different answers | `findManagedEventIds` for the drift gauge and `StockEpoch`. The `StockEpoch` row is the one an easy implementation drops, and it is the dangerous one |
| 401/403 were the **only** responses in the API with no registry `code` — thrown in the filter chain, where no `@RestControllerAdvice` can reach them | `AdminProblemResponses`, keeping the RFC 7235 `WWW-Authenticate` challenge that replacing the entry point would have dropped |
| The admin password was stored and compared in **plaintext**, and `SecretsGuard` only refused the literal string `admin` | `DelegatingPasswordEncoder`; the guard now refuses **any** `{noop}` value. The old check passed `hunter2` |
| `OrderReceiptResponse` carries `receiptToken` — a 90-day bearer capability | A distinct `AdminOrderResponse`. An operator view has no business minting an impersonation link into terminal history |

**Verified against the three-replica cluster, not just the suite.**

| Drill | Result |
| :--- | :--- |
| Hold expiry across replicas | Restored **exactly once, in 339 ms**; peak never exceeded the starting counter. The sweeper would have taken up to 10,000 ms |
| Redis restart while a sale is **paused** | All three replicas flagged it — the `findManagedEventIds` split doing its job. With `findOpenEventIds` the event would have been invisible until someone resumed it |
| Pause mid-sale | `windowStatus` → `CLOSED`, join → `409`, hold refused, stock untouched; resume restored everything |
| Mail server killed → buy → DLQ → resend | The DLQ named the buyer and the exact exception; the resend delivered the lost ticket |
| Resend **again** | Queued, and **no second email**. `SENT` is not `DLQ`, so the re-claim matches nothing |
| Unauthenticated admin call | `401` with `ADMIN_AUTH_REQUIRED`, a `type` URI, a `traceId` and a challenge |
| Fan-out and the 3-replica promotion check | Still 30/30 across three replicas — no regression |

- **Result:** 87 tests green, up from 68 at the end of Stage 1. The module graph is unchanged; every
  endpoint writes only state its own module owns.

### Pass 7 — the plan-correctness pass

- **Scope:** no production code. A full re-read of the built system against every document that
  claims to describe it, plus one decision that changes the design's target: **the operating envelope
  is 3–10 concurrent sales, not one.**

**The premise.** Six passes had asserted the pre-existing work was correct. This pass tested that by
deriving the real module graph, the real endpoint list, the real Redis key set and the real
transaction counts from the code, and diffing them against the docs.

**The graph survived the audit intact.** The edge list derived from actual `import com.flashseats.*`
statements is acyclic, `notification` and `payment` have zero outbound module edges, `saleflow` is a
true leaf, and no module reaches into another's `service`, `repository` or `model` package —
including in test code. This is the part of the system that was exactly as advertised.

**What the docs claimed and the code did not do.**

| Found | Fix |
| :--- | :--- |
| **The nine module specs referenced 22 classes that never existed** — `BotFacade`, `RecaptchaService`, `StripeGatewayService`, `QueueRedisRepository`, `HoldRedisRepository`, `OrderService` and more. `queue.md` described a *different system*: pass keys unscoped by event, a PostgreSQL advisory lock for the promotion tick, draining the waiting ZSET on exhaustion, a `tier-availability` frame, a configurable ordering property — every one of them contradicted by an ADR that superseded it | All nine rewritten to a fixed **owns / exposes / never** shape, against the code. **No spec lists class names any more**: a class list is what drifts, owned state and exposed contract are what a test can catch |
| `03-end-to-end-flow.md` §1 still described `catalog:stock:{e}:{t}` as **"the one shared key"**, mutated by `hold` through scripts living in `hold` — the design ADR-046 explicitly replaced, and which never shipped | Rewritten to state the rule that actually holds: `catalog` owns the key outright, `hold` moves stock through the facade, and the boundary has no exception |
| §6 listed a configurable queue ordering, a 30 s sweeper and a reCAPTCHA threshold; §7 listed **eight metrics of which two exist** | Tunables corrected against `application.properties`; metrics split into **built** and **specified, not built**, so the gap is legible instead of implied |
| `CLAUDE.md` drew `filter ──► bot`. There is no `filter` module — it is a package inside `bot` | Corrected |

**The decision that changes the target.** ADR-028 derives the promotion batch from the connection
pool and is sound **for one sale**, which it never said. The worker loops every open event and applies
the cap per event, and `queue:promote:{e}` is per event, so replicas promote different sales in the
same second against one shared pool: `R × E × 45` admissions per second into `R × 30` connections.
The second half of the same error is that a checkout costs **eight** sequential transactions, not one.
**ADR-049** adds a global admission budget and re-scopes ADR-028 as the per-sale cap.

**Bugs found by reading, not by failing.** Each is recorded in §9. The write-only `queue:hb` key, the
partial index behind `sumActiveQuantityForTier`, the ticket-retrieval endpoint (**ADR-050**), the dead
facade methods, and the session identity split are already closed; what remains here is the
triple-computed drift gauge and the next concurrent-sales proof.

**One rule added to stop this recurring.** `CLAUDE.md` now carries *"Updating the docs is part of the
change, not follow-up"* — a table mapping what you changed to what you must update, and two standing
rules: **never list class names in a module spec**, and **mark anything aspirational as such**. Doc
drift is what produced most of the defects in passes 1 and 2; it is now a checklist rather than a
habit.

- **Result:** 88 `@Test` methods, untouched — nothing in this pass changes `src/`.

### Pass 8 — concurrent sales, built and measured

- **Scope:** review the `preview-next-stage` merge, fix what it got wrong, and run the ADR-049 drill.
  **108 `@Test` methods, green** — and green with the metadata cache *enabled*, which the merge had
  switched off for the whole suite.

**The merge built the right two things and shipped both with defects.** It landed the catalog cache
(Stage 4c item 1), the global admission budget (item 2), the exhausted-`EXISTS` hoist (item 3), the
per-event emitter index (item 4), plus `RANDOM` ordering (ADR-024) and `tier-availability`
(ADR-027) from Stage 4b. Nine findings, in severity order:

| Found | Fix |
| :--- | :--- |
| **The cache had no TTL.** Eviction reaches only the replica that served the operator's call, so a paused sale answered `OPEN` on the other two **for the life of the process** — and the window status gates queue join, hold creation and checkout. ADR-043 calls the operator surface a correctness dependency; this made pause a cluster-wide no-op | TTL-bounded, and the TTL is *documented as* the cross-replica invalidation (**ADR-051**) |
| **It loaded inside `computeIfAbsent`** — blocking JDBC inside `ConcurrentHashMap`'s per-bin monitor, which **pins carrier threads** on JDK 21. On a cold key at sale open, thousands of virtual threads converge on one bin. A cache added to stop a stall introduced a worse one | `get` → load outside the map → `put`. Invariant 11, reached through a cache rather than a lock library |
| **Pre-warm and the rebuild read it.** Both write inventory counters derived from the tier list: a stale list leaves a tier with no counter (`503` for the rest of the sale, ADR-004) or rebuilds the wrong set (ADR-046) | `tiersUncached` for both. Recovery paths read the authority |
| **Eviction ran inside the transaction**, so a concurrent reader could re-cache the row the commit was about to change | Published as an event, evicted `AFTER_COMMIT` |
| **It cached mutable JPA entities** and handed one instance to every request thread | Immutable `EventRow` / `TierRow` records; the window status stays derived on every call |
| **It was disabled in `application-test.properties`**, so all 102 tests ran with the feature off and the configuration production uses had no coverage at all | Enabled; `SaleFixture.reset()` clears every `DerivedStateCache`, a one-method interface in `shared` — because a fixture importing `catalog.service` is the boundary violation `ApplicationModules.verify()` catches, and it does not care that the caller is a test |
| **The budget starved every sale but one.** 90 ÷ 8 = 11 per tick cluster-wide against a per-event cap of 45, so the cap never binds — and every replica iterates `findOpenEventIds()` ascending, so the lowest event id took the whole allowance every tick. At `E = 5`: one sale draining, four frozen | The open-event order is **shuffled** per tick. ADR-049's "secondary cap prevents starvation" is only true while the cap is smaller than the allowance |
| **The claim was three non-atomic round trips** keyed on each replica's own clock (`epochMillis / interval`), so skew opened two adjacent windows each with a full allowance | One Lua script on `queue:budget`, window anchored by the key's own TTL. No clock agreement required |
| **`RANDOM` ordering scored by `SHA-256(eventId:sessionId)`** — idempotent, and **precomputable**. Session ids cost nothing to mint, so a bot grinds candidates offline until it holds a low draw: the automation advantage ADR-024 exists to remove, restored by the mechanism meant to remove it | A fresh uniform draw; `ZADD NX` already makes a rejoin idempotent by discarding the second one |

Also: a lost-registration race in the new emitter index (the per-event set was removed once empty while
a concurrent connect had already joined that instance, leaving a live connection in a set nothing
iterates), and a per-sweep `WARN` per unreadable counter — a log flood during the incident the message
describes.

**And the documents the merge did not touch.** It added a Redis key, three tunables, a resurrected
tunable, an SSE frame and a public endpoint while changing `00-architecture-decisions.md`,
`03-end-to-end-flow.md`, `FE_SPEC.md` and `CLAUDE.md` not at all. `03` §6 still said queue ordering was
"not configurable" next to a live `flashseats.queue.ordering`; `CLAUDE.md`'s key table — the one rule
that names itself explicitly — had no `queue:budget`. ADR-051 is new; 049, 024 and 027 are amended to
*built*, each with the departures the build produced.

**A correction that matters because a limit rests on it.** A checkout is **nine** sequential
transactions, not eight. Every listing collapsed `PaymentTransactionStore`'s two `REQUIRES_NEW`
transactions — the pair bracketing the gateway call, which its own javadoc describes as two — into "the
payment store", and ADR-049's budget is derived from that count.

**The drill cost two false starts, both worth recording.** `V9`'s comment block had been rewritten in
place after it had already been applied, and Flyway checksums the whole file: every replica refused to
start on `Validate failed: checksum mismatch for version 9` with DDL identical to the character. Then
the drill script sourced `.env` — which expands the bcrypt digest's `$$` to the shell's PID and exports
it over Compose's own value, re-creating ADR-048's corrupted-hash defect from the other side, and every
admin call answered `401`. The repo's own scripts `grep | cut` a single value for exactly this reason.

**And the drill's headline number was wrong, which is the finding this pass nearly missed.** The run
reported "21 tickets sold" and the first write-up accepted it, reaching for the host as the explanation.
Two questions — *"0 or 370 sold, that's not a lot, I thought the point was to sell a lot without
overbooking"* and then *"shouldn't these numbers be much better?"* — are what forced it open. Three
things came out:

1. **The ledger held 109 orders and 162 seats, not 21.** k6 counts responses that *arrive*, times out at
   60 s, and ends with a graceful ramp-down, so 757 in-flight purchases were scored as nothing. This is
   the same class of defect as ADR-047's four: **the harness measured itself and the summary presented
   it as the sale.** `docker/scripts/sold-count.sh` now reads the ledger and checks
   `sold + held + redis == capacity` per tier, and the k6 summary labels its own number as a floor.
2. **The residual latency is scheduling, not the pool** — 25 idle connections with 75 waiters and a 3 s
   timeout firing at 9.8 s — and the claim that `pending` stayed at `0.0` was an artefact of a sampler
   that rotates replicas every ~15 s. It reached 217.
3. **At 300 VUs, with the allowance corrected, five concurrent sales sell out: 2,496 of 2,500, three
   tiers at exactly 500/500, no oversell, no drift, no inventory `503`s, and
   `hikaricp_connections_pending` at zero throughout.** That is run F, and it is what the drill existed
   to establish. Everything below ~10 % in runs A–D was the load generator competing with the system for
   ten cores.

The lesson is not about k6. Three of this pass's nine findings and all four of its wrong turns came from
trusting a summary line over a ledger — and the drill was *built* in Pass 7 specifically because a green
harness had hidden the failure it was written to find.

**Two more things turned up while closing out, and the second is the better find.**

**The hottest path in the system made four Redis round trips where one would do.** `GET /queue/status`
is called ~90,000 times per replica in a 300-VU five-sale run — 80× everything else combined — and each
call read the admission, its TTL, the pass, the exhausted marker and the rank *sequentially*. They were
always independent; only the decision between them is ordered. Now it is one pipelined round trip and a
`decide` over the values, with `CLOSED` answered before the read so a finished sale's polling clients
cost no Redis at all. **No latency claim is attached**: two runs of the identical build measured p99 at
1,880 ms and 1,242 ms, so this host's ±50 % variance swamps it. The round-trip count is the result.

**And verifying that change exposed a latent bug that `QueueLifecycleIT` caught immediately.**
`getRemainingForEvent` asked *"is any counter missing?"* and never *"is there anything to count?"*: with
an empty tier list, `counters.size() < tierIds.size()` is `0 < 0`, so it summed an empty stream and
answered **0**. The promotion worker reads 0 as sold out and sets `queue:exhausted:{e}` — which clears
only when `remaining > 0`, which a tier-less event never reports. **A whole waiting room told the sale
had ended, permanently, on the strength of a sum over nothing.** That is ADR-035's trap surviving inside
the method written to kill it, and it sits on the normal path: every event exists before its tiers do —
every seeder, every fixture, and any future create-event endpoint. `RemainingForEventTest` now pins all
four answers apart, because the entire design rests on their being different.

**One instrument was blaming the wrong component, and only ordering hid it.** `fanout-check.sh` asserted
the sale was `OPEN` but never that it had *seats*. Run straight after a load run that sold out, all 30
sessions correctly received no promotion — admission control has nothing to promote from a drained
counter — and the script reported a pub/sub fan-out failure, pointing at `QueuePubSubConfig` and
`PromotionWorker.issuePass`. It now refuses to run against a `SOLD_OUT` or `UNKNOWN` availability and
says which, so it cannot accuse the mechanism it exists to prove. Re-seeded, it passes: **30/30 promoted,
10/10/10 across three replicas.** Same family as ADR-047's four harness defects — an instrument that
measures the wrong thing is worse than no instrument, because its answer is specific.

**The allowance's formula was the last thing wrong, and it was wrong in its units.** ADR-049 asked for a
budget "derived from the true per-buyer connection cost"; that became 90 connections ÷ 8 transactions =
11, a concurrency divided by a count, spent as a rate. It capped the sale at 76 % while leaving the pool
89 % idle. Replaced by one honest rate at ADR-028's own 45, re-scoped to the cluster — the only scope
change ADR-049 ever needed — and the sale sells out.

- **Result:** **five concurrent sales sell out — 2,496 of 2,500 — with no oversell, no drift and
  `hikaricp_connections_pending` at zero.** Where the same drill measured 202 pending and 370 sold in
  Pass 7, it now measures 0 pending and a **complete sellout — 2,500 of 2,500**. 112 tests green. The one number still open is checkout p99: 10 s
  against a 200 ms criterion, which `pending` at zero says is not the database, and which needs a host
  where the load generator is not sharing ten cores with three JVMs.

---

### Pass 9 — Stage 2: real money behind the seam that was already there, and defence that fails open

- **Scope:** replace the stub gateway, build the webhook receiver, make ADR-012's refund reachable,
  ship 3-D Secure, build §10 S5's compensating control — **and then review all of it** against
  rollbacks, connection loss, repeated attempts and load. **142 tests green** (112 before; `payment`
  and `bot` each had none of their own).
- **Method:** build against the existing seam without changing anything above it, then give the
  module its first tests — and keep every one of them runnable with no Stripe account.

**The seam held.** `PaymentGateway`, `payment:inflight`, the two `REQUIRES_NEW` transactions
bracketing the network call, find-or-create, the attempt ceiling and the compensating refund were all
built for a provider that did not exist yet, and none of them needed changing. What the provider
actually cost was: two fields on `GatewayResult` (`clientSecret`, and a `requiresAction` factory that
*must* carry the intent id), one method on the interface (`retrieve`), one field on `GatewayCharge`
(`holdToken`, which travels as provider metadata and is how the webhook finds its order), and **three
lines in `CheckoutService`**.

| Built | Shape |
| :--- | :--- |
| `StripePaymentGateway` | Server-confirmed PaymentIntents (ADR-052). Payment Element would have inverted ADR-001 and made the synchronous `402`/`409` contract dead code |
| `CircuitBreakingGateway` | A decorator, not an aspect. Counts `GatewayTransportException` and **nothing else** — a decline is a returned value, and a breaker that counted declines opens on a healthy provider during an ordinary burst of expired cards |
| `webhook_events` + the receiver | A claim, not a log (ADR-053). `ON CONFLICT DO NOTHING`, rowcount as the answer, **released when settlement throws** so the redelivery retries |
| `PaymentSettledEvent` → `order` | ADR-005's one cross-module edge, traversed for the first time since it was drawn |
| 3-D Secure | `402` + `clientSecret`, resumed by re-POSTing the same body (ADR-054) |

**Three things this pass got right only because a document was wrong out loud.**

| Found | Resolution |
| :--- | :--- |
| **`03` §5 and `06` §11 both specified `POST /orders/checkout/resume`; `FE_SPEC` §2 said flatly that it does not exist and must not be built.** Three documents, two answers, and the contract one is the one clients are written against | FE_SPEC wins. Both others corrected, and **ADR-054** records why: a second retry path needs its own idempotency story, and this system's whole guarantee is that there is one |
| **`05-global-standards.md` §2 told clients to "follow `resumeUrl`"** — a field that existed in no code, no DTO and no other document | Replaced with the real contract: `clientSecret`, then re-POST |
| **The compensating refund discarded `RefundResult`.** A provider that *refused* the refund still produced an order marked `REFUNDED` and an email telling the buyer their money was coming — money this business holds and should not, described to the only person who would notice as already returned | One `OrderRefundService` for both call sites; a failure is counted on `flashseats.payment.refund.failed` and written into `failure_reason`. **Alarm on any non-zero value** |

**The resume had to retrieve, and finding out why was the real design work.** The obvious
implementation — charge again on the re-POST — fails in two different directions depending on the
idempotency key. Reuse it, as `FE_SPEC` §1 requires, and the provider replays its cached
`requires_action` response **for ever**, so the buyer can never finish. Vary it per attempt and a
*second* intent opens, so they authenticate one payment and are billed for two. Only retrieving the
pending intent works — and `PaymentStatus.PROCESSING`, declared on day one and never written because
the stub could not reach it, turned out to be exactly the marker needed.

**Accepted, and written down rather than discovered later:** while a challenge is outstanding, a
different card in the body is ignored, because the resume re-reads that intent. The bound is the hold,
which expires in minutes, and the alternative is a second charge against a hold that already has one
in flight. A *failed* challenge resolves itself — the provider moves the intent to
`requires_payment_method`, the retrieve returns `DECLINED`, and the next attempt charges fresh.

**The stub was kept, and made the default.** It would have been natural to delete it on the day the
real thing arrived, and that would have taken the only deterministic coverage of decline, outage and
challenge with it — along with the ability to run the load harness, every drill and the whole suite
with no account. Its vocabulary is now the provider's own (`pm_card_authenticationRequired`), so one
script drives either gateway. Two consequences worth stating plainly:

- **The suite proves this system's behaviour, not Stripe's.** `docker/scripts/stripe-check.sh` is the
  only thing that checks the real account, the real status mapping and the real webhook secret agree,
  and it is a script someone has to run rather than a test that fails on its own.
- **The webhook secret is read on every profile**, independently of `stripe.enabled`, because that is
  how the tests sign their own payloads. Gating it on the flag would have left the endpoint untested
  on exactly the configuration the tests run.

**Two traps avoided that the repository had already written down once.** The webhook claim is on its
own bean, because a `@Transactional` method called from the same object runs with no transaction at
all — and a claim that is not committed before the work it guards lets all three replicas settle the
same charge. And the claim is released on failure, which is ADR-038's rule (Pass 6, the DLQ replay)
appearing in a second place for the same reason. The same self-invocation trap was then avoided a
third time in `bot`'s audit writer, where the lambda would have called its own `@Transactional`
method through `this`.

**And the bot half** (ADR-055) — §10 S5's compensating control, deferred four times. reCAPTCHA v3 on
join with a new `queue ──► bot` edge, `ip_rules` as a TTL-bounded snapshot rather than a per-request
query, and `bot_audit_logs` written asynchronously on a queue that discards. Verification **fails
open**: only a score the provider actively returns below the threshold refuses anything. It is `V11`,
not the `V6__bot.sql` four documents asked for, because `V6` has been applied everywhere. And it is
**off by default** — a blank secret means no verification — so the honest claim is that the control
exists, not that it is on.

**The bot half, in the same pass** (ADR-055). §10 S5 has said the same sentence since Pass 1 —
session identity is free to mint, so the primary rate-limit bucket constrains nobody determined — and
the named compensating control had been deferred four times. It is now built: reCAPTCHA v3 on join,
`ip_rules` as the manual override, and `bot_audit_logs` for what was refused. The module wrote no
tables before this and had no operator surface at all, so an address flooding a sale could be
answered only by changing a property and restarting three replicas *during the sale*.

Three decisions carry the weight, and each is the same shape as one this repository already made:

| Decision | The failure it avoids |
| :--- | :--- |
| **Verification fails open.** Only a score the provider actively returns below the threshold refuses anything; an unconfigured secret, a missing token, a timeout and a non-2xx all allow | A challenge provider's outage closing a sale ten thousand people are waiting for — **at peak load**, because that is when the provider is busiest too. Every degraded verification is audited, since failing open is otherwise invisible |
| **`ip_rules` is a TTL-bounded snapshot, never a per-request query** | ADR-051's trap with the pool as the resource: a filter that queries to decide whether to shed load sits *inside* the connection pool it exists to protect, queued behind the buyers it is shielding |
| **Audit writes are asynchronous on a bounded queue that discards** | Every row is written on a path an attacker controls the rate of, so a synchronous insert lets them convert their own `429`s into database load during the sale. Evidence is not worth an outage |

Two smaller things worth recording. The provider timeouts (1 s / 2 s) are **correctness settings**:
a default-timeout client on the join path turns a provider slowdown into a sale-length outage,
reintroducing the exact failure that failing open exists to prevent. And the migration is `V11`, not
the `V6__bot.sql` four documents asked for — `V6` has been `V6__pass1_corrections.sql` in every
database that has run this schema, and Flyway checksums the whole file.

**What S5 actually closes to.** Not "closed". `flashseats.bot.recaptcha.secret` is blank in `dev`,
`test` and a clean checkout, so verification is **off** unless someone sets it — deliberate, because
the stack must run from a clean checkout, and therefore the honest statement is that the control now
*exists* rather than that it is *on*. ADR-044's verified accounts remain the other route to the same
problem.

**The same pass then reviewed its own work**, against the conditions this code will actually meet
rather than the ones a green suite exercises: a rolled-back transaction, a dropped connection, one
actor retrying hard, a sale with many buyers at once. Four defects, and **none of them is an error** —
which is exactly why 129 passing tests could not see them (ADR-056).

| Found | Condition that reveals it | Rule |
| :--- | :--- | :--- |
| The settlement caught `RuntimeException` and refunded, so a pool timeout or an unreadable counter refunded a buyer whose seats were **fine** — then answered the provider `200`, so nothing ever retried and the mistake was permanent | any transient database trouble during a webhook | **Only a definite failure moves money.** The three hold exceptions refund; everything else propagates, releases the claim and earns a redelivery — ADR-046's inventory rule, reaching money |
| `payment:inflight` was released in an **unguarded** `finally`, so Redis dropping after a successful charge discarded the result and marked the order `FAILED` — money moved, order says it did not | Redis blip mid-checkout | A throw from `finally` **replaces** the returned value. Guard cleanup; the key expires anyway |
| The 3-D Secure resume lookup was its own `REQUIRES_NEW` read, so **every** checkout paid a tenth sequential transaction to serve the challenge minority — against the count ADR-049's allowance is derived from | many buyers at once | Merged into the insert as one `beginAttempt`. Back to nine, and one class shorter |
| `ip_rules` retried a down database **per request** and had no single-flight guard, so a TTL boundary was a pool spike and an outage was a connection storm | PostgreSQL unreachable; high request rate | A failed attempt stamps the clock like a success; one reload in flight; an empty result is cached too. All non-blocking — a lock here pins carrier threads |

Plus `requires_confirmation` was mapped to `PAYMENT_ACTION_REQUIRED`, which would have handed the
client a `clientSecret` whose `handleNextAction` does nothing — a `402` loop for the life of the hold;
and the audit trail recorded `getRemoteAddr()`, which behind nginx is nginx, so every row in the only
deployment that matters said `172.28.0.10`. `X-Forwarded-For` is now resolved in exactly one place and
published as `shared`'s `ClientAddress` attribute.

**Measured, on the ADR-049 drill plus a bot dimension the drill had never had** (13 Sept 2026,
three replicas, five sales, 300 VUs):

| | Pass 8 | Pass 9 |
| :--- | :--- | :--- |
| seats sold of 2,500 | 2,496 | **2,497** — `sold + held + redis == 500` on every tier |
| `hikaricp_connections_pending` | 0 | 0 throughout the sale, peaking at 2 (the 6–7 later in the trace is the deliberate database outage below, not sale load) |
| **checkout p99** | **9.3 s** | **6.4 s** |
| inventory `503`s · rate-limited | 0 · 0 | 0 · 0 |
| `flashseats.stock.drift` | transient | **one** `1.0` sample out of sixty, zero on that replica's next read — the documented transient, not sustained (ADR-046) |

The p99 is the number Pass 8 left open, and a third of it went away by *removing* a transaction
rather than adding capacity. It is still far above the 200 ms exit criterion, and still measured on a
ten-core host shared with three JVMs and the load generator.

**And the bot half, measured for the first time.** During the same run: a `DENY` rule added mid-run
reached all three replicas; `bot_audit_logs` held **13 rows against 1,678 orders** — refusals only,
never a row per request; and every row recorded the *forwarded* client address rather than nginx's,
which is the fix above proving itself in the only deployment shape where it matters.

Then PostgreSQL was stopped for twelve seconds under sixty requests. One replica attempted **two**
reloads — one per TTL window. Before the backoff it would have attempted sixty, one per request,
against a database that was already down. That measurement is the whole point of the fix, and it is
invisible in every other instrument, because nothing about it is an error.

**And the instrument contradicted the ADR it cites.** `pool-pressure.sh` failed the whole run on that
single drift sample, printing "this is a correctness failure" — while `sold-count.sh`, which reads the
ledger and is the authority, reported `sold + held + redis == 500` on every one of the five tiers.
ADR-046 says to alarm on *sustained* non-zero precisely because Redis and PostgreSQL are not read in
one snapshot. The script now requires drift on **consecutive samples for the same replica** before
failing, and names an isolated one as the measurement's own artefact. Same family as ADR-047's four
harness defects: an instrument that measures the wrong thing is worse than no instrument, because its
answer is specific.

**A note on the test that could not be written the obvious way.** Forcing an ambiguous settlement
failure with `@MockitoSpyBean` **broke nineteen unrelated tests**: a bean override forks the
application context, so two applications ran their schedulers against the same containers while the
fixture truncated underneath both. The natural trigger turned out to be sitting in the schema —
`ticket_holds` has no foreign key to `ticket_tiers`, so removing a tier row leaves a live hold the
catalog cannot describe. Worth recording, because the next person to reach for a bean override in
this suite will hit the same wall.

- **Result:** **142 tests green.** `payment`'s first suite (12): webhook signature, replay,
  settlement, ADR-012's refund, the 3-D Secure round trip asserting **one** charge and **zero**
  attempts consumed, and a breaker unit test asserting a hundred declines leave it closed. `bot`'s
  first suite (5): join succeeds with no provider configured, a denied address is refused with
  `IP_BLOCKED`, removing a rule takes effect with no restart, an expired rule stops applying at *its*
  expiry rather than the cache's, and only refusals reach the audit trail. Then the review's own
  thirteen: the ambiguous-failure branch and its redelivery, provider status mapping including the
  `requires_confirmation` loop, the snapshot's backoff and single flight, and the resolved audit
  address. Still open: rate-limit metrics, and checkout p99.
---

### Pass 10 — the simplification pass

- **Scope:** the whole codebase and every document, read for *legibility* rather than correctness.
  The trigger was not a defect: eight passes of adding correctness had left 215 Java files holding
  ~8,360 lines of real code — 91 of them 25 lines or fewer, 33 % of every file a comment — with six
  classes on the path from `checkout` to `charge` and five representations of an event. The
  guarantees were sound; nobody could find them. `REFACTORING_BLUEPRINT.md` is the pass's main
  artefact and is now the repo's entry point.

- **Written in parallel with Pass 9 and merged after it**, which turned out to be the most useful
  thing about it — see "what the merge taught" below. Numbered 10 and carrying **ADR-057** because
  Pass 9 had taken 052–056 while this was in flight.

- **The finding that shaped it:** most of what looks removable here is load-bearing, and recording
  *that* is worth more than the deletions. SSE looks like a duplicate of `/queue/status` polling and
  is the cheap path — polling is the measured CPU hog at ~90,000 calls per replica per run against
  ~1,100 checkouts. `saleflow` is a 205-line module for one endpoint and is the only host for a
  four-facade read that keeps the graph acyclic. `LoggingOutboxPublisher` looks like a toy and is
  what lets the whole suite test the three-transaction relay with no broker. Checkout step 0 looks
  redundant with step 4 and must precede step 1. All of it is in the blueprint's §1.5 so the next
  pass does not re-propose it.

**Built (112 tests green after every stage, `ModularityTests` included):**

| Change | Effect |
| :--- | :--- |
| **Five `*FacadeImpl` deleted; services implement their own facades** (ADR-057) | One hop shorter on every cross-module call. `CatalogFacadeImpl` was twelve one-line delegations and said so in its own javadoc |
| **25 exception classes → 10 + four `<Module>Errors`** (ADR-057) | A module's whole failure surface is one readable file. 16 of the 25 were never caught by type; every deleted class's javadoc moved verbatim onto its factory |
| **Six unreachable `ErrorCode` constants removed** | §12 item 7, in the direction nobody had checked. Ten went; four came back — see below |
| **`V12__drop_unread_schema.sql`** | `outbox_events.last_error` (written by nothing, read by nothing) and **three indexes no query uses**, two of them on `ticket_holds` and therefore maintained on every reserve |
| **`NotificationStatus.FAILED` removed** | Declared, never assigned — and dangerous: `DLQ` is re-claimable by design, so a second terminal-looking state a replay does not recognise is how a buyer gets two tickets or none (ADR-042) |
| **Checkout lost one transaction** | `confirm` returned the `Order`, the caller discarded it and re-read the same row plus its items to build the receipt. It now returns the receipt built from what it already holds. **Not yet measured** — the `connections_pending` before/after needs the concurrent-sales drill |
| **Three dangling references** | `redis.conf` naming `hold_reserve.lua`, a javadoc naming `AdminResendController`, and an nginx block routing to an endpoint that did not exist — the "class names that never existed" failure mode, alive in three places |
| **Four pom dependencies removed** | `springdoc` (zero annotations behind it — `/docs` described every shape and no meaning), `devtools` (fights the static Testcontainers), `mail-test` and `security-test` (nothing uses either; re-verified after the merge) |

**What the merge with Pass 9 taught, and it is the most transferable thing here.** This pass deleted
ten `ErrorCode` constants, three enum values, seven schema objects and three env vars on one rule:
*nothing references it.* Pass 9 landed a real Stripe gateway, a webhook receiver, 3-D Secure and an
IP-rule surface in the same week — and **roughly half of those deletions had to be reverted on the
merge**:

| Deleted as unreachable | What Pass 9 did |
| :--- | :--- |
| `PAYMENT_ACTION_REQUIRED`, `WEBHOOK_SIGNATURE_INVALID`, `BOT_VERIFICATION_FAILED`, `IP_BLOCKED` | All four raised by real code paths |
| `PaymentStatus.PROCESSING` | Became the 3-D Secure parking state — a resumed checkout finds the parked intent by it rather than opening a second one |
| `GatewayResult.Outcome.REQUIRES_ACTION`, and three `PaymentResult` fields | All load-bearing for 3-D Secure; `PaymentResult` gained a fourth, `clientSecret` |
| `idx_pay_hold` | Now the index behind the 3-D Secure resume lookup. Dropping it would have put a sequential scan on the authentication path |
| `HoldNotFoundException`, made a factory | Caught **by type** by the webhook settlement path, with `HoldExpiredException` and `HoldAlreadySettledException`, to refund a settlement whose seats are gone. Restored as a class — the same rule, new evidence |
| `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `RECAPTCHA_SECRET` | All three read by real configuration; `SecretsGuard` now refuses to boot without the webhook secret |

**The rule was not wrong — its time horizon was.** "Nothing references this" is a sound reason to
delete *code*, which is cheap to restore from git and whose absence the compiler enforces. It is a
much weaker reason to delete *contract and schema*, where the cost of being early is a revert, a
renumbered migration and a merge conflict in someone else's branch. **Delete a thing when its feature
is not being built — not merely when it is not built yet**, and check the tip of the other branches
before deciding which of those you are looking at. Six of the ten codes did stay deleted, so the
check is still worth running; it is the confidence that needed calibrating, not the question.

Two smaller lessons from the same merge, both now in `CLAUDE.md`: a migration version is
first-come, so `V10` had to become `V12` when Pass 9 took `V10` and `V11`; and an ADR number is the
same, so `ADR-052` became `ADR-057`. Two branches appending to one log is exactly how a duplicate
happens.

**One deletion re-examined and kept.** The dedicated nginx `location` for the webhook stayed
deleted even though the endpoint now exists. It only set `proxy_next_upstream off`, which is a
weaker version of a guarantee the application already makes: a webhook delivery is a claim, and a
claim is released when its work did not happen (ADR-053), so a delivery replayed against a second
replica finds the claim taken and acks. The honest argument for the block is cost, not correctness,
and one duplicate delivery per slow payment is not a cost.

**Not done, and deliberately listed rather than quietly dropped:** the representation merges
(blueprint Stage D — queue state still has four shapes, order line items four) and the absorption of
the single-method wrappers (Stage E — `OrderNumbers`, `HoldKeys`, `SaleWindows`, the three
one-method repositories). Stage E's `payment` collapse is **withdrawn**: that module was 22 files
around one switch statement when the blueprint was written and is now a real gateway with a breaker,
a webhook and 3-D Secure. `idx_pay_order` and `idx_orders_intent` are still queried by nothing and
were left alone for the same reason — shaving writes off a table someone is actively extending is
not worth the coordination cost.

**The gap this pass found and did not close.** `application-test.properties` sets
`flashseats.notification.enabled=false`, which `@ConditionalOnProperty`-disables the Rabbit topology,
both consumers and `EmailDispatcher`. **The entire 1,020-line `notification` module therefore has no
test that drives a message through a listener** — no ack/nack, no DLX routing, no
claim → render → send → mark, no `EmailComposer` (133 lines, zero tests), and no end-to-end refund
path. This is the trap `CLAUDE.md` names as *"disabling a feature in the test profile so the suite
passes"*, and its own prescribed fix applies: give the fixture a seam.
`RabbitOutboxPublisherTest` already shows the pattern with its own Testcontainers broker, and Pass 9's
`PaymentWebhookIT` shows it again. It is item 3.2 of the blueprint's checklist.

**Also noticed, not fixed:** `README.md` §"Architecture at a glance" is materially stale — it
describes `tier_inventory`, dropped in `V7`, and a `filter ──► bot` module edge that does not exist.
It predates ADR-046 and was not in this pass's scope.

### Pass 11 — reconciling the frontend merge

- **Scope:** PR #16 (`shoham-phase4-imp`), 7,943 files, merged to `preview` with **no document change
  at all**. The pass read the merge, wrote down what it actually did, fixed what it broke, and took
  the dependencies back out of git. No new capability was added.

- **The finding that shaped it, and it is not a defect.** The merge contains four pieces of real
  work — Redis Sentinel, SSE reconnect replay, most of the "specified, not built" metric set, and a
  React SPA implementing `FE_SPEC` — and **none of it was discoverable.** `04` still said Stripe was
  unbuilt, `06` §11 still said Sentinel was deferred, and `03` §7 still listed six live metrics as
  aspirational. In a repo whose first rule is that a stale spec is fixed rather than the code, that is
  not untidiness: it is six standing orders to rebuild what exists. The rule in `CLAUDE.md` is now
  demonstrably load-bearing rather than stylistic, and **ADR-058 is what should have been in the
  merge.**

**Built:**

| Change | Effect |
| :--- | :--- |
| **One SSE id space** (ADR-058) | Only replayable frames carry an `id`; everything else is sent with none, which the SSE spec defines as leaving the client's last-event-id alone. As merged, position frames stamped a `"local-N"` over it every two seconds and the replay parsed ids as numbers — **so the feature returned nothing on the normal path** |
| **The replay log holds broadcasts only** (ADR-058) | The promotion frame carries a `passToken`, single-use with a 120 s TTL, and was being written to a per-event ZSET that outlives the sale. A promoted buyer's reconnect now re-reads the live pass and rebuilds the frame — which also works with no `Last-Event-ID`, in a fresh tab |
| **`QueueReplayIT`** | The feature shipped with no test; both defects above are the kind a first test catches. Three now: the log holds no capability, broadcasts replay in order from a sequence, and an unretained frame does not burn one |
| **The client mirrors the payment seam** (ADR-058) | `stripe.ts` threw at module load without `VITE_STRIPE_PUBLISHABLE_KEY`, so a clean checkout was a white screen — against a backend whose default is the stub. No key now means stub mode with the stub's magic tokens in the UI. `frontend/.env.example` added |
| **One idempotency key per hold** | The merged client minted a fresh UUID per attempt, which opens a *second* PaymentIntent — the failure ADR-054 exists to prevent. `getIdempotencyKey` already existed, was imported, and was unused. `FE_SPEC` §3 has always specified once-per-hold |
| **`useCheckoutSubmit`** | The POST, the 3-D Secure re-post and the `problem.code` table are one hook; the Stripe and stub paths differ only in how a payment method is obtained. Written to add the second path without duplicating 120 lines of error handling |
| **7,858 tracked frontend files → 47** | `node_modules` (57 MB), `dist`, four `tsc` outputs, and five scratch files including a Windows classpath dump. The `.gitignore` rules for them were added *in the same merge that tracked them*, so they were inert |

**Deliberately not done:**

- **The history rewrite.** The pack is 24 MB; `filter-repo` costs every collaborator a re-clone and
  every open branch a rebase. Revisit if it becomes a problem.
- **Wiring the SPA into nginx and compose.** It touches `nginx.conf`, which is correctness rather
  than tuning, and it wants its own pass and its own browser-level verification.
- **The ten failing tests.** See §9. Reproduced unmodified at the merge commit, so it is not this
  pass's regression, and two hypotheses were tested and disproved rather than guessed at. Everything
  this pass touched was verified by targeted run: `QueueLifecycleIT`, `QueueBroadcasterTest`,
  `UserJourneyIT`, `ModularityTests`, `SaleflowRehydrationIT`, both hold ITs and the new
  `QueueReplayIT` — 29 tests, green — plus the frontend's 18 vitest tests and a production build.

**The transferable lesson, and it is the opposite of Pass 10's.** Pass 10 learned that deleting on
"nothing references this" has a time horizon. This pass learned the same thing about *writing*: a
merge that adds four capabilities and no documentation is indistinguishable, a week later, from a
merge that added nothing — and the repo's own instructions then actively mislead. The cost is not
paid by the author, who knows what they built; it is paid by whoever reads `04` next and starts
building Stripe again.

### Pass 12 — establishing the base

- **Scope:** make the suite's verdict trustworthy, read PR #16's backend against the ADRs it must
  honour, and verify the cluster on the Sentinel topology it now ships. Fix-as-you-go: every finding
  got a test and its doc update in the same commit.

- **The finding that reframes the merge.** Pass 11 fixed what reading the *code* exposed. This pass
  ran it, and that is a different instrument: **the Sentinel cluster had never started, on any
  machine.** All three shell scripts PR #16 added were committed mode 644, and one of them is a
  container `entrypoint`, so the sentinels restart-looped on `permission denied` and every app
  replica depends on them. Nothing said so, because the topology is only reachable through
  `--profile cluster` and no test goes there. **The drill's own instrument was in the same state** —
  `declare -A` on a bash macOS does not ship, and a hardcoded `docker.exe` that would have reported
  a clean PASS having measured nothing. Code review would not have found either; running it did.

**Built:**

| Change | Effect |
| :--- | :--- |
| **Surefire `runOrder` pinned** | The default is filesystem order, so two machines could disagree about whether the suite passed. 213/213 twice from cold containers. **Pinned, not fixed** — the order dependence in §9 stays open, and the pom says so |
| **The fulfilment-lag gauge stopped scanning its own table** | `MIN(created_at) WHERE status <> PROCESSED` matched neither partial index, so it sequentially scanned `outbox_events` — processed rows included — every 10 s per replica. Asked one status at a time it is two bounded reads. No new index: that table is written once per checkout |
| **`payment.decline.ratio` → `payment.attempts{outcome}`** | A ratio cumulative since process start is the one shape that cannot show a spike, which `03` §7 says is why it exists. Counters leave the windowing to the query and delete state rather than adding it |
| **Three script modes, and an entrypoint that no longer depends on one** | See above. `CLAUDE.md` carries the rule and the one-line audit |
| **`pool-pressure.sh` runs, and measures** | bash 3.2 compatible, and `docker.exe` detected rather than assumed |
| **The `UPCOMING` dev event restored** | `prewarm` refuses any other window, so with every dev event `OPEN` the seeding path ADR-004 protects could not be exercised on `dev` at all |
| **Two payment comments corrected** | Both claimed guarantees they do not hold. The refund short-circuit does not cover the crash case its comment described — the gateway's idempotency key does — and `charge` had lost the reason it constrains payment methods |

**Verified on the cluster, for the first time through Sentinel:**

- `fanout-check.sh` — 30/30 promoted, 10/10/10 across three replicas.
- `hold-expiry-check.sh` — restored exactly once, 669 ms, across three replicas.
- `sentinel-failover-check.sh` — replica promoted, old primary rejoined as a replica.
- **The half that script deliberately skips, done by hand and now the highest-value claim in the
  system that is no longer merely argued.** After a failover all three replicas independently refused
  to sell event 9001 — the vouched `run_id` no longer matched the promoted primary's — a buyer's hold
  answered **`503 INVENTORY_UNAVAILABLE`, retryable**, and not "sold out"; `rebuild-stock` on **one**
  replica resumed selling on **all three**. That is ADR-046's derived-never-consumed design observed
  end to end against a failover rather than a restart.
- Run **H** of the concurrent-sales drill; see §11.

**Worth carrying forward.** Only the replica that serves `rebuild-stock` logs "vouched for again";
the other two release silently on their next tick. An operator reading logs sees the refusal three
times and the recovery once. Correct by design and mildly unhelpful during an incident.

**The transferable lesson.** Pass 11's was that undocumented work is invisible. This pass's is
narrower and sharper: **a path no test and no script exercises is not "probably fine", it is
unknown.** Every defect here lived in exactly such a path — a profile the suite never starts, a shell
the author never ran — and each was found in the first minute of trying to use it.
