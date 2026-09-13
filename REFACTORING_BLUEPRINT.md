# Refactoring Blueprint

> **Start here if you are new to FlashSeats.** Part 1 is the whole system on one page. Parts 2 and 3
> are the work to make the code match it.

FlashSeats is eight review passes deep. The passes worked — every defect they found is fixed, and the
numbers are real (five concurrent sales, 2,496 of 2,500 sold, no oversell). What eight passes of
*adding* correctness produced is not a logic problem but a **legibility** one: 215 Java files for
~8,360 lines of real code, six classes traversed on one cross-module call, five representations of an
event, and nine documents with no "start here".

This blueprint fixes that. **It does not weaken a single invariant.**

| | Today | Target |
| :--- | ---: | ---: |
| Java files | 215 | ~130 |
| Files ≤ 25 lines | 91 | ~25 |
| Classes traversed on `checkout → charge` | 6 | 4 |
| Representations of one domain object | 5 | 2 |
| Files to read for a module's failure surface | up to 12 | 1–5 |
| Entry-point document | none | this one |

---

# Part 1 — Simplified System Flow

## 1.1 The whole journey

Nine steps, all real HTTP. The demo client at `/` is one consumer of it.

| # | Call | Lands in | Gated by |
| :-- | :--- | :--- | :--- |
| 1 | `GET /api/v1/events/{id}` | `EventController` → `CatalogService` | mints the signed `fsid` cookie |
| 2 | `POST /api/v1/queue/join` | `QueueController` → `BotFacade`, then `QueueService` | the challenge check, which **fails open** (ADR-055); sale window `OPEN`; `ZADD NX` so a refresh keeps your place |
| 3 | `GET /api/v1/queue/stream` | `QueueController` → `SseEmitterRegistry` | SSE. `GET /queue/status` is the polling equivalent |
| 4 | *(worker, 1 s)* | `PromotionWorker` | cluster-wide `queue:budget`, then the per-event batch |
| 5 | `POST /api/v1/queue/admit` | `QueueService.admit` | pass is single-use — revoked here |
| 6 | `POST /api/v1/holds` | `HoldController` → `HoldService` | `X-Admission-Token`; reserve in Redis, then insert the row |
| 7 | `POST /api/v1/orders/checkout` | `CheckoutService` → `OrderCommitService` | the nine-step sequence in §1.3 |
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

**Why nine modules for 8,400 lines** — the question everyone asks once:

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
3  window gate                        → OPEN, or CLOSED within 15 min
4  find-or-create on UNIQUE(hold_token)
5  grantGrace()                       → once per hold. FAILS ⇒ abort 410, DO NOT CHARGE
6  authorize()                        → OUTSIDE every transaction
6b 3-D Secure?                        → 402 + clientSecret; order left FAILED, no attempt consumed.
                                        The client authenticates and re-POSTs THIS SAME body —
                                        find-or-create resumes it (ADR-054). No resume endpoint.
7  @Transactional                     → consumeHold · CONFIRMED · order_items · outbox_events
8  AFTER_COMMIT                       → discardTimer, revokeAdmission — best-effort, safe to lose
9  commit failed after a charge?      → refund · REFUNDED · ORDER_REFUNDED outbox row
```

**Step 0 has to be first.** A successful purchase consumes its hold, so validating the hold first
would answer a resubmission with "your reservation expired" when the buyer already owns the seats.

**Step 7 is one transaction containing only SQL.** `consumeHold` is a conditional `UPDATE` that joins
it, so if anything fails the hold returns to `ACTIVE` and expires normally.

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
| **this file** | first, always |
| `docs/03-end-to-end-flow.md` | the authoritative journey and the 3–10 concurrent-sale operating envelope |
| `docs/00-architecture-decisions.md` | before changing a decision. 51 ADRs; most record a defect and its fix |
| `docs/05-global-standards.md` | the cross-cutting contract — error registry, transaction rules, facade rules |
| `FE_SPEC.md` | the client contract |
| `docs/modules/*.md` | one page per module: owns / exposes / never |
| `docs/06-mvp-overview.md` | what is actually built, and the open limitations |

**Precedence when two documents disagree:** ADRs win, then `05`, then `FE_SPEC`, then `03`, then
`01`/`02`, then module pages. A module spec contradicting an ADR is stale — fix the spec, not the code.

---

# Part 2 — Refactoring Steps

Seven stages, ordered by legibility bought. Each is independently committable and must leave
`./mvnw test` green. **Docs ship in the same commit as the code** — a stale spec in this repo is a
standing order to build the wrong thing.

## Stage A — this document

Done by the file you are reading. Link it from `README.md` as the starting point.

## Stage B — collapse the cross-module hop

**5 files, ~240 lines. The biggest tracing win.**

Every `*FacadeImpl` is pure delegation. `CatalogFacadeImpl` is twelve one-line methods and documents
itself as *"deliberately no logic here"*.

```
before — 3 types, 2 hops            after — 2 types, 1 hop
CatalogFacade      interface        CatalogFacade   interface, @NamedInterface package — unchanged
CatalogFacadeImpl  delegation       CatalogService  implements CatalogFacade
CatalogService     logic
```

**Why this is safe under Modulith.** `ApplicationModules.verify()` checks *source-code type
references*, not runtime bean types. Call sites name `CatalogFacade`, which lives in a
`@NamedInterface` package; `CatalogService` stays in the internal `service` package where no other
module may name it. Spring resolves by type. `ModularityTests` fails the build if this is wrong — it
is the proof, not an argument.

Delete: `CatalogFacadeImpl`, `HoldFacadeImpl`, `OrderFacadeImpl`, `QueueFacadeImpl`,
`PaymentFacadeImpl`.

Three details to carry:

- `HoldFacadeImpl.toSummary` and `OrderFacadeImpl.toSummary` map entity→record. Move each onto its
  record as `HoldSummary.of(TicketHold)`. That also kills the **second, independent** mapper off the
  same entity at `hold/dto/HoldResponse.java:26`.
- `HoldFacadeImpl.discardTimer` reaches around `HoldService` straight into `HoldTimers`. Give
  `HoldService` the method so the facade surface matches the service surface.
- `QueueFacadeImpl.verifyAdmission` holds the only logic in any Impl — a null guard. Move it.

**Also update:** each `docs/modules/*.md` "exposes" section.

## Stage C — centralize each module's failure surface

**25 exception files → 8 classes + 7 `<Module>Errors`.**

Today a module's failures are one class per failure, up to twelve files. Seventeen of the
twenty-five are never caught distinctly, and twelve have a single throw site. You cannot see what a
module can refuse without opening a directory.

Replace them with **one `<Module>Errors` class per module**, in the same `@NamedInterface` exception
package so the boundary is unchanged:

```java
public final class CatalogErrors {

    private CatalogErrors() {}

    /** No such event. */
    public static FlashSeatsException eventNotFound(long eventId) {
        return new FlashSeatsException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + eventId + ".");
    }

    /**
     * Pre-warm refuses an open sale. Reseeding a live counter resurrects every sold ticket
     * (ADR-004), which is the worst failure this system has.
     */
    public static FlashSeatsException prewarmWindowClosed(long eventId) { … }
}
```

Keep as classes **only** the eight that carry logic or are caught by type:

| Keep | Why |
| :--- | :--- |
| `DuplicatePaymentException` | **caught by type** at `CheckoutService.java:148` |
| `PaymentDeclinedException` | picks between two `ErrorCode`s on `attemptsRemaining` |
| `TicketNotAvailableException` | branches message *and* `retryable` on `OrderStatus` |
| `HoldExpiredException` | carries `expiresAt` |
| `HoldAlreadySettledException`, `OrderRefundedException` | participate in control flow |
| `InsufficientStockException`, `InventoryUnavailableException` | the pair ADR-004 exists to keep apart — "pick another tier" is not "we cannot see our own inventory" |

`FlashSeatsException`'s constructors become public. **The wire format is byte-identical**, so the
`ErrorCode` registry, `ProblemResponseIT` and `FE_SPEC.md` are untouched.

**Move every deleted class's javadoc onto its factory verbatim.** Those paragraphs are the record of
why a distinction exists. This is a re-shelving, not a deletion.

## Stage D — one shape per concept

| Concept | Now | After |
| :--- | :--- | :--- |
| Queue state | **four**: `QueueState` (facade), `QueueStatusResponse` (dto), `SaleStateResponse.QueueSection` (*field-for-field identical to `QueueState`*), and an ad-hoc `LinkedHashMap` at `SseEmitterRegistry.java:112-115` | `QueueState` + one response that adds `aheadOfYou` and `serverTime` |
| Order line item | **four**: `OrderItem`, `OrderItemResponse` (*identical five fields*), `OutboxPayload.Item`, `TicketDocument.Seat` — hand-mapped in three places | one projection + the two wire records that genuinely differ |
| Order summary | `OrderSummary` is a strict subset of `AdminOrderResponse` with `status` stringified | one mapper feeding both |
| Availability | `TierAvailability(long, **String**)` against `TierResponse.availability` as an **enum** — `CatalogService.java:290` calls `.name()` to downgrade it | one encoding; `AvailabilityLevel` moves to the facade package |

**Add one test** pinning `OutboxPayload` and `OrderConfirmedPayload` structurally equal. They are
deliberately separate (§1.5) and nothing catches a silent divergence today. The failure mode is
undeliverable tickets.

## Stage E — absorb the files that are not concepts

Each is a file you must open to discover it does almost nothing.

| Absorb | Into | Size |
| :--- | :--- | ---: |
| `OrderNumbers` | `OrderCommitService` | 30 lines for `"TK-" + "%05d".formatted(...)` |
| `HoldKeys`, `HoldTokens` | `HoldService` / `HoldTimers` | 60 lines, two static utilities in one package |
| `AvailabilityBuckets`, `SaleWindows` | `CatalogService` | 61 lines; one and three call sites, both already inside it |
| `HoldReconciliationSweeper` | move `@Scheduled` onto `HoldService.sweepExpired` | 36 lines, 30 of them javadoc, wrapping one call |
| `PaymentTransactionRepository`, `OrderItemRepository`, `TicketTierRepository` | their single callers | one method each |
| The authorisation block at `OrderQueryService.java:88-98` ≡ `:167-177` | `requireAuthorised(orderNumber, sessionId, receiptToken)` | six lines, duplicated; the javadoc at `:149-151` already admits it |

**`payment` is WITHDRAWN from this stage.** The blueprint originally called it "22 files and ~700
lines around one switch statement" and proposed collapsing `GatewayCharge`/`GatewayResult` into
`AuthorizeCommand`/`PaymentResult`. That description was accurate when written and is now false:
the module has a real Stripe gateway, a circuit-breaking decorator, a webhook receiver, 3-D Secure
and a settlement path (ADR-052–056). The two record pairs no longer differ by name alone —
`GatewayResult` carries `clientSecret` and a `requiresAction` the facade record deliberately
reshapes. **Do not collapse them.** Stage B already removed this module's `*FacadeImpl`, which was
the part that was genuinely free.

One item from the original proposal survives and is worth doing: `RefundResult` is constructed and
**discarded** at `CheckoutService`'s compensation path, so a failed refund — money we hold and
should not — is a log line and nothing more. Act on it.

## Stage F — delete what nothing reads

All verified by grep against `src/main` **and** `src/test`.

> **This stage ran, and the payment work landing in parallel reverted about half of it.** What
> follows is what actually holds; the reverted items and the lesson are in `06` §13 Pass 10 and
> ADR-057. Short version: *"nothing references this"* is a sound reason to delete **code**, and a
> much weaker one to delete **contract and schema**.

**Java**

- `OrderQueryService.findByOrderNumber` — public, `@Transactional`, **zero callers**. Gone.
- **Six unreachable `ErrorCode` constants**: `NOT_IN_QUEUE`, `QUEUE_PASS_EXPIRED`,
  `QUEUE_UNAVAILABLE`, `SALE_EXHAUSTED`, `ORDER_ALREADY_CONFIRMED`, `NOTIFICATION_LOG_NOT_FOUND`.
  **Removing one changes the client contract** — `05` §2 and `FE_SPEC.md` §2 move in the same commit.
  Four more were removed and came straight back when the gateway, the webhook and the IP-rule
  surface landed.
- `NotificationStatus.FAILED` — declared, never assigned, and dangerous: `DLQ` is re-claimable by
  design, so a second terminal-looking state a replay does not recognise is how a buyer gets two
  tickets or none (ADR-042). `PaymentStatus.PROCESSING` and `GatewayResult.Outcome.REQUIRES_ACTION`
  went the same way and both came back — they are 3-D Secure's parking state and its outcome.
- `DuplicatePaymentException`'s unused parameter, and `QueueService.queueScore`'s two unused
  parameters — leftovers from the precomputable-draw design ADR-024 itself rejected.

**Schema — one new migration, `V12`.** Earlier migrations are immutable: Flyway checksums the whole
file, and editing `V9`'s *comments* once made every container refuse to start. It was written as
`V10` and renumbered when the payment work took `V10` and `V11` — a version is first-come.

- `outbox_events.last_error` — a column nothing writes and nothing reads.
- **Three indexes no query uses**: `idx_holds_session`, `idx_holds_event_tier` (both maintained on
  *every* hold insert, i.e. every reserve) and `idx_orders_email`.
- **`idx_pay_hold` is NOT dropped** — it now backs the 3-D Secure resume lookup. The first draft
  dropped it on the reasoning that the webhook did not exist; the webhook arrived that week, and
  dropping it would have put a sequential scan on the authentication path.
- **`idx_pay_order` and `idx_orders_intent` are still queried by nothing and are left alone anyway.**
  Shaving writes off a table someone is actively extending, on a path that is not hot, is not worth
  the coordination cost.
- **Keep** `ticket_holds.settled_at` and `settle_reason`. Unread by code, but they are the incident
  forensics for a settle-once claim.

**Infra — four dangling references.** Each is the "class names that never existed" failure mode this
repo has a rule about.

- `nginx.conf` routed `/api/v1/payments/webhook` to an endpoint that did not exist. The endpoint
  exists now and the block still stays deleted: it only set `proxy_next_upstream off`, a weaker
  version of the guarantee ADR-053 already makes — a replayed delivery finds the claim taken and
  acks. The generic `/api/` block routes it.
- ~~`compose.yaml` passes `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `RECAPTCHA_SECRET` — none
  reaches Java.~~ **Reverted.** All three are read now, and `SecretsGuard` refuses to boot the
  `docker` profile without the webhook secret.
- `redis.conf:71` names `hold_reserve.lua`; the scripts are `stock_reserve`, `stock_restore`,
  `promotion_budget`.
- `AdminNotificationController.java:24` javadoc names `AdminResendController`, which does not exist.

**`pom.xml` — four dependencies**

- `springdoc-openapi-starter-webmvc-ui` — **zero** `@Operation`/`@Tag`/`@Schema`/`OpenAPI` beans. It
  serves auto-derived docs nobody annotates while `FE_SPEC.md` is the real contract. `/docs` is
  advertised in `CLAUDE.md` and `README.md`; both move with it.
- `spring-boot-devtools` — unused, and its restart classloader fights the static Testcontainers in
  `IntegrationTest`.
- `spring-boot-starter-mail-test` — notification is disabled in every test.
- `spring-security-test` — no `@WithMockUser` anywhere; `OperatorSurfaceIT` uses real basic auth.

**One namespace, one owner.** `/api/v1/admin/events` is claimed by `AdminCatalogController` (catalog)
*and* `AdminStockController` (order); `AdminOrderController` (`/api/v1/admin` +
`/notifications/resend/…`) overlaps `AdminNotificationController` (`/api/v1/admin/notifications`).
Spring tolerates it because sub-paths differ; it is still two modules in one namespace, and it is the
first thing that confuses a reader of the admin surface. Re-base to `/admin/events/…`,
`/admin/stock/…`, `/admin/notifications/…`. **Amends ADR-043 and ADR-048**; `FE_SPEC.md` §2 moves too.

**One predicate, one place.** `EventRepository.findManagedEventIds` duplicates in SQL the
`isInsideWindow` predicate at `CatalogService.java:193-195`, and its own javadoc asks future editors
to keep the two in sync **by hand**. It has one caller. Serve it from `CatalogService`.

**`QueueOrdering.RANDOM`** — `FIFO` is the shipped default; `RANDOM` is reached by one test and never
enabled. Deletes an enum, a branch, a property and a test. **New ADR superseding ADR-024.**

## Stage G — the one core change

**Remove one redundant transaction per successful checkout.**

`OrderCommitService.confirm` returns the fully-populated `Order` (`:192`). `CheckoutService.java:142`
**discards it**, then re-reads the same order and its items through `queries.receiptFor(…)` at `:162`.
Build the receipt from what `confirm` already holds.

- **The invariant is untouched.** Step 7 stays one transaction containing only SQL, the charge still
  precedes the consume, and nothing crosses the commit boundary.
- **Pinned by** `UserJourneyIT` (asserts the receipt body, and the same order number on replay) and
  `CheckoutRecoveryIT`.
- **Worth doing** because the measured bottleneck is the connection pool —
  `hikaricp_connections_pending` peaked at **202 against a pool of 30** in the five-sale run — and a
  checkout costs eight to nine sequential transactions. This removes one from the happy path.

Everything else in that count is structural and stays: steps 0 and 1 cannot merge (§1.5), and
`PaymentTransactionStore`'s two `REQUIRES_NEW` transactions are what keep the gateway call outside a
transaction.

## Verification — every stage

```bash
./mvnw test                                   # 112 green; ModularityTests is the boundary proof
docker compose up -d && ./mvnw spring-boot:run
docker/seed/seed.sh                           # then walk the nine-step journey by hand
docker compose --profile cluster up -d --build
docker/scripts/fanout-check.sh                # 30/30 across >= 2 upstreams — proves SSE survived
docker/scripts/hold-expiry-check.sh           # settle-once still restores exactly once
```

Stages F and G additionally, because admission and checkout are touched. **This drill takes about
30 minutes and looks hung while it runs.**

```bash
docker/seed/seed-concurrent.sh
docker/scripts/pool-pressure.sh 300 &
docker compose --profile loadtest run --rm -e VUS=300 k6-concurrent
docker/scripts/sold-count.sh                  # the ledger is the authority, not k6's summary
```

Stage G wants a before/after on `hikaricp_connections_pending` from `pool-pressure.sh`, since
removing a transaction from checkout is the claim being made.

---

# Part 3 — Remaining Tasks

## 3.1 This refactor

- [x] **Stage A** — this document; linked from `README.md`
- [x] **Stage B** — five `*FacadeImpl` deleted; services implement their facades (**ADR-057**)
- [x] **Stage C** — 25 exception files → 9 classes + four `<Module>Errors`; javadoc moved verbatim
- [ ] **Stage D** — one shape per concept; add the `OutboxPayload` ≡ `OrderConfirmedPayload` test
- [ ] **Stage E** — absorb the non-concept files, and act on the discarded `RefundResult`. The
      `payment` collapse is **withdrawn**: that module is a real gateway now, not 22 files around a
      switch statement
- [x] **Stage F** — dead surface, migration `V12`, four pom dependencies, three dangling infra
      references. **About half of the deletions were reverted** when the payment work landed in
      parallel; `06` §13 Pass 10 has the full accounting and the lesson
- [ ] **Stage F (remainder)** — admin namespaces, `QueueOrdering.RANDOM` + the ADR superseding 024,
      and `EventRepository.findManagedEventIds`' duplicated predicate. These three change behaviour
      or a URL, so they were held back from the behaviour-preserving stages
- [x] **Stage G1** — the redundant receipt re-read is gone. **Not yet measured**: the
      `connections_pending` before/after still needs the concurrent-sales drill
- [x] **Merge review** — `getActiveHold` lost its transaction to self-invocation when its
      `*FacadeImpl` collapsed into the service. Restored, and it was the only instance
- [x] Updated `05` §2 and §5, `FE_SPEC.md` §2, `CLAUDE.md`, `README.md`, and `06` §6 and §13

## 3.2 The coverage gap this review found

- [ ] **The entire 1,020-line `notification` module has no test that drives a message through a
      listener.** `application-test.properties:17` sets `flashseats.notification.enabled=false`, which
      `@ConditionalOnProperty`-disables the Rabbit topology, both consumers and `EmailDispatcher`. So
      nothing exercises: the broker consume, `basicAck`/`basicNack`, DLX routing, the
      claim → render → send → mark sequence, `recordFailure`'s delivered/not-delivered branch, or MIME
      assembly. `EmailComposer` is 133 lines with **zero** tests, including the refund body that must
      not dereference `event()` — the exact defect that would dead-letter every refund notice. The
      end-to-end refund path (`compensate` → `markRefunded` → `ORDER_REFUNDED` → `OrderRefundedConsumer`)
      is untested at every hop.

      This is the repo's own documented trap — *"disabling a feature in the test profile so the suite
      passes"* — and its own prescribed fix: **give the fixture a seam rather than switching the
      feature off.** `RabbitOutboxPublisherTest` already shows the pattern with its own Testcontainers
      RabbitMQ.
- [ ] `PaymentService` and `PaymentTransactionStore` have no direct test — no inflight-guard TTL, no
      release, no `refund`.
- [ ] `OutboxRelay.recoverStaleClaims` and `purge` have no test.

## 3.3 Documentation consolidation

- [ ] Move superseded ADRs (003, 006, 028) to `docs/archive/` behind forward-pointing stubs. They are
      kept for the record and cost every reader.
- [ ] Split `06-mvp-overview.md` §13 — the ~480-line review-pass log — into `docs/07-review-log.md`.
      It is history, not instruction, and it sits inside the file everyone is told to read first.
- [ ] Fold `01-system-architecture.md` and `02-high-level-design.md` (470 lines together) into
      `03-end-to-end-flow.md`, which already owns the authoritative journey.

## 3.4 Carried forward from `06-mvp-overview.md` §9

- [ ] **Checkout p99 is the open number, and it is now stale in our favour.** 682 ms at 300 VUs on
      one sale; **6.4 s at 300 VUs across five**, against a 200 ms exit criterion. That 6.4 s was
      measured on nine sequential transactions per checkout. **Checkout now costs eight** — Pass 9
      merged the 3-D Secure resume read into `beginAttempt` (10 → 9) and Pass 10's Stage G1 removed
      the receipt re-read (9 → 8), and **the second one has never been measured.** Re-running the
      drill is the cheapest real number available right now. It still needs a host where k6 is not
      competing with the three JVMs for cores.
- [ ] **A pool timeout surfaces to a buyer as `500 INTERNAL_ERROR` mid-checkout.** For what is really
      back-pressure, `503` with a `Retry-After` is the honest answer.
- [ ] **The Playwright suite specified in `FE_SPEC.md` §8.** All four client rules are browser
      behaviours — a skewed clock, a real reload, a live `EventSource` — so none is reachable from the
      API suite. Twelve reload points are checked by hand today.
- [ ] **Notification failure classification** (ADR-029): transient failures earn the retry chain; the
      deterministic ones already skip it.
- [ ] **The operator surface is curl-only.** ADR-043 calls it a correctness dependency; one that can
      only be driven by hand-written Basic-auth curl during an incident is half-built.
- [x] ~~**Payment is a stub.**~~ **Built** (Pass 9, ADR-052–056): a real `StripePaymentGateway`
      behind the existing seam, a circuit-breaking decorator, the webhook receiver, 3-D Secure and
      the settlement path. `STRIPE_ENABLED` defaults to **false**, so the stub is still what every
      drill and the load harness run against — deliberately, since they need no account.
- [ ] **The real provider path has only ever been proved by hand.** `PaymentWebhookIT` and
      `ThreeDSecureIT` both run against `StubPaymentGateway`: correct about *this* system's
      behaviour, silent about whether Stripe agrees. `docker/scripts/stripe-check.sh` is the proof
      and needs a `sk_test_...` key plus the `stripe` CLI container. **Nobody has run it since the
      simplification merge.**
- [ ] **`idx_pay_order` and `idx_orders_intent` are indexes no query uses.** `V12` deliberately left
      them: `payment_transactions` was under active construction and shaving writes off a table
      someone is extending is not worth the coordination cost. Revisit once that module settles.
      (`idx_pay_hold` is now genuinely used — it backs the 3-D Secure resume lookup. Do not drop it.)
