# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

FlashSeats — a high-concurrency ticket flash-sale engine. Modular monolith, Java 21, Spring Boot
4.1.1. Currently in **design phase**: the documentation is complete and corrected; almost no
production code exists yet.

**Read [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) before changing
anything.** It contains 18 ADRs, each recording a defect found in the first design pass and the fix.
Several look like over-engineering until you read the failure they prevent.

## Document precedence

```
00-architecture-decisions.md      ← highest authority
03-end-to-end-flow.md             ← the authoritative user journey
01 / 02 (architecture, HLD)
docs/modules/*.md                 ← lowest; a second pass is still pending
```

When a module spec contradicts an ADR, the ADR wins and the module spec is stale — fix the module
spec rather than the code.

## Facts that are easy to get wrong

- **Spring Boot 4.1.1**, not 3.x. Spring Modulith **2.1.1**.
- Base package is **`com.flashseats`** (the app class lives in `com.flashseats.flashseats`).
  Older docs said `com.app.*`; that namespace does not exist.
- Redis is a **single primary + Sentinel**, not Cluster. `hold_reserve.lua` spans hash slots and
  would fail `CROSSSLOT` on Cluster (ADR-018).
- The **transactional outbox is hand-rolled** in `order`. The Spring Modulith event-publication
  starters were deliberately removed; only `spring-modulith-starter-core` and `-starter-test`
  remain, purely for `ApplicationModules.verify()` (ADR-009). Do not re-add them casually.
- `spring.threads.virtual.enabled=true` is load-bearing, not decoration.

## Module boundaries — enforced, not advisory

```
filter  ──► bot
hold    ──► queue, catalog
order   ──► hold, catalog, payment
payment ──( PaymentSettledEvent · webhook path only )──► order
order   ──( outbox → RabbitMQ )──► notification
```

Rules:

- Cross-module calls go through a `*Facade` interface. Never touch another module's `service`,
  `repository`, or `model` package.
- A module reads only its own tables and its own Redis key prefixes.
- **The single exception:** `catalog:stock:{eventId}:{tierId}` is owned by `catalog` and mutated by
  `hold`, exclusively through `hold_reserve.lua` / `hold_restore.lua`. Nothing else may touch it.
- `payment` calls **no** facades. Adding one would make the graph cyclic (ADR-005).
- `order` owns no Redis keys at all.
- The graph must stay acyclic — `ApplicationModules.verify()` fails the build otherwise.

## Invariants — never weaken these

1. `confirmed_sold + active_holds + remaining == total_capacity`, always. This is the
   `flashseats.stock.drift` metric; non-zero is a page-worthy alarm.
2. Stock is restored **exactly once** per hold, via the settle-once claim:
   `GETDEL holdmeta:{token}` (Phase 2+) or a conditional `UPDATE … WHERE status='ACTIVE'` (Phase 1).
3. `UNIQUE(hold_token)` on `orders` — one hold can never become two orders.
4. **Charge before consuming the hold.** There is no `CONSUMED → RELEASED` transition (ADR-001).
5. A missing stock counter is a **fault** (`-2` → `503` → alarm → locked rebuild), never a cache
   miss. Never reseed from `total_capacity` while a sale is open (ADR-004).
6. Charge amounts are computed server-side from `CatalogFacade`. No client input reaches them.
7. Session identity comes from the signed `fsid` cookie only — never a body field, query param, or
   custom header (ADR-010).
8. Outbox polling uses `FOR UPDATE SKIP LOCKED`; notification dedupe is
   `UNIQUE(order_number, kind)` with insert-then-send. A `SELECT`-based check is a race.

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

## Implementation order

Follow [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md). Phase 1 is
PostgreSQL-only and **already correct** — overbooking is impossible via a row-locked conditional
`UPDATE` plus `CHECK (remaining >= 0)`. Later phases make a correct system fast, not a fast system
correct.

Do not skip ahead: the transaction boundary in `order` (consume the hold *inside* the commit) must
be right from the first line of code. Retrofitting it is exactly how overbooking bugs appear.

## Commands

```bash
./mvnw -DskipTests compile     # build
./mvnw test                    # tests (includes ApplicationModules.verify() once written)
./mvnw spring-boot:run         # run — needs PostgreSQL
```

## Working style for this repo

- Doc changes: update the ADR **and** every document the change touches. Docs drifting apart is
  what created most of the defects in the first pass.
- New concurrency-sensitive code: state the failure mode you are guarding against, and which
  invariant above covers it.
- Adding a dependency: check Boot 4 compatibility. Redisson and Resilience4j ship Boot-3-targeted
  starters; we use the plain artifacts and declare beans ourselves.
- Do not add a cross-module facade edge without checking the graph stays acyclic.
