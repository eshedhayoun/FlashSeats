# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

FlashSeats — a high-concurrency ticket flash-sale engine. Modular monolith, Java 21, Spring Boot
4.1.1. The **MVP is built and running**: all nine modules, the full journey from landing page to emailed
PDF ticket, 25 tests green. Inventory is PostgreSQL-only for now — correct, and the Redis fast path
replaces exactly one method body.

**Read [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) before changing
anything.** It contains 39 ADRs, each recording a defect and its fix — 034-039 come from the first review
pass over the built code. Several look
like over-engineering until you read the failure they prevent.

**For what is actually built**, read [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) — scope,
security posture, next stages, and the review-pass log. It is the doc to update after every pass.

## Document precedence

```
00-architecture-decisions.md      ← highest authority (39 ADRs)
05-global-standards.md            ← cross-cutting contract; module docs conform to it
FE_SPEC.md                        ← client contract (repo root)
03-end-to-end-flow.md             ← the authoritative user journey
01 / 02 (architecture, HLD)
docs/modules/*.md                 ← lowest; structural rewrite still pending
```

**ADR-019 supersedes ADR-003** and **ADR-020 amends ADR-006** — the originals are kept for the
record but do not describe the current design.

When a module spec contradicts an ADR, the ADR wins and the module spec is stale — fix the module
spec rather than the code.

## Facts that are easy to get wrong

- **Spring Boot 4.1.1**, not 3.x. Spring Modulith **2.1.1**. Boot 4 moved things:
  JSON is **Jackson 3** (`tools.jackson.databind.ObjectMapper` is the autoconfigured bean — the
  Jackson 2 class is on the classpath with no bean behind it), `@EntityScan` is now
  `org.springframework.boot.persistence.autoconfigure.EntityScan`, and Flyway needs
  `spring-boot-starter-flyway` — `flyway-core` alone runs no migrations.
- Base package is **`com.flashseats`** (the app class lives in `com.flashseats.flashseats`).
  Older docs said `com.app.*`; that namespace does not exist.
- Redis is a **single primary + Sentinel**, not Cluster. `hold_reserve.lua` spans hash slots and
  would fail `CROSSSLOT` on Cluster (ADR-018).
- The **transactional outbox is hand-rolled** in `order`. The Spring Modulith event-publication
  starters were deliberately removed; only `spring-modulith-starter-core` and `-starter-test`
  remain, purely for `ApplicationModules.verify()` (ADR-009). Do not re-add them casually.
- `spring.threads.virtual.enabled=true` is load-bearing, not decoration.
- **Redisson is gone** (ADR-022). Distributed locks are `pg_try_advisory_xact_lock`.
- There are **nine** modules: seven domain + `shared` (open) + `saleflow` (read-only leaf).
- **`SecretsGuard` refuses to start** outside `dev`/`test` while any secret is still
  `dev-only-change-me` — including `docker compose --profile cluster`, which runs the `docker`
  profile. Generate them per `.env.example` (ADR-039).
- Every signed token declares a **`kind`**, length-prefixed into the signed bytes. `SignedToken.sign`
  and `.verify` both take it; a token of one kind never verifies as another.

## Module boundaries — enforced, not advisory

```
                    shared        ← open module; everyone may depend on it

filter   ──► bot
queue    ──► catalog
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog     ← read-only leaf; nothing depends on it
payment  ──( PaymentSettledEvent · webhook path only )──► order
order    ──( outbox → RabbitMQ )──► notification
```

Rules:

- Cross-module calls go through a `*Facade` interface. Never touch another module's `service`,
  `repository`, or `model` package. Full facade rules: `05-global-standards.md` §5 — synchronous,
  never `@Transactional` itself, records not entities, module-owned exceptions only.
- A module reads only its own tables and its own Redis key prefixes.
- **The single exception:** `catalog:stock:{eventId}:{tierId}` is owned by `catalog` and mutated by
  `hold`, exclusively through `hold_reserve.lua` / `hold_restore.lua`. Nothing else may touch it.
- `payment` calls **no** facades. Adding one would make the graph cyclic (ADR-005).
- `order` owns no Redis keys at all.
- The graph must stay acyclic — `ApplicationModules.verify()` fails the build otherwise.

## Invariants — never weaken these

1. `confirmed_sold + active_holds + remaining == total_capacity`, always. This is the
   `flashseats.stock.drift` metric; non-zero is a page-worthy alarm.
2. Stock is restored **exactly once** per hold, via one conditional `UPDATE` on `ticket_holds`
   `WHERE status='ACTIVE'` — in **every** phase. PostgreSQL is the authority; Redis holds the timer.
   There is no `holdmeta` key and no `GETDEL` (ADR-019).
3. `UNIQUE(hold_token)` on `orders` — one hold can never become two orders.
4. **Charge before consuming the hold.** There is no `CONSUMED → RELEASED` transition (ADR-001).
5. A missing stock counter is a **fault** (`-2` → `503` → alarm → locked rebuild), never a cache
   miss. Never reseed from `total_capacity` while a sale is open (ADR-004).
6. Charge amounts are computed server-side from `CatalogFacade`. No client input reaches them.
7. Session identity comes from the signed `fsid` cookie only — never a body field, query param, or
   custom header (ADR-010).
8. Outbox polling uses `FOR UPDATE SKIP LOCKED`; notification dedupe is
   `UNIQUE(order_number, kind)` with insert-then-send. A `SELECT`-based check is a race.
9. **A SQL transaction may contain only SQL** (ADR-023). No HTTP, SMTP, broker, Redis write, PDF
   rendering, or sleep inside `@Transactional`. Side effects go in `AFTER_COMMIT`; the outbox relay
   is three short transactions.
10. All errors are RFC 7807 `ProblemDetail` with a `code` from the `05-global-standards.md` §2
    registry. No `ApiResponse<T>` envelope.
11. `synchronized` **pins virtual threads** on JDK 21. Use `ReentrantLock`. Distributed locks are
    `pg_try_advisory_xact_lock` — Redisson was removed for exactly this reason (ADR-022).

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

## Implementation order

Follow [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md). Phase 1 is
PostgreSQL-only and **already correct** — overbooking is impossible via a row-locked conditional
`UPDATE` plus `CHECK (remaining >= 0)`. Later phases make a correct system fast, not a fast system
correct.

Do not skip ahead: the transaction boundary in `order` (consume the hold *inside* the commit) must
be right from the first line of code. Retrofitting it is exactly how overbooking bugs appear.

## Commands

```bash
cp .env.example .env
docker compose up -d                             # PostgreSQL, Redis, RabbitMQ, Mailpit
docker compose up -d postgres                    # strictly-minimal Phase 1

./mvnw -DskipTests compile                       # build
./mvnw test                                      # incl. ApplicationModules.verify() once written
./mvnw spring-boot:run                           # run locally against the compose services

docker compose --profile cluster  up -d --build  # Nginx + 3 replicas on :8080 (Phase 4)
docker compose --profile loadtest run --rm k6    # 10k virtual buyers
```

UIs: RabbitMQ `:15672`, Mailpit `:8025`, API docs `/docs`.

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
