# MVP Overview

> **The reference for what exists, what does not, and why.** Read this before changing code; read
> [`00-architecture-decisions.md`](00-architecture-decisions.md) before changing a decision.
>
> A **Review passes** log at the bottom records every pass over this MVP. Append to it; do not
> rewrite history.

**Status:** built and running, two review passes, one cleanup pass and **Stage 1** deep. 68 tests
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
| `shared` | `ErrorCode` (42 codes), `ProblemDetails`, one global advice, `SessionId`, `Money`, `Clock`, `SignedToken`, `TraceIdFilter` | — |
| `bot` | Signed `fsid` cookie; Redis-backed Bucket4j session + IP buckets; SSE exempt from per-request accounting | reCAPTCHA, `ip_rules`, audit logs. **Writes no tables.** |
| `catalog` | Events, tiers, window derivation, `serverTime`, bucketed availability, **Redis counters + Lua, the `-2` fault path, pre-warm, the Redis-restart guard** | pause, `TierAvailabilityChangedEvent` |
| `queue` | `ZADD NX` join, SSE with heartbeats, HMAC passes, admission sessions, promotion worker, **pub/sub fan-out**, measured drain-rate estimates | `RANDOM` ordering, `tier-availability` frame, `Last-Event-ID` replay |
| `hold` | `ticket_holds` authority, the settle-once claim, atomic reserve **with compensation**, **after-commit restore**, bounded grace, sweeper, all three endpoints | the `hold:{token}` Redis timer and the keyspace listener (Stage 3) |
| `payment` | Real `PaymentFacade`, `payment_transactions`, three idempotency layers, stub gateway behind the final interface | Stripe, webhooks, 3-D Secure, Resilience4j |
| `order` | Full orchestration, find-or-create, server-side pricing, receipt tokens, outbox relay with `SKIP LOCKED`, compensating refund, **the stock rebuild and the drift gauge** | `PaymentSettledEvent` listener, `/checkout/resume` |
| `notification` | Rabbit topology + DLX, insert-then-send consumer, PDFBox tickets, HTML email, Mailpit | Failure classification, DLQ inspection, admin resend, refund-notice template |
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
./mvnw test        # 68 tests: unit, modularity, concurrency, journey, recovery, queue lifecycle,
                   #            pre-warm, stock rebuild, drift, Redis-restart guard
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
| `CatalogAvailabilityIT` | A tier with no counter reads `UNKNOWN`, a drained tier still reads `SOLD_OUT`, and the two are never the same answer (ADR-040). |
| `ProblemResponseIT` | Spring's own binding failures are `400` with a registry `code`, not `500` (ADR-041). |
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
- **The 10,000-VU run has not happened**, and the limit is the host, not the system. Three JVMs take
  ~6 GB of the 7.65 GB this machine gives Docker, and k6 costs ~0.33 MB per VU. 2,000 VUs is the
  ceiling here; at that load Redis sat at 10.5k ops/s and 29 % CPU, so the system was not the thing
  running out. Needs a host with ~32 GB.
- **Checkout p99 is 682 ms at 300 VUs and 4.7 s at 2,000**, against a 200 ms exit criterion. Also a
  host artefact — the load generator and three JVMs were competing for ten cores — but it is
  unmeasured on real hardware, so it stays an open number rather than a passing one.
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
- **Payment is a stub.** Every idempotency layer is real; the gateway is not.
- ~~**No admin surface** beyond pre-warm.~~ **Built** (Stage 4, ADR-048): pause/resume, the DLQ
  listing, a ticket resend, and an operator order view. `rebuild-stock` shipped in Stage 1. Still
  a single in-memory operator account — now stored bcrypt-hashed rather than in plaintext, with a
  real identity provider deferred until a second operator exists (§10 S12).
- **`ORDER_REFUNDED` is written but never consumed** — the refund-notice template is deferred.

- ~~**The outbox relay publishes without confirms.**~~ **Fixed** (Stage 4, ADR-048). A row is marked
  `PROCESSED` only once the broker acknowledges it — and, just as importantly, only once it was
  *routed*: a confirm means "the broker has this", not "a queue has this", so `mandatory` plus
  publisher-returns is what stops every ticket being confirmed into an exchange bound to nothing.
  `OutboxPublisher` is batch-shaped, so an unhealthy broker costs one timeout per batch rather than
  one per message. Unconfirmed rows stay `PROCESSING` and the stale-claim sweep retries them.
- **`QueueBroadcaster` does 4 sequential Redis round trips per connection per 2 s tick** — the
  admission `GET`, the pass `GET`, the exhausted `EXISTS` and the waiting `ZRANK`. The cost is linear
  in **connections × open events**, not in connections alone: the sweep loops watched events and
  re-runs the per-session reads inside each. Past some product of the two the sweep cannot finish
  inside its interval.
  **Measured in Stage 3 on ONE sale, and it was not that point yet.** At 2,000 VUs the median gap
  between `position-update` frames was **2,016 ms against a configured 2,000 ms** — keeping up, with
  Redis at 29 % CPU. **That evidence does not generalise to the `E = 3..10` envelope** adopted in
  ADR-049, and re-measuring at `E = 5` is what decides whether the fix stays deferred.
  `docker/scripts/sse-cadence.sh` is the instrument. Two of the four round trips are removable
  cheaply: the exhausted `EXISTS` is per *event* and is currently re-read per *session*, and the
  remaining reads batch into one pipelined round trip per event per tick.
- **The emitter registry is a flat map keyed by session id.** "Sessions watching event X" streams the
  whole map and allocates a `Set`, so a sweep costs `O(connections × events)` traversals before it
  makes a single Redis call. A per-event index removes it.
- **`notification.order-refunded.queue` has no consumer**, so it grows without bound on a durable
  broker. The refund-notice template is Stage 4.
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

**Found in Pass 7 (the plan-correctness pass), all open:**

- **Admission is budgeted per sale against a shared pool.** The promotion worker loops every open
  event and applies `promotion-batch-size` per event; the tick lock is per event too. At the newly
  adopted `E = 3..10` envelope the cluster admits up to `R × E × 45` per second into `R × 30`
  connections. ADR-049 is the fix and is **specified, not built**. This is the largest open risk in
  the system.
- **A checkout costs eight sequential database transactions**, and a full buyer session about
  fifteen — not the ~1 that ADR-028's "capacity to serve" model implicitly prices. Both limits were
  therefore generous even at `E = 1`.
- **Nothing is cached.** `events` changes only on operator pause/resume and `ticket_tiers` never
  changes after creation, yet every window check, event summary, tier summary and tier-id lookup is a
  PostgreSQL transaction — on the landing page, the queue-status poll and the rehydration endpoint.
  Highest-leverage single change for the multi-sale envelope.
- **`queue:hb:{sid}` is written by every join and every status poll and read by nobody.** The
  "abandonment metric" its Javadoc names was never built. It is also unscoped by event, and its
  expiries flood the shared `__keyevent@0__:expired` channel that the hold listener filters on every
  replica. ADR-046's *"a table written by nobody's reader"* trap, in Redis.
- **`sumActiveQuantityForTier` has no supporting index.** `idx_holds_event_tier` needs a leading
  `event_id`; `idx_holds_sweeper` leads on `expires_at`. The drift gauge runs this per tier, per
  event, **per replica**, every 60 s, over a table that accumulates every hold ever created — so the
  cost grows with sale history and never falls.
- **The drift gauge is computed three times to produce one global answer.** Read-only and therefore
  "safe on every replica", but all three replicas compute the same number.
- **There is no way to retrieve a ticket.** The PDF exists only as an email attachment; a typo'd
  address is unrecoverable, and the operator resend replays the same payload to the same wrong
  address. ADR-050, **specified, not built**.
- **Dead surface:** `OrderFacade.getOrderSummary` has zero callers anywhere; `HoldFacade.releaseHold`,
  `HoldReleaseReason` and the service method behind them are reached only from a test.
- **Session identity spans `bot` and `shared`**, coupled by a request-attribute string constant, with
  the signing secret under `flashseats.bot.*`. `bot` is abuse defence; identity is not.
- **The operator surface is curl-only.** ADR-043 calls it a correctness dependency; one that can only
  be driven by hand-written Basic-auth curl during an incident is half-built.

---

## 10. Security posture

**This MVP is not production-ready, and the gaps are deliberate rather than overlooked.** Everything
below is a real exposure someone should close before real money moves through it.

### Closed in Pass 1

| # | Was | Now |
| :-- | :--- | :--- |
| S1 | **Default secrets** — one leaked string forged an `fsid`, a queue pass, an admission **and** a receipt token, and `receipt-secret` defaulted to the *session* secret's env var so the two were the same value | Three separate secrets (`FLASHSEATS_SESSION_SECRET`, `FLASHSEATS_QUEUE_PASS_SECRET`, `FLASHSEATS_RECEIPT_SECRET`), every token domain-separated by a length-prefixed `kind`, and `SecretsGuard` **refuses to start** on any profile but `dev`/`test` while a default is in place (ADR-039) |
| S2 | **Default admin credentials** `admin`/`admin` | Same guard covers the admin password. Still an in-memory user — a real identity provider remains the right answer, and is still deferred |
| S3 | **`Secure` cookie defaults to false** | Now `${FLASHSEATS_COOKIE_SECURE:false}`, so it is set per environment rather than edited in a properties file. The default stays `false` because a `Secure` cookie is silently dropped over `http://localhost` and would break every local session |
| S4 | **Receipt tokens never expire** and were `sign(orderNumber)` — deterministic, so derivable by counting against sequential order numbers | Payload is `orderNumber:expiry:nonce`, mirroring `QueueTokens`. Default lifetime 90 days (`flashseats.order.receipt-token-ttl-days`) |
| S11 | **`X-Forwarded-For` trusted from any client** — anyone could rotate a fake address for unlimited fresh IP buckets, or poison a real one. With the session bucket already free to mint, this left *no* effective rate limit for a cookie-less caller | Honoured only from a peer in `flashseats.bot.trusted-proxies`, **empty by default** (ADR-039) |

### Must fix before any deployment

| # | Exposure | Detail and fix |
| :-- | :--- | :--- |
| S12 | **Admin auth is an in-memory user** | HTTP Basic against one hardcoded account guards pre-warm and the metrics endpoints. The password is no longer a published default, but this is not an identity system. Replace the `UserDetailsService` bean before anyone else needs access. |

### Structural weaknesses to weigh

| # | Weakness | Assessment |
| :-- | :--- | :--- |
| S5 | **Session identity is free to mint** | The rate limiter's primary bucket is per-`fsid`, and anyone can discard a cookie to get a fresh one. The IP bucket is therefore the only real backstop — and it is deliberately loose (300 burst) so NAT populations are not blocked. This is the ADR-011 trade working as designed, but it means **the session bucket does not constrain a determined attacker at all.** Pass 1 made the IP bucket real (S11); it is now genuinely the backstop ADR-011 assumed it was. reCAPTCHA on join is still the missing compensating control, and it is still deferred. |
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

- **Redis Sentinel**, deferred in ADR-047. No exit criterion needs failover, and it works against the
  Redis-restart criterion, which is cleanest against a standalone instance that simply stops.
- **The 10,000-VU run and the p99 number.** Both need a host where the load generator is not
  competing with the system under test; see §9.
- ~~The `hold:{token}` timer and the `__keyevent@0__:expired` listener.~~ **Built in Stage 4**, and
  proven on the rig Stage 3 left behind: restored exactly once, in 339 ms, across three replicas.
- The rest of the metric set and its alarms: `outbox.lag.seconds`, `dlq.depth`,
  `queue.promotion.rate`, `payment.decline.ratio`, `jvm.threads.pinned`. `stock.drift` and
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

- The refund-notice template, so `ORDER_REFUNDED` reaches the buyer — and a consumer for
  `notification.order-refunded.queue`, which currently has none and grows without bound.
- Notification failure classification (ADR-029): transient failures earn the retry chain; the
  deterministic ones already skip it.
- `tier-availability` frames in the waiting room (ADR-027); `RANDOM` queue ordering (ADR-024).
- The React SPA against `FE_SPEC.md`, if the demo client is outgrown.
- **The Playwright suite specified in `FE_SPEC.md` §8.** Every one of the four client rules is a
  browser behaviour — a skewed clock, a real reload, a live `EventSource` — so none of them is
  reachable from the API suite, and the twelve reload points are checked by hand today. Two of the
  defects Pass 1 fixed were reload-path defects. The spec is written; the implementation is not.

### Stage 4c — Correctness cleanup and concurrent sales (Pass 7 findings)

**The gate:** Pass 7 was a plan-correctness pass and changed no code. These are its findings, in
dependency order. Everything in the first two groups is cheap; the third is the real work.

**Delete what nothing uses** (no behaviour change):

- `queue:hb:{sid}` and the `touchHeartbeat` call on the hottest polling path — a write-only key.
- `OrderFacade.getOrderSummary` (zero callers) and `HoldFacade.releaseHold` + `HoldReleaseReason` +
  the service method behind them (test-only).
- The `ORDER_REFUNDED` decision: build the Stage 4b consumer, or stop writing the rows. Carrying it a
  fifth time is not an option.

**Fix the bugs:**

- `V9`: `CREATE INDEX idx_holds_active_tier ON ticket_holds (tier_id) WHERE status = 'ACTIVE'`.
- `GET /orders/{orderNumber}/ticket.pdf`, with `TicketPdfRenderer` moved to `shared` (**ADR-050**).
- Move the `fsid` filter from `bot` to `shared/identity`; rename to `flashseats.session.*`.
  `ApplicationModules.verify()` is the check.

**Then concurrent sales** (**ADR-049**), in leverage order, each measurable on its own:

1. Cache `events` + `ticket_tiers` behind `CatalogService`, evicted on pause/resume. The single
   highest-leverage change, and the smallest.
2. The global admission budget, with the per-event batch as a secondary cap.
3. Hoist the exhausted `EXISTS` out of the per-session loop; pipeline the rest of the sweep.
4. A per-event index in the emitter registry.
5. Make the drift gauge a singleton under the promotion tick's Redis-lock pattern.

**The drill — built in Pass 7, and NOT YET RUN.** Every other instrument here runs one event, and so
did every measurement the capacity numbers rest on.

```bash
docker/seed/seed-concurrent.sh                 # 9001..9005, all opening at once
docker/scripts/pool-pressure.sh 300 &          # THE instrument
docker compose --profile loadtest run --rm -e VUS=2000 k6-concurrent
```

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

**Bugs found by reading, not by failing.** Each is recorded in §9 and none is fixed yet: the
write-only `queue:hb` key, the missing partial index behind `sumActiveQuantityForTier`, the absent
ticket-retrieval endpoint (**ADR-050**), the triple-computed drift gauge, two dead facade methods, and
session identity split across `bot` and `shared`.

**One rule added to stop this recurring.** `CLAUDE.md` now carries *"Updating the docs is part of the
change, not follow-up"* — a table mapping what you changed to what you must update, and two standing
rules: **never list class names in a module spec**, and **mark anything aspirational as such**. Doc
drift is what produced most of the defects in passes 1 and 2; it is now a checklist rather than a
habit.

- **Result:** 88 `@Test` methods, untouched — nothing in this pass changes `src/`.
