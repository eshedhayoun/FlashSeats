# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

FlashSeats — a high-concurrency ticket flash-sale engine. Modular monolith, Java 21, Spring Boot
4.1.1. The **MVP is built and running**: all nine modules, the full journey from landing page to emailed
PDF ticket, 228 tests green in any class order. **Inventory lives in Redis** (Stage 1, ADR-046): `catalog:stock:{e}:{t}`
is the live count and PostgreSQL keeps no copy of it. **Payment is real** (Stage 2, ADR-052-054) —
but `flashseats.payment.stripe.enabled` is **false by default**, so `dev`, `test`, the load harness
and every drill still run the in-process stub through the complete journey, 3-D Secure included.

**Read [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) before changing
anything.** It contains 62 ADRs. Most record a defect and its fix — 034-039 come from the first
review pass over the built code, 040-042 from the second — and several look like over-engineering
until you read the failure they prevent. 043-045 are the exception: forward-looking decisions about
the operator surface, buyer accounts and what health should report, with nothing built against them
yet. **046 is Stage 1** — Redis as the counter, and the five places it departs from the module specs.
**049 and 051 are the concurrent-sales work**, both built in Pass 8: the cluster-wide admission
allowance and the metadata cache that had to come before it. **050 is ticket retrieval**, built. **052-055 are Stage 2**, all built: Stripe behind the
existing seam with a circuit breaker, the webhook as a released-on-failure claim, 3-D Secure with no
resume endpoint, and bot defence that fails open. **056 is Stage 2's own review** — the four defects
that only appear under a rollback, a dropped connection or real load. **059-062 are Pass 13**: a
pool timeout answers `503 SERVICE_BUSY` rather than `500`, `/session/reset` accepts JSON only,
cached test contexts are never paused (the cause of the order-dependent suite), and each replica
has a memory limit with the JVM flags owned by the image alone.

**The operating envelope is 3–10 concurrent sales**, not one
([`03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) §2). Every capacity number written before
ADR-049 silently assumed a single sale. Check which assumption a limit rests on before trusting it.
**Ten concurrent sales sell out** — 4,997 of 5,000 at 300 VUs, no oversell, and
`hikaricp_connections_pending` at zero on every sample up to 2,000 VUs (`06-mvp-overview.md` §11,
Pass 13). Checkout p99 meets its 200 ms criterion up to about 600 VUs across five sales on the dev
laptop, and it collapses to 6 s at 2,000. **The limit there is host CPU, not the pool**: k6 shares
the ten cores with the system it measures.

**For what is actually built**, read [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) — scope,
security posture, next stages, and the review-pass log. It is the doc to update after every pass.

## Document precedence

```
00-architecture-decisions.md      ← highest authority (62 ADRs)
05-global-standards.md            ← cross-cutting contract; module docs conform to it
FE_SPEC.md                        ← client contract (repo root)
03-end-to-end-flow.md             ← the authoritative user journey AND the operating envelope
01 / 02 (architecture, HLD)
docs/modules/*.md                 ← lowest; one page per module: owns / exposes / never
```

**ADR-019 supersedes ADR-003**, **ADR-020 amends ADR-006**, **ADR-049 amends ADR-028** — the
originals are kept for the record but do not describe the current design.

When a module spec contradicts an ADR, the ADR wins and the module spec is stale — fix the module
spec rather than the code.

## Updating the docs is part of the change, not follow-up

**Every code change updates the documents it touches, in the same commit.** This is not tidiness. In
this repo the docs are *instructions* — the line above says a stale spec is to be fixed rather than
the code, so a spec describing an unbuilt design is a standing order to build the wrong thing. Doc
drift is how most of the defects in passes 1 and 2 were created.

A pass over the specs in Sept 2026 found **22 class names that never existed** and whole sections
describing superseded designs. That is the failure mode this rule exists to stop.

**Before you finish a change, check each of these and update what the change touched:**

| You changed | Update |
| :--- | :--- |
| Anything with a rationale worth keeping | a **new ADR** — amend, never silently rewrite an old one |
| A module's owned tables, Redis prefixes, facade or endpoints | that module's [`docs/modules/*.md`](docs/modules/) |
| The buyer's journey, a timer, or a tunable | [`03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) §2 and §6 |
| An error code | the §2 registry in [`05-global-standards.md`](docs/05-global-standards.md) |
| A Redis key | the key table in **this file**, and the owning module's spec |
| A metric or an alarm | `03` §7 — and say whether it is **built** or **specified** |
| An API request/response shape | [`FE_SPEC.md`](FE_SPEC.md) §2 |
| Anything at all, at the end of a pass | [`06-mvp-overview.md`](docs/06-mvp-overview.md) §9, §11, §13 |

**Two rules that keep the specs honest:**

1. **Never list class names in a module spec.** A class list is what drifts; it goes stale the first
   time something is renamed and nothing fails. Specs describe **owned state, exposed contract, and
   prohibitions** — all three are things a test or `ApplicationModules.verify()` can catch.
2. **Mark anything aspirational as such, explicitly.** A doc that describes a plan in the present
   tense is indistinguishable from one describing the build. Write "specified, not built".

## Facts that are easy to get wrong

- **Spring Boot 4.1.1**, not 3.x. Spring Modulith **2.1.1**. Boot 4 moved things:
  JSON is **Jackson 3** (`tools.jackson.databind.ObjectMapper` is the autoconfigured bean — the
  Jackson 2 class is on the classpath with no bean behind it), `@EntityScan` is now
  `org.springframework.boot.persistence.autoconfigure.EntityScan`, and Flyway needs
  `spring-boot-starter-flyway` — `flyway-core` alone runs no migrations.
- Base package is **`com.flashseats`**, and `FlashseatsApplication` sits at that root, so scanning
  needs no widening. App-wide configuration (security, `SecretsGuard`, MVC) is in
  `com.flashseats.app`, a leaf module nothing may depend on. Before Pass 14 the app class was in
  `com.flashseats.flashseats`. Older docs said `com.app.*`; that namespace does not exist.
- Redis is a **single primary + Sentinel**, not Cluster (ADR-018). The `CROSSSLOT` argument that
  originally motivated this is moot — `stock_reserve.lua` now touches one key — but keyspace
  notifications are still per-node and a handful of keys is nowhere near a single primary's ceiling.
  **Sentinel is now actually built** in the `cluster` profile — one primary, two replicas, three
  sentinels, discovered through `application-docker.properties` (ADR-058, superseding ADR-047's
  deferral). `dev`, `test` and the plain `docker compose up -d` stack stay standalone. **A failover
  stops every sale**: the promoted replica is a new process with a new `run_id`, so `StockEpoch`
  distrusts every managed event until an operator rebuilds it. That is ADR-046 working, not a bug.
  **Failover state survives `down`**: it lives in the sentinel volumes. After
  `sentinel-failover-check.sh`, the next `up` can leave every app replica connected to a node that
  Sentinel then demotes, and every request answers a bare `500` with `READONLY` in the logs.
  `docker compose --profile cluster restart app-1 app-2 app-3` fixes it (`06` §9, not yet fixed in
  code).
- The **transactional outbox is hand-rolled** in `order`. The Spring Modulith event-publication
  starters were deliberately removed; only `spring-modulith-starter-core` and `-starter-test`
  remain, purely for `ApplicationModules.verify()` (ADR-009). Do not re-add them casually.
- `spring.threads.virtual.enabled=true` is load-bearing, not decoration.
- **Resilience4j is the plain artifacts, never the starter** — `resilience4j-spring-boot3` targets
  Boot 3. **Two** `CircuitBreaker` beans are declared by hand: `payment`'s gateway config (ADR-052)
  and `bot`'s reCAPTCHA config (ADR-058).
- **Stripe is on the classpath unconditionally but used conditionally.** Signature verification
  (`com.stripe.net.Webhook`) is needed on every profile, because that is how the tests sign their own
  payloads; the *gateway* is chosen by `flashseats.payment.stripe.enabled`.
- **The webhook secret is minted per `stripe listen` session**, not per account. A stale one rejects
  every delivery and the symptom is silence that looks exactly like a quiet day.
- **Redisson is gone** (ADR-022). Distributed locks are `pg_try_advisory_xact_lock`.
- There are **nine** modules: seven domain + `shared` (open) + `saleflow` (read-only leaf). `app`
  (security and startup guards) is a tenth *package* Modulith sees, and it has no inbound edges.
- **`SecretsGuard` refuses to start** outside `dev`/`test` while any secret is still
  `dev-only-change-me` — including `docker compose --profile cluster`, which runs the `docker`
  profile. Generate them per `.env.example` (ADR-039).
- Every signed token declares a **`kind`**, length-prefixed into the signed bytes. `SignedToken.sign`
  and `.verify` both take it; a token of one kind never verifies as another.
- Session identity is **`flashseats.session.*` in `shared`**, not `flashseats.bot.*`. The env var
  `FLASHSEATS_SESSION_SECRET` is unchanged. `bot` is rate limiting only.

## Module boundaries — enforced, not advisory

```
                    shared        ← open module; everyone may depend on it

bot      ──► shared only          ← servlet filters; `filter` is a PACKAGE in `bot`, not a module
queue    ──► catalog, bot         ← `bot` only on join (reCAPTCHA); acyclic, `bot` needs nothing
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog     ← read-only leaf; nothing depends on it
payment  ──( PaymentSettledEvent · webhook path only )──► order   ← BUILT; the only inbound edge
order    ──( outbox → RabbitMQ )──► notification
```

Rules:

- Cross-module calls go through a `*Facade` interface. Never touch another module's `service`,
  `repository`, or `model` package. Full facade rules: `05-global-standards.md` §5 — synchronous,
  never `@Transactional` itself, records not entities, module-owned exceptions only.
- A module reads only its own tables and its own Redis key prefixes.
- **There is no exception.** `catalog:stock:{eventId}:{tierId}` is owned and mutated by `catalog`
  alone; `hold` moves stock through `CatalogFacade`. Earlier drafts had `hold` run the scripts
  against catalog's key and called it the one shared key in the system (ADR-046).
- `payment` calls **no** facades. Adding one would make the graph cyclic (ADR-005). It reports
  settlement as an **event** for exactly that reason: the type dependency runs `order → payment`,
  which already exists, so the runtime direction never closes the loop.
- `order` owns no Redis keys at all.
- The graph must stay acyclic — `ApplicationModules.verify()` fails the build otherwise.

## Invariants — never weaken these

1. `confirmed_sold + active_holds + remaining == total_capacity`, always. This is the
   `flashseats.stock.drift` gauge, live since ADR-046; alarm on *sustained* non-zero, since Redis
   and PostgreSQL are not read in one snapshot.
2. Stock is restored **exactly once** per hold, via one conditional `UPDATE` on `ticket_holds`
   `WHERE status='ACTIVE'`, and the `INCRBY` happens **after that claim commits** (ADR-046).
   PostgreSQL is the authority for the lifecycle; Redis holds only the count. There is no
   `holdmeta` key and no `GETDEL` (ADR-019).
3. `UNIQUE(hold_token)` on `orders` — one hold can never become two orders.
4. **Charge before consuming the hold.** There is no `CONSUMED → RELEASED` transition (ADR-001).
5. A missing stock counter is a **fault** (`-2` → `503` → alarm → rebuild), never a cache miss.
   Never reseed from `total_capacity` while a sale is open (ADR-004). `ReserveResult` keeps
   `INSUFFICIENT` and `COUNTER_MISSING` apart inside one atomic step.
6. Charge amounts are computed server-side from `CatalogFacade`. No client input reaches them.
7. Session identity comes from the signed `fsid` cookie only — never a body field, query param, or
   custom header (ADR-010).
8. Outbox polling uses `FOR UPDATE SKIP LOCKED`; notification dedupe is
   `UNIQUE(order_number, kind)` with insert-then-send. A `SELECT`-based check is a race.
9. **A SQL transaction may contain only SQL** (ADR-023). No HTTP, SMTP, broker, Redis write, PDF
   rendering, or sleep inside `@Transactional`. Side effects go in `AFTER_COMMIT`; the outbox relay
   is three short transactions. This is why the reserve sits *before* the hold's transaction and the
   restore *after* it (ADR-046).
10. All errors are RFC 7807 `ProblemDetail` with a `code` from the `05-global-standards.md` §2
    registry. No `ApiResponse<T>` envelope.
11. `synchronized` **pins virtual threads** on JDK 21. Use `ReentrantLock`. Distributed locks are
    `pg_try_advisory_xact_lock` — Redisson was removed for exactly this reason (ADR-022).
12. **Every Redis write fails toward under-counting** (ADR-046). Invisible seats are lost revenue a
    rebuild recovers; phantom seats are an oversell nothing recovers. Where both orderings look
    defensible, take the one that under-counts.

## Traps this design already stepped in once

Do not reintroduce these — each cost a real defect in the first pass:

| Trap | Why it breaks |
| :--- | :--- |
| Repopulating stock from `total_capacity` on a cache miss | Resurrects every sold ticket after any Redis eviction |
| Restoring stock in a keyspace-expiry listener without a claim | Expiry is pub/sub — every replica restores, so stock triples |
| Holding `SseEmitter`s without Redis Pub/Sub fan-out | Works on 1 replica, drops ~⅔ of promotions on 3 |
| Plain `ZADD` for queue joins | Updates the score; a refresh sends the user to the back of the line |
| Unbounded `extendHold` | Free permanent seat-squatting |
| Releasing the hold on a card decline | The UX says retry; releasing contradicts it |
| Tight per-IP rate limits | Blocks entire NAT populations during the spike |
| Consuming the hold before charging | Requires a state transition that does not exist |
| `SELECT`-then-send for email idempotency | Two workers both pass the check |
| **Mutating Redis inside a SQL transaction** | Redis cannot roll back — a failed commit leaks inventory permanently |
| Publishing to RabbitMQ inside the outbox transaction | Holds row locks across a broker round trip |
| Charging before confirming the hold extension | Takes money for seats we no longer hold |
| An `ApiResponse<T>` envelope | Fights HTTP; breaks status codes and caching |
| `synchronized` on a blocking path | Pins the carrier thread; looks like a Redis outage |
| A `@Scheduled` job that is neither idempotent nor lock-guarded | Runs three times, once per replica |
| Evicting queue entries on a missing heartbeat | A Wi-Fi → cellular handover deletes live buyers from the line |
| Rendering `503 INVENTORY_UNAVAILABLE` as "sold out" | Tells thousands the sale ended when a counter is merely missing |
| Granting a grace extension per payment attempt | 300 + 3×120 = 660 s; three declines buy 11 minutes of squatting |
| Retrying a deterministic render failure | Same stack trace three times, same DLQ, queue delayed |
| Sizing promotion batches from inventory alone | Admits 5,000 buyers into a 30-connection pool |
| `@Modifying(clearAutomatically = true)` on the settle claim | Detaches every other entity in the transaction — the order's status change is silently discarded |
| Validating the hold before checking for a confirmed order | A resubmitted checkout gets `410 HOLD_EXPIRED` instead of its receipt; the hold is gone because the purchase succeeded |
| Treating `PENDING` as a terminal order state | It is committed before the charge, so any exit that recorded no outcome strands the buyer holding live seats behind a `409` about a charge they never made (ADR-034) |
| Reading `COALESCE(SUM(remaining), 0)` as "sold out" | A `SUM` cannot distinguish "nothing left" from "nothing known". An un-warmed event told its whole waiting room the sale had ended (ADR-035) |
| Deleting the waiting ZSET to express "sold out" | Unrecoverable, and the trigger is a live inventory read that a released hold makes wrong seconds later (ADR-035) |
| Checking ZSET rank before the sale window | A closed sale's queue reports `WAITING` forever, and both the promoter and the broadcaster have already stopped iterating it (ADR-036) |
| A Redis key that is not scoped by event | One visitor in two concurrent sales has one promotion overwrite the other (ADR-036) |
| A claim that survives the failure of the work it guarded | The DLQ replay finds the claim taken and acknowledges without sending (ADR-038) |
| `saveAndFlush` + catch `DataIntegrityViolationException` + **return** | The transaction is rollback-only; the return throws `UnexpectedRollbackException` at commit. Use `ON CONFLICT DO NOTHING` and a rowcount (ADR-038) |
| Trusting `X-Forwarded-For` without a trusted-proxy check | Unlimited fresh IP buckets from one caller, and with a free-to-mint session bucket that is no rate limiting at all (ADR-039) |
| Filtering rehydration to "in flight" states | A completed purchase vanishes on reload and the buyer is invited to re-buy what they own (ADR-037) |
| `Math.max(remaining, 0)` on a counter that can return `-1` | Clamps the *fault code* into a *number*. A missing counter is published as `SOLD_OUT` on the landing page, and the client renders that tier unclickable (ADR-040) |
| A `@RestControllerAdvice` catching `Exception` without naming Spring's binding exceptions first | `ExceptionHandlerExceptionResolver` runs before `DefaultHandlerExceptionResolver`, so the backstop owns them: a missing query param answers `500 INTERNAL_ERROR` with no `code` (ADR-041) |
| Marking a notification `DLQ` after the mail server already accepted it | `DLQ` is re-claimable by design, so the replay sends a second ticket (ADR-042) |
| Drawing operator-supplied text with a standard-14 PDF font | `showText` throws on anything outside WinAnsi. Deterministic, so no retry — a Hebrew event title costs a paid buyer their ticket |
| A default `spring.profiles.active` in `application.properties` | Makes a fail-closed profile guard opt-in: the packaged jar boots on dev secrets, silently |
| **Incrementing a Redis counter before the claim that justifies it commits** | The `UPDATE` rolls back and the `INCRBY` does not — the hold returns to `ACTIVE` with its seats already back on sale. The sweeper settles 500 in one transaction, so one failure does it 500 times (ADR-046) |
| **Compensating a Redis decrement on *any* exception** | A constraint rejection is a definite rollback and safe to compensate; a failure at *commit* is ambiguous, and returning seats that may still be held is an oversell. Let ambiguity fall to drift (ADR-046) |
| **A table written by nobody's reader** | `tier_inventory` outlived its purpose as a write-only copy of a number that had moved to Redis. A stale column named `remaining` reads exactly like the truth (ADR-046) |
| **Letting a Lua return code escape its repository** | `-1` meant "sold out" at one layer and "no counter at all" one layer up — the precise pair of meanings this design spends its effort separating |
| **Detecting a fault by consuming its evidence** | The first restart guard stamped a shared key when it fired, so whichever replica noticed used the signal up and the others sold on. Derive the verdict from durable state; do not consume it (ADR-046) |
| **Adding `proxy_set_header` to an nginx `location`** | It REPLACES the inherited set, it does not merge. Three locations added `Connection ""` and silently dropped `Host` and `X-Forwarded-For`: `Host` fell back to `$proxy_host` = `flashseats_app`, and Tomcat rejects the underscore — *every* proxied API request answered a bare HTML 400, below Spring, with no `code`. Include the shared set in every location (ADR-047) |
| **A load harness that is one container pretending to be 10,000 buyers** | One source address is one IP bucket: capacity 300, refill 150/s. The run measures the rate limiter, not the sale. Each VU needs its own `X-Forwarded-For` (ADR-047) |
| **Seeding a load-test sale with `ON CONFLICT DO NOTHING` on id 1** | The postgres volume outlives the run and the dev seeder already owns id 1. The insert silently does nothing and the test asserts a 500-seat capacity against a 700-seat sale (ADR-047) |
| **Treating a publisher confirm as proof of delivery** | A confirm means the *broker* has the message, not that a *queue* does. An exchange with no matching binding acks and discards — and the whole notification topology sits behind a property. `mandatory` + publisher-returns, and treat a return like a nack (ADR-048) |
| **Trusting a `hold:{token}` expiry event** | `grantGrace` moves a hold's expiry in PostgreSQL, so the original timer fires mid-payment. Re-read the row and settle only what the sweeper would have; re-arm anything still alive (ADR-048) |
| **A per-message wait on an asynchronous ack** | A batch of 100 against a sick broker is 100 sequential timeouts on the relay thread. Send the batch, await it once (ADR-048) |
| **Reusing "is it on sale?" to mean "should we still watch it?"** | Pausing a sale dropped it from the drift gauge *and* the Redis-restart guard — so a paused event's rolled-back counters were flagged only once someone resumed and started selling from them (ADR-048) |
| **Returning `OrderReceiptResponse` from an admin endpoint** | `receiptToken` is a 90-day bearer capability. An operator view would mint a durable impersonation link into terminal history and any log that records bodies (ADR-048) |
| **Guarding a password with `equals("admin")`** | It refuses one known string. `{noop}hunter2` passes and is stored in plaintext. Refuse the *encoding*, not the value (ADR-048) |
| **Editing a migration that has already been applied — even only its comments** | Flyway checksums the whole file. `V9`'s comment block was rewritten after the measurement that motivated it, and **every container then refused to start**: `Validate failed … checksum mismatch for version 9`, with identical DDL. A migration is immutable the moment any database has run it; new understanding goes in a new migration, an ADR, or the code that issues the query. Recovery is `UPDATE flyway_schema_history SET checksum = <resolved> WHERE version = …` (what `flyway repair` does) on every database that applied the old one |
| **Making a per-replica health gauge a singleton** | Only the lock winner updates its gauge; the others report the value they never set — `0.0` — for ever. A canary that reads healthy on two replicas out of three is worse than a triplicated one. If the duplicate work ever matters, compute once and **publish to all**, never compute once and let the others answer zero (ADR-046's "derived, never consumed") |
| **A guard that asks "is anything missing?" but never "is there anything there?"** | `counters.size() < tierIds.size()` is `0 < 0` for an event with no tiers — false — so it summed an empty list and answered `0`. The promoter reads 0 as sold out and marks the event exhausted *permanently*, because the marker clears only when `remaining > 0`. ADR-035's trap inside the method written to kill it, on the normal path: every event exists before its tiers do |
| **Casting a pipelined Redis connection to `StringRedisConnection`** | Inside `executePipelined` the connection is a proxy. The cast compiles and throws `ClassCastException` at runtime. Use the byte-level `stringCommands()` / `keyCommands()` / `zSetCommands()` API. And read the replies defensively: `EXISTS` may come back `Boolean` **or** a number, and `Boolean.TRUE.equals(1L)` is `false` — which would report an exhausted sale as `WAITING` for ever |
| **A capacity limit expressed as a formula over quantities that do not share units** | `90 connections / 8 transactions per buyer = 11` looks derived and is arbitrary: a concurrency over a count is neither, and it was then spent as a per-second rate. It capped a sale at 76 % while the pool sat 89 % idle. A limit is a rate with a **measured** ceiling — raise it until `hikaricp_connections_pending` stops returning to zero (ADR-049) |
| **A scheduled job that protects a resource by reading that resource** | `PromotionWorker` bounds admission to protect the connection pool — and called `findOpenEventIds()`, a pooled read, every tick. Under pressure it queued behind the buyers it existed to admit: **16 s inside a 1 s tick**, so nobody was promoted, the queue did not drain, and the polling that saturated the pool continued. Ask not what a read costs but what stops working when it is slow (ADR-051) |
| **A cache with no TTL** | Eviction reaches one replica. A paused sale then answers `OPEN` on the other two *for the life of the process*, and the window status gates join, holds and checkout — so pause stops nothing. The TTL **is** the cross-replica invalidation (ADR-051) |
| **Loading a cache entry inside `computeIfAbsent`** | The loader runs inside `ConcurrentHashMap`'s per-bin `synchronized`, so a blocking JDBC read there **pins carrier threads** — the Redisson failure (ADR-022), reached through a cache. Load outside the map: `get`, load, `put` (ADR-051) |
| **A recovery path that reads a cache** | `prewarm` and the rebuild write inventory counters *derived from the tier list*. A stale list leaves a tier with no counter — a `503` for the rest of the sale — or rebuilds the wrong set. Probably-right input, definitely-wrong counter (ADR-051) |
| **Caching a value derived from the clock** | A window status flips with no write to evict on, so the one thing nothing can detect goes stale. Cache the row; derive the status every call (ADR-051) |
| **Disabling a feature in the test profile so the suite passes** | The configuration production runs then has no coverage at all. Give the fixture a seam instead — `SaleFixture.reset()` clears every `DerivedStateCache` (ADR-051) |
| **An instrument stricter than the ADR it cites** | `pool-pressure.sh` failed a whole run on ONE non-zero drift sample while `sold-count.sh` read the ledger and found the invariant exact on every tier. ADR-046 says *sustained*, because Redis and PostgreSQL are not read in one snapshot. A false correctness alarm during a load drill costs more than no alarm: it is specific, so it gets believed (ADR-047) |
| **Iterating open events in a fixed order while spending a shared budget** | Every replica reads the same ascending list, so the lowest event id takes the whole allowance every tick and the other sales stand still. Shuffle the order (ADR-049) |
| **Compensating on an exception rather than on a fact** | `catch (RuntimeException)` around a settlement refunded a buyer whose seats were fine whenever the database blipped — and answered the provider `200`, so nothing ever retried. Only a *definite* failure may move money; ambiguity falls to the redelivery (ADR-056, ADR-046) |
| **Cleanup in an unguarded `finally`** | A throw from `finally` **replaces** the value the block was returning. An unguarded `redis.delete` discarded a *successful* charge and marked the order `FAILED` — money moved, order says it did not. Guard it; the key expires anyway (ADR-056) |
| **A cache that retries a failing dependency per request** | While PostgreSQL was down, `ip_rules` opened a connection per request: the load-shedder becoming the load, at the worst moment. Stamp the failed attempt exactly like a success, single-flight the reload, and cache an empty result too (ADR-056) |
| **Reading an operator's rule table on every request** | `ip_rules` gates every API call. A query there puts the rate limiter *inside* the connection pool it exists to protect, queued behind the buyers it is shielding — ADR-051's trap with the filter as the job. Snapshot it, TTL it, and let the TTL be the cross-replica invalidation (ADR-055) |
| **Writing an audit row synchronously on a refusal path** | Every row is written on a path an attacker controls the rate of, so a synchronous insert lets them convert their own `429`s into database load during the sale. Bounded queue, **discard** policy: evidence is not worth an outage (ADR-055) |
| **Refusing a request because the challenge provider was unreachable** | It fails at peak load, because that is when the provider is busiest too — so the failure mode is "the sale closes at exactly the wrong moment". Fail open and audit the degradation (ADR-011, ADR-055) |
| **Deriving a "random" queue draw from the session id** | Idempotent and *precomputable*: ids are free to mint, so a bot grinds candidates offline until it holds a low draw. `ZADD NX` already makes a fresh draw idempotent (ADR-024) |
| **Reusing one provider idempotency key across a 3-D Secure resume** | The client mints ONE key per hold and reuses it on every retry, so a second `charge` replays the cached `requires_action` response **for ever** and the buyer can never finish. Varying the key per attempt is worse: it opens a *second* intent, so they authenticate one payment and are billed for two. Retrieve the existing intent (ADR-054) |
| **Letting a webhook claim survive a failed settlement** | The provider redelivers, the claim says "already handled", and the buyer's settled charge never reaches an order. ADR-038's rule in a new place: `processed_at IS NULL` must mean *in flight*, and a failure must leave **no row at all** (ADR-053) |
| **Binding a webhook body to a DTO before verifying its signature** | The signature is over the *bytes*, not the meaning. Jackson round-tripping an equivalent object changes key order and whitespace, so every legitimate delivery fails verification — and the fix looks like a provider bug for as long as you believe the JSON is the same |
| **Counting declines against a circuit breaker** | A refused card is a *correct answer*. During a flash sale a burst of expired cards is the normal state of the world, so a breaker that counted them opens on a healthy provider and takes the whole sale's payments down. Count only what a transport failure throws (ADR-052) |
| **Making a `@Transactional` claim a private method on the class that calls it** | Spring's proxy does not intercept self-invocation, so it runs with **no transaction at all**, silently. For a webhook claim that is not style: the claim must be *committed* before the settlement it guards begins, or all three replicas settle the same charge |
| **Two authorities for one id space** | SSE reconnect replay numbered its retained frames from a Redis sequence and gave every *other* frame a per-connection `"local-N"`. Positions arrive every two seconds, so a reconnect almost always quoted a `local-N`, which the replay parsed as a number, failed, and answered with an empty list — **the feature could not fire on the normal path**, and nothing failed because nothing tested it. Number only what is replayable and send the rest with **no `id`**: the SSE spec then leaves the client's last-event-id alone (ADR-058) |
| **Retaining a bearer capability in a log that outlives it** | The promotion frame carries a `passToken` whose own key expires in 120 s, and the reconnect log is one ZSET per *event*, kept until sale end plus retention. Writing it there files a spent single-use capability next to the session id it belongs to, for hours. Replay the *fact* and re-read the authority — the `hold:{token}` idiom — or, better, derive the frame from live state on connect, which also works with no `Last-Event-ID` at all (ADR-058) |
| **A client that cannot run the configuration the server defaults to** | `stripe.ts` threw at module load without `VITE_STRIPE_PUBLISHABLE_KEY`, while `flashseats.payment.stripe.enabled` is **false by default** — so the only gateway the SPA could drive was the one nobody runs locally, and a clean checkout was a white screen. Mirror the server's seam on the client (ADR-058) |
| **Adding ignore rules in the same commit that tracks the files** | `.gitignore` never applies to a path git already tracks, so `frontend/node_modules/` sat in the rules while 7,805 of its files sat in the index. The rule reads as protection and is inert; `git check-ignore -v` is how you find out (ADR-058) |
| **A JVM in a container with no memory limit** | `MaxRAMPercentage` is a percentage of the *limit*. With no limit it is a percentage of the whole VM, so three replicas each sized themselves to ~5.7 GiB inside 7.65. It passed at 300–600 VUs and was SIGKILLed by the VM's kernel at 2,000. `dmesg` inside the VM names it; the container's `OOMKilled` flag stays `false` (ADR-062) |
| **Trusting a JVM flag you read in a Dockerfile** | `compose.yaml`'s `JAVA_TOOL_OPTIONS` replaced the image's and dropped `ExitOnOutOfMemoryError` and ZGC, for every run ever measured. Adding a 1.5 GiB limit then made ergonomics pick SerialGC. Read the *effective* flags with `java -XX:+PrintFlagsFinal -version` inside the container (ADR-062) |
| **Reading a periodic gauge more often than it is computed** | `flashseats.stock.drift` is recomputed every 60 s. `pool-pressure.sh` reached a loaded replica every ~24 s and called two reads of *one* computation "sustained drift". The ledger was exact. Sustained means across computations |
| **A psql `\set` in a script that is also given `-v`** | `\set events 5` ran after `-v events=10` and won, so `EVENTS=10` silently seeded five sales while the script pre-warmed ten. Defaults go under `\if :{?var}` |
| **Letting Spring Framework 7 pause cached test contexts** | When a class switches to another context, the cached one is paused and later restarted. The resumed shared context answered every `/queue` request with a bare `500` that never reached its own filters, so no application log showed anything. It looked like pollution *inside* the app for a whole pass. `spring.test.context.cache.pause=never` in `src/test/resources/spring.properties` (ADR-061). If a failure depends on how many contexts the suite has, suspect the framework's context lifecycle first |
| **Classifying a failure by its Spring wrapper** | `CannotCreateTransactionException` means "the pool is busy" *and* "the database is down". Only the first should tell a client to retry in a second. Look for `SQLTransientConnectionException` in the cause chain (ADR-059) |

## Implementation order

Follow [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md). Phases 1 and 2's
inventory work are **done**: the counter went to Redis in Stage 1 without weakening a guarantee,
which was the point of doing correctness first and speed second.

Do not skip ahead: the transaction boundary in `order` (consume the hold *inside* the commit) must
be right from the first line of code. Retrofitting it is exactly how overbooking bugs appear.

**Redis, all of it.** Every key in the system, so a change can be weighed against the whole surface
rather than one module's corner:

| Key | Module | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `catalog:stock:{e}:{t}` | `catalog` | String | none | **the live inventory count**; `noeviction` is mandatory |
| `catalog:vouch:{e}` | `catalog` | String | none | which Redis instance last derived this event's counters (ADR-046) |
| `queue:waiting:{e}` | `queue` | ZSET | sale end | the waiting room; score is arrival, so rank is position. Never deleted (ADR-035) |
| `queue:pass:{e}:{sid}` | `queue` | String | 120 s | the single-use promotion pass |
| `queue:passes:{e}` | `queue` | ZSET | sale end | live passes, scored by expiry, so a count is one `ZCOUNT` |
| `queue:admit:{e}:{sid}` | `queue` | String | 600 s | proof of admission into the sale (ADR-020) |
| `queue:admissions:{e}` | `queue` | ZSET | sale end | live admissions, same trick as `passes` |
| `queue:events:{e}` | `queue` | Pub/Sub | — | promotion fan-out to whichever replica holds the SSE connection (ADR-007) |
| `queue:replay:{e}` | `queue` | ZSET | sale end | the last 256 **broadcast** frames, scored by sequence, so a reconnect can be handed what it missed. A session-targeted frame is **never** retained here — the one that exists carries a pass token (ADR-058) |
| `queue:replay-seq:{e}` | `queue` | String | sale end | the monotonic sequence behind those frames; it is the only SSE `id` the system issues |
| `queue:promote:{e}` | `queue` | String | 900 ms | makes the promotion tick a singleton across replicas (ADR-032) |
| `queue:budget` | `queue` | String | one tick | **the cluster-wide admission allowance**, shared by every open sale. The one key here deliberately *not* scoped by event; its TTL is the window, so replicas need not agree on the time (ADR-049) |
| `queue:exhausted:{e}` | `queue` | String | sale end | derived sold-out marker; deleted the moment stock returns (ADR-035) |
| `payment:inflight:{holdToken}` | `payment` | String | 90 s | duplicate-charge guard, anchored to the hold (ADR-014) |
| `bot:rate:*` | `bot` | Bucket4j | rolling | session-first rate limiting, IP as a coarse backstop (ADR-011) |
| `bot:verified:{sid}` | `bot` | String | 900 s | one challenge verification, remembered per session (ADR-055) |
| `hold:{token}` | `hold` | String | hold TTL | expiry timer. A **hint, never an authority** — the listener re-reads the row and the settle-once claim is what makes three replicas restore once (ADR-048) |

Two rules over that table: a module touches only its own prefix, and **no key here is ever the
authority for anything**. Lose the lot and `ticket_holds` plus the rebuild reconstruct the state;
that is what makes `noeviction` a correctness setting rather than a tuning one.

## Commands

```bash
cp .env.example .env
docker/scripts/dev-up.sh                         # THE local dev entry point. Use this, not a bare
                                                 # `docker compose up -d`. It refuses to continue on
                                                 # a port conflict (naming the process), stops any
                                                 # cluster replicas -- they share this Redis and
                                                 # PostgreSQL but sign queue passes with DIFFERENT
                                                 # secrets, so a pass minted by one is a 401 at the
                                                 # other -- and guarantees one sale is actually OPEN,
                                                 # which CatalogDevSeeder cannot do once the volume
                                                 # holds anything. `--reset` wipes the volumes.
                                                 # It NEVER writes a stock counter (ADR-004).
docker compose up -d                             # PostgreSQL, Redis, RabbitMQ, Mailpit
docker compose up -d postgres                    # strictly-minimal Phase 1

./mvnw -DskipTests compile                       # build
./mvnw test                                      # incl. ApplicationModules.verify() once written
./mvnw spring-boot:run                           # runs the `dev` profile (set in the pom, not in
                                                 # application.properties — a default profile there
                                                 # would make SecretsGuard opt-in, ADR-039)

docker/secrets/gen-env.sh                        # fill .env with real secrets (idempotent).
                                                 # SecretsGuard refuses to start the `docker`
                                                 # profile without this (ADR-039).
                                                 # The admin password is stored BCRYPT-HASHED and
                                                 # printed once; .env cannot authenticate. Scripts
                                                 # read FLASHSEATS_ADMIN_PLAINTEXT (ADR-048):
                                                 #   export FLASHSEATS_ADMIN_PLAINTEXT='...'

docker compose --profile cluster  up -d --build  # Nginx + 3 replicas on :8080 (Phase 4)
docker/seed/seed.sh                              # seed event 9001, pre-warm, wait for OPEN.
                                                 # The `docker` profile seeds no catalog —
                                                 # CatalogDevSeeder is @Profile("dev") (ADR-047)
docker/scripts/fanout-check.sh                   # PROVE promotion fan-out across replicas.
                                                 # 30/30 across >= 2 upstreams or it fails.
                                                 # Refuses a SOLD_OUT sale: a drained counter
                                                 # promotes nobody, and the script used to call
                                                 # that a fan-out failure. Re-seed first
docker/scripts/hold-expiry-check.sh              # PROVE the expiry listener restores seats exactly
                                                 # once, and faster than the sweeper (ADR-048)
docker/scripts/sentinel-failover-check.sh        # PROVE Sentinel promotes a replica and the old
                                                 # primary rejoins. TOPOLOGY ONLY -- it deliberately
                                                 # does not check inventory, and after a failover
                                                 # every event is distrusted until rebuilt (ADR-058)
docker/scripts/redis-master-cli.sh               # redis-cli against whichever node Sentinel calls
                                                 # the primary right now

# The SPA. Dev-only: nginx serves no static root and there is no compose
# service, so the cluster still serves src/main/resources/static (ADR-058).
cd frontend && npm install && npm run dev        # :5173, proxies /api to :8080.
                                                 # cp .env.example .env.local and LEAVE THE STRIPE
                                                 # KEY BLANK to drive the stub gateway, which is
                                                 # what the backend runs by default
cd frontend && npm test                          # vitest unit tests

# Stage 2, the REAL provider. Everything else here runs the stub, deliberately
# -- so none of it can tell you whether Stripe agrees (ADR-052).
export STRIPE_API_KEY=sk_test_... STRIPE_ENABLED=true
docker compose --profile cluster --profile stripe up -d --build
docker compose logs stripe | grep whsec_         # PER SESSION, not per account. Put it in
                                                 # STRIPE_WEBHOOK_SECRET and recreate the app, or
                                                 # every delivery 400s and looks like no traffic
docker/scripts/stripe-check.sh                   # real charge, real 3-DS, real redelivered webhook
docker compose --profile loadtest run --rm k6    # the load run; VUS=n to scale it down
docker/scripts/sse-cadence.sh 60                 # run DURING a load run: is QueueBroadcaster's
                                                 # sweep finishing inside its 2s interval?

# The ADR-049 drill: E sales at once. Every OTHER instrument here runs one
# event, and so did every measurement the capacity numbers rest on.
docker/seed/seed-concurrent.sh                   # seeds 9001..9005, pre-warms all five.
                                                 # EVENTS=10 seeds ten -- it silently seeded five
                                                 # before Pass 13
docker/scripts/pool-pressure.sh 300 &            # THE instrument. Without it the drill
                                                 # proves nothing: the failure is latency,
                                                 # not an error, so k6 sees a green run.
                                                 # Needs FLASHSEATS_ADMIN_PLAINTEXT exported.
                                                 # Drift fails only when one replica stays
                                                 # non-zero for a whole drift interval (60 s):
                                                 # two samples inside one interval are the SAME
                                                 # computation read twice (ADR-046: sustained)
docker compose --profile loadtest run --rm -e VUS=300 k6-concurrent
docker/scripts/sold-count.sh                     # what was ACTUALLY sold, and the invariant per tier.
                                                 # k6's count is what the CLIENT saw: it abandons
                                                 # in-flight requests at 60s and at ramp-down, and
                                                 # under-reported by 8x in the worst Pass 8 run.
                                                 # VUS=300, not 2000, on a ten-core host -- at 2000 the
                                                 # load generator competes with the three JVMs and the
                                                 # same build sells 6% instead of 76%
```

Changing the compose network's `ipam` recreates the network, and containers created against the
**old** one get reattached without their service-name DNS aliases — every service name then resolves
`NXDOMAIN` and the replicas restart-loop on `Unable to connect to redis`. `docker compose down`
first; a plain `up -d` is not enough.

**And `down` needs the profile too.** `docker compose down` without `--profile cluster` leaves every
profiled container — nginx, the replicas, the sentinels — running or stopped but *present*, still
holding a reference to the network it just deleted. The next `up` then fails with
`failed to set up container networking: network <id> not found` for exactly those services, which
reads like a Docker bug and is not one. Use `docker compose --profile cluster down`.

**Every script under `docker/` must be mode 755.** All three that PR #16 added were committed 644,
and `sentinel-entrypoint.sh` is a container `entrypoint` — so the sentinels restart-looped on
`exec …: permission denied`, and because every app replica `depends_on` them, **the cluster had
never once started**. `git ls-files -s 'docker/**/*.sh'` shows the modes; a checkout on a filesystem
without POSIX permissions is how a 644 gets in.

Metrics are scraped **per replica**, not through nginx, and nginx deliberately routes only
`/actuator/health`. `hikaricp_connections_pending` and `flashseats_stock_drift` are per-instance
gauges; through a load balancer you get one replica at random.

UIs: RabbitMQ `:15672`, Mailpit `:8025`. There is no `/docs`: springdoc served an OpenAPI page
derived purely from request mappings, with not one `@Operation` or `@Schema` behind it. The API
contract is [`FE_SPEC.md`](FE_SPEC.md) §2.

## Docker config that is correctness, not tuning

`docker/redis/redis.conf` and `docker/nginx/nginx.conf` encode ADR requirements. Do not replace
either with stock images or defaults:

- `maxmemory-policy noeviction` — a `TTL = -1` key is **not** protected from an LRU policy. Evicting
  a stock counter mid-sale is the worst failure this system has (ADR-004).
- `notify-keyspace-events Ex` — `E` is the key-**event** channel (`__keyevent@0__:expired`, message
  = key name), which is what the hold listener needs. `Kx` publishes the event name to the keyspace
  channel and the listener never fires (ADR-003).
- `appendonly yes` / `appendfsync everysec` — and remember a restart still needs a stock
  reconciliation pass, because `everysec` can lose a second of `DECRBY`s.
- `proxy_buffering off` + `proxy_read_timeout 3600s` on `/api/v1/queue/stream` — buffered SSE makes
  the waiting room look frozen and delays the time-critical promotion frame (ADR-007).
- `worker_connections 20480` — SSE connections are long-lived; the 1024 default dies at ~500 users.
- `mem_limit` on each app replica, plus `-XX:+UseG1GC` in the Dockerfile (ADR-062). Without the
  limit, `MaxRAMPercentage` sizes each heap against the whole Docker VM, and the VM's OOM killer
  took replicas down mid-sale at 2,000 VUs. With the limit and no explicit collector, the JVM
  drops to SerialGC. **Do not set `JAVA_TOOL_OPTIONS` in `compose.yaml`**: it replaces the
  image's value, and one did.

**Always test multi-replica.** `docker compose --profile cluster` runs three. Promotion pub/sub
fan-out and settle-once restoration are both correct on one instance and broken on three if
implemented naively — a single-instance test cannot see either bug.

## Working style for this repo

- Doc changes: update the ADR **and** every document the change touches. Docs drifting apart is
  what created most of the defects in the first pass.
- New concurrency-sensitive code: state the failure mode you are guarding against, and which
  invariant above covers it.
- Adding a dependency: check Boot 4 compatibility and virtual-thread safety. Resilience4j ships a
  Boot-3-targeted starter, so we use the plain artifacts and declare beans ourselves. A library that
  blocks inside `synchronized` pins carrier threads on JDK 21 — that is why Redisson was dropped.
- Do not add a cross-module facade edge without checking the graph stays acyclic.
