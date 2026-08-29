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
| [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) | **Start here.** 18 ADRs — every non-obvious decision and the failure it prevents |
| [`docs/01-system-architecture.md`](docs/01-system-architecture.md) | Stack, module map, dependency graph, deployment |
| [`docs/02-high-level-design.md`](docs/02-high-level-design.md) | Infrastructure and the concurrency model |
| [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) | **The authoritative user journey**, step by step |
| [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md) | Four phases, each with exit criteria |
| [`docs/modules/`](docs/modules/) | Per-module specs — `catalog`, `queue`, `hold`, `bot`, `payment`, `order`, `notification` |

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

Dependencies are acyclic and verified at build time by `ApplicationModules.verify()`:

```
filter  ──► bot
hold    ──► queue, catalog
order   ──► hold, catalog, payment
payment ──( PaymentSettledEvent · webhook only )──► order
order   ──( outbox → RabbitMQ )──► notification
```

---

## The user journey

```
landing (countdown, server clock)
   └─► join sale ──► bot gate (captcha + rate limits)
          └─► waiting room  ── SSE positions ──► promoted
                 └─► queue pass (120 s, single-use)
                        └─► select tier ──► hold (300 s, atomic stock decrement)
                               └─► checkout ──► charge ──► consume hold + commit + outbox
                                      └─► receipt (< 200 ms)
                                             └─► async: RabbitMQ → PDF → email
```

Full detail, with every edge case, in [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md).

---

## Three ideas worth knowing

**1. Overbooking is prevented in `hold`, not in the queue.**
The queue exists so 9,500 people do not hit checkout at once — and so they learn their fate quickly.
Correctness comes from an atomic reserve: a Redis Lua script in Phase 2+, and in Phase 1 a single
row-locked statement, `UPDATE tier_inventory SET remaining = remaining - :q WHERE tier_id = :t AND
remaining >= :q`. Overbooking is impossible from the very first phase.

**2. The settle-once claim.**
A hold ends in one of four ways — consumed, released, expired, swept — and three replicas may all
try to handle the same ending at once. Redis keyspace expiry is *pub/sub*, so every replica receives
it. Exactly one caller wins the claim and restores the stock:

```
Phase 2+ :  GETDEL holdmeta:{holdToken}                        -- atomic, one winner
Phase 1  :  UPDATE ticket_holds SET status=?
             WHERE hold_token=? AND status='ACTIVE'            -- rowcount = 1
```

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
| **Phase 2** | Redisson 3.50.0 |
| **Phase 3** | Bucket4j 8.14.0 (Redis-backed), Stripe Java 29.2.0, Resilience4j 2.3.0 |
| **Phase 4** | PDFBox 3.0.7, Thymeleaf, Mailpit, Nginx, k6 |
| **Frontend** | React + TypeScript (Vite), MUI, `EventSource` |

All dependencies are already declared in [`pom.xml`](pom.xml), grouped by phase.

> Redisson and Resilience4j ship Boot-3-targeted autoconfiguration starters. We use the plain
> library artifacts and declare the beans ourselves rather than fight autoconfiguration on Boot 4.

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

Design phase. The documentation set has been through one full correction pass — 18 ADRs record the
defects found and how each was fixed. A detailed second pass over the per-module specs is planned
before implementation begins.
