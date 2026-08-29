# FlashSeats

A high-concurrency ticket flash-sale engine, built as a **modular monolith** on Java 21 and Spring
Boot 4.

The problem it solves: **10,000 people want 500 tickets and they all arrive in the same second.**
Exactly 500 must sell. Nobody may be charged for a seat they do not get. The 9,500 who miss out must
find out quickly.

---

## Documentation

Read in this order:

| Document | What it covers |
| :--- | :--- |
| [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) | **Start here.** 30 ADRs — every non-obvious decision and the failure it prevents |
| [`docs/01-system-architecture.md`](docs/01-system-architecture.md) | Stack, module map, dependency graph, deployment |
| [`docs/02-high-level-design.md`](docs/02-high-level-design.md) | Infrastructure and the concurrency model |
| [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) | **The authoritative user journey**, step by step |
| [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md) | Four phases, each with exit criteria |
| [`docs/05-global-standards.md`](docs/05-global-standards.md) | **Cross-cutting contract** — RFC 7807, error registry, idempotency, transaction rules, facade rules |
| [`FE_SPEC.md`](FE_SPEC.md) | **Front-end specification** — view state machine, API map, storage, SSE, timers, copy |
| [`docs/modules/`](docs/modules/) | Per-module specs — `catalog`, `queue`, `hold`, `bot`, `payment`, `order`, `notification`, `saleflow`, `shared` |

When a module spec disagrees with an ADR, **the ADR wins** and the module spec is stale.

---

## Architecture at a glance

```
                              [ browser ]
                                   │
                         [ Nginx · least_conn ]
                          proxy_buffering off
                                   │
              ┌────────────────────┼────────────────────┐
         [ app 1 ]            [ app 2 ]            [ app 3 ]
                    stateless · Java 21 virtual threads
              └────────────────────┼────────────────────┘
              ┌────────────────────┼────────────────────┐
        [ Redis 7 ]        [ PostgreSQL 16 ]      [ RabbitMQ ]
     primary + Sentinel      AOF everysec        DLX + DLQ
     stock · queue ·         orders · outbox     PDF + email
     holds · buckets         audit ledgers
```

### Modules

| Module | Owns | Storage |
| :--- | :--- | :--- |
| `bot` | Signed session identity, rate limits, reCAPTCHA | PG + Redis |
| `catalog` | Events, tiers, sale windows, **inventory** | PG + Redis |
| `queue` | Waiting room, SSE, HMAC passes, admission control | Redis only |
| `hold` | Time-bound reservations, settle-once stock restoration | PG + Redis |
| `payment` | Stripe, idempotency, webhooks, refunds | PG + Redis |
| `order` | ACID ledger, **checkout orchestration**, outbox | PG only |
| `notification` | PDF tickets, email, DLQ replay | PG + RabbitMQ |
| `saleflow` | Read-only rehydration endpoint | none |
| `shared` | Open module: error codes, `SessionId`, `Money` | none |

Dependencies are acyclic and verified at build time by `ApplicationModules.verify()`:

```
                    shared        ← open module; everyone may depend on it

filter   ──► bot
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog     ← read-only leaf
payment  ──( PaymentSettledEvent · webhook only )──► order
order    ──( outbox → RabbitMQ )──► notification
```

---

## The user journey

```
landing (countdown, server clock)
   └─► join sale ──► bot gate (captcha + rate limits)
          └─► waiting room  ── SSE positions ──► promoted
                 └─► queue pass (120 s, single-use)
                        └─► admission session (600 s — browse freely)
                               └─► select tier ──► hold (300 s, atomic decrement)
                                      └─► checkout ──► charge ──► consume + commit + outbox
                                             └─► receipt (< 200 ms)
                                                    └─► async: RabbitMQ → PDF → email
```

Three nested timers, not two. The **admission session** is what lets a buyer compare tiers, reload
the tab, or release a hold and pick again without losing their place in the sale.
`GET /api/v1/sale/{eventId}/state` rehydrates all of it after a refresh.

Full detail, with every edge case, in [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md).

---

## Three ideas worth knowing

**1. Overbooking is prevented in `hold`, not in the queue.**
The queue exists so 9,500 people do not hit checkout at once — and so they learn their fate quickly.
Correctness comes from an atomic reserve: a Redis Lua script in Phase 2+, and in Phase 1 a single
row-locked statement, `UPDATE tier_inventory SET remaining = remaining - :q WHERE tier_id = :t AND
remaining >= :q`. Overbooking is impossible from the very first phase.

**2. The settle-once claim, in PostgreSQL.**
A hold ends in one of four ways — consumed, released, expired, swept — and three replicas may all
try to handle the same ending at once. Redis keyspace expiry is *pub/sub*, so every replica receives
it. Exactly one caller wins the claim and restores the stock:

```sql
UPDATE ticket_holds SET status = ?, settled_at = now()
 WHERE hold_token = ? AND status = 'ACTIVE';    -- rowcount = 1 ⇒ you won
```

The same statement in every phase. **PostgreSQL is the authority; Redis holds the timer.** That
ordering is what makes `consumeHold` roll back with the order transaction — an earlier Redis-side
claim could not, and a failed commit left the seats permanently unsellable.

**3. A missing stock counter is a fault, not a cache miss.**
Repopulating from `total_capacity` after a Redis eviction would silently resurrect every ticket
already sold. The reserve script returns a distinct `-2`, the API returns `503`, an alarm fires, and
recovery is an explicit locked rebuild from PostgreSQL. See ADR-004.

---

## Stack

| | |
| :--- | :--- |
| **Runtime** | Java 21 (virtual threads), Spring Boot 4.1.1, Spring Modulith 2.1.1 |
| **Data** | PostgreSQL 16, Redis 7 (single primary + Sentinel — **not** Cluster) |
| **Messaging** | RabbitMQ 3.13 |
| **Ops** | Actuator + Micrometer/Prometheus, Flyway, Testcontainers 1.21.3 |
| **Phase 3** | Spring Security, Bucket4j 8.14.0 (Redis-backed), Stripe Java 29.2.0, Resilience4j 2.3.0 |
| **Phase 4** | PDFBox 3.0.7, Thymeleaf, Mailpit, Nginx, k6 |
| **Frontend** | React + TypeScript (Vite), MUI, `EventSource` |

All dependencies are already declared in [`pom.xml`](pom.xml), grouped by phase.

> Resilience4j ships a Boot-3-targeted autoconfiguration starter, so we use the plain library
> artifacts and declare the beans ourselves. **Redisson was dropped** (ADR-022): once the hold claim
> moved into PostgreSQL its only remaining use was one lock on a rare admin path, and its
> `synchronized`-heavy internals risk pinning virtual threads on JDK 21. The stock-rebuild lock is
> now `pg_try_advisory_xact_lock()`.

---

## Getting started

Prerequisites: JDK 21 and Docker.

```bash
cp .env.example .env
docker compose up -d                # PostgreSQL, Redis, RabbitMQ, Mailpit
./mvnw spring-boot:run              # run the app from your machine
```

For a strictly-minimal Phase 1: `docker compose up -d postgres`.

| Service | Where | Credentials |
| :--- | :--- | :--- |
| App | http://localhost:8080 · API docs at `/docs` | — |
| PostgreSQL | `localhost:5432` | `flashseats` / `flashseats` |
| Redis | `localhost:6379` | no auth (dev) |
| RabbitMQ UI | http://localhost:15672 | `flashseats` / `flashseats` |
| Mailpit UI | http://localhost:8025 | — |

### Multi-replica cluster (Phase 4)

```bash
docker compose --profile cluster up -d --build     # Nginx + 3 app replicas on :8080
docker compose --profile loadtest run --rm k6      # 10k virtual buyers
```

**Test with three replicas, not one.** Promotion pub/sub fan-out (ADR-007) and settle-once stock
restoration (ADR-003) both behave perfectly on a single instance and break on three if implemented
naively. A single-instance test cannot see either bug.

### Docker layout

```
compose.yaml                    profiles: (default) · cluster · loadtest
Dockerfile                      multi-stage, JRE 21, non-root
.env.example                    copy to .env
docker/redis/redis.conf         noeviction · notify-keyspace-events Ex · AOF
docker/nginx/nginx.conf         least_conn · SSE unbuffered · X-Forwarded-For
docker/k6/flash-sale.js         load harness; asserts zero overbooking
```

Two config files carry correctness requirements, not tuning preferences:

- **`redis.conf`** — `maxmemory-policy noeviction` (a `TTL = -1` key is *not* safe from an LRU
  policy), `notify-keyspace-events Ex` (the key-**event** channel; `Kx` would leave the hold expiry
  listener silent), and AOF `everysec`. Running the stock Redis image with defaults passes tests and
  loses inventory later.
- **`nginx.conf`** — `proxy_buffering off` and a 3600s read timeout on `/api/v1/queue/stream`, plus
  `worker_connections 20480` (the 1024 default is exhausted by ~500 waiting users).

---

## Roadmap

| Phase | Goal | Exit criterion |
| :--- | :--- | :--- |
| **1** | Correct transactional core (PostgreSQL only) | Two parallel requests for the last ticket → exactly one wins |
| **2** | Redis fast path + waiting room | Same guarantee at 1,000 concurrent requests |
| **3** | Bot defence + Stripe | Payments survive tab closure; floods throttled |
| **4** | Async fulfilment + scale | 10,000 users / 500 tickets / **zero overbooking** / 500 emails |

Every phase ends with a system that is *correct*, not merely *smaller*. Concurrency guarantees are
never retrofitted — that is how overbooking bugs are born.

### Invariants that must hold at the end of every phase

1. `confirmed_sold + active_holds + remaining == total_capacity` — always.
2. No order without exactly one settled hold.
3. No charge without an order row.
4. No confirmed order without an outbox row.
5. Stock restored **exactly once** per hold.
6. `ApplicationModules.verify()` passes.

Invariant 1 is the `flashseats.stock.drift` metric. It is wired in Phase 1 and must never go
non-zero.

---

## Project status

Design phase, two review passes complete.

- **Pass 1 — correctness.** ADR-001…018: overbooking holes, contradictory checkout flows,
  cross-replica bugs, missing constraints.
- **Pass 2 — best-practice alignment.** ADR-019…025: benchmarked against Ticketmaster / Queue-it /
  AXS. Found a permanent inventory leak (a Redis mutation inside a SQL transaction), a missing
  admission-session tier, no shared error contract, and three transaction-boundary violations.
- **Pass 3 — edge-case and UX stress test.** ADR-026…030: found that a Wi-Fi → cellular handover
  deleted buyers from the queue, that per-tier sell-outs were invisible to people waiting for them,
  that promotion batch size ignored the connection pool, and that per-attempt grace extensions would
  have blown the hold ceiling.

30 ADRs record every decision and the failure it prevents. [`FE_SPEC.md`](FE_SPEC.md) covers the
client. The per-module specs are aligned; a structural rewrite to the `05-global-standards.md` §10
template is the remaining work.
