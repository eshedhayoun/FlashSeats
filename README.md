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
| [`REFACTORING_BLUEPRINT.md`](REFACTORING_BLUEPRINT.md) | **Start here.** The whole system on one page — journey, module graph, where each concept lives, and what looks removable but is not. Then the staged refactor that makes the code match it |
| [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) | 60 ADRs — every non-obvious decision and the failure it prevents. Read before changing a decision |
| [`docs/01-system-architecture.md`](docs/01-system-architecture.md) | Stack, module map, dependency graph, deployment |
| [`docs/02-high-level-design.md`](docs/02-high-level-design.md) | Infrastructure and the concurrency model |
| [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) | **The authoritative user journey**, step by step |
| [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md) | Four phases, each with exit criteria |
| [`docs/05-global-standards.md`](docs/05-global-standards.md) | **Cross-cutting contract** — RFC 7807, error registry, idempotency, transaction rules, facade rules |
| [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) | **What is actually built** — scope, security posture, next stages, review-pass log |
| [`FE_SPEC.md`](FE_SPEC.md) | **Front-end specification** — view state machine, API map, storage, SSE, timers, copy, and the planned Playwright suite |
| [`docs/modules/`](docs/modules/) | Per-module specs — `catalog`, `queue`, `hold`, `bot`, `payment`, `order`, `notification`, `saleflow`, `shared` |

When a module spec disagrees with an ADR, **the ADR wins** and the module spec is stale.

---

## Repository layout

```
pom.xml, mvnw, src/        the backend: one Maven project, at the root by Maven convention
frontend/                  the React client (Vite + TypeScript), the one sub-package
docker/                    infrastructure: redis/nginx config, seeds, secrets, k6, drill scripts
docs/                      ADRs, standards, journey, per-module specs
compose.yaml, Dockerfile   the local stack and the application image
```

The backend is not in a `backend/` folder. That was considered and left alone on purpose. Moving it
would change about fifty paths across the Dockerfile, compose, the drill scripts and the docs, and it
would gain nothing in behaviour. The Maven project sits at the root, and `frontend/` is the one thing
alongside it.

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
| `bot` | Rate limits, IP rules, the challenge check on join | PG + Redis |
| `catalog` | Events, tiers, sale windows, **inventory** | PG + Redis |
| `queue` | Waiting room, SSE, HMAC passes, admission control | Redis only |
| `hold` | Time-bound reservations, settle-once stock restoration | PG + Redis |
| `payment` | Stripe, idempotency, webhooks, refunds | PG + Redis |
| `order` | ACID ledger, **checkout orchestration**, outbox | PG only |
| `notification` | PDF tickets, email, DLQ replay | PG + RabbitMQ |
| `saleflow` | Read-only rehydration endpoint | none |
| `shared` | Open module: error codes, `SessionId`, `SignedToken`, `Clock`, the PDF renderer | none |

Dependencies are acyclic and verified at build time by `ApplicationModules.verify()`:

```
                    shared        ← open module; everyone may depend on it

bot      ──► shared only          ← `filter` is a PACKAGE in `bot`, not a module
queue    ──► catalog, bot         ← `bot` only on join; acyclic, `bot` needs nothing
hold     ──► queue, catalog
order    ──► hold, catalog, payment, queue
saleflow ──► queue, hold, order, catalog     ← read-only leaf
payment  ──( PaymentSettledEvent · webhook path only )──► order
order    ──( outbox → RabbitMQ )──► notification
```

The last two arrows are **not** facade calls — one is a Spring event, the other a row RabbitMQ
delivers. Either would be a cycle as a facade edge, which is why neither is one (ADR-005, ADR-009).
Each module's service implements its own facade interface directly; there is no `*Impl` (ADR-057).

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

**1. Overbooking is prevented by an atomic reserve, not by the queue.**
The queue exists so 9,500 people do not hit checkout at once — and so they learn their fate quickly.
Correctness comes from `stock_reserve.lua`: `GET`, compare, `DECRBY` in one atomic step against
`catalog:stock:{e}:{t}`, refusing to go below the requested quantity. **The counter lives in Redis
and PostgreSQL keeps no copy of it** (ADR-046); the `tier_inventory` table this section used to
describe was dropped in `V7`. `hold` moves stock only through `CatalogFacade` — the counter is
`catalog`'s alone.

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
| **Phase 4** | PDFBox 3.0.7, Mailpit, Nginx, k6 |
| **Frontend** | A single-file demo client at `/`; `EventSource` for the waiting room. The React SPA in [`FE_SPEC.md`](FE_SPEC.md) is specified, not built |

All dependencies are declared in [`pom.xml`](pom.xml), grouped by phase. Thymeleaf is **not** among
them — email bodies are text blocks in `EmailComposer` — and four more were removed in Pass 10 for
having no reference at all; the pom records why beside each gap.

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
docker/scripts/dev-up.sh            # preflight + infrastructure + a sale that is actually open
./mvnw spring-boot:run
open http://localhost:8080          # walk the whole journey in a browser
```

### Environment files

There are two example files. Each is copied once to a git-ignored file that you then edit. No other
environment files exist.

| File | Committed? | Created by | Read by |
| :--- | :--- | :--- | :--- |
| `.env.example` | yes | — | a template, nothing reads it |
| `.env` | **no** | `cp .env.example .env`, then `docker/secrets/gen-env.sh` | `docker compose`, which passes it to every container |
| `frontend/.env.example` | yes | — | a template, nothing reads it |
| `frontend/.env.local` | **no** | `cp frontend/.env.example frontend/.env.local` | Vite (`npm run dev`) |

The order, once per machine:

1. **`cp .env.example .env`** — enough for local development. The `dev` and `test` profiles boot on
   the built-in `dev-only-change-me` secrets.
2. **`docker/secrets/gen-env.sh`** — needed before `--profile cluster`. It replaces every default
   secret in `.env` with a fresh one and prints the admin password **once**. `.env` keeps only its
   bcrypt digest. It is idempotent and leaves any value that is already real alone. `SecretsGuard`
   refuses to start any profile other than `dev`/`test` on a default secret (ADR-039), which is why
   the cluster will not come up without this step.
3. **`export FLASHSEATS_ADMIN_PLAINTEXT='…'`** — the password step 2 printed. The admin-facing
   scripts (`seed.sh`, `seed-concurrent.sh`, `pool-pressure.sh`) read it, because `.env`
   no longer holds anything they can log in with. Keep it in your shell, not in a file.
4. **`cp frontend/.env.example frontend/.env.local`** — only if you run the React client. Leave the
   Stripe key blank to use the stub gateway, which is what the backend runs by default.

`.gitignore` covers `.env`, every `.env.*` variant (backups included) and `frontend/.env.local`, and
lets both `.env.example` files through. To check a path, run `git check-ignore --no-index -v <path>`.

**Use `dev-up.sh` rather than a bare `docker compose up -d`.** The bare form works on a clean
machine and has three ways to fail later that all present as "the backend is broken":

| Symptom | Cause |
| :--- | :--- |
| `Port 8080 was already in use` | Another process — often this project's own nginx or a replica, in which case the browser shows a working app that is *not* your code |
| `401 QUEUE_PASS_INVALID` mid-journey | A `--profile cluster` stack left running. Those replicas share this Redis and PostgreSQL but sign queue passes with the real secrets from `.env`, while a local run falls back to `dev-only-change-me`. Whichever app mints the pass, the other rejects it |
| Every event reads `CLOSED` | `CatalogDevSeeder` seeds **only when the database is empty**, deliberately, so a restart never resets a live sale. Once the volume holds anything, nothing reopens the windows |

The script checks all three, fixes the two that are safe to fix, and refuses to continue on a port
conflict rather than killing a process that might not be ours. `--reset` wipes the volumes for a
clean seeded sale. It never writes a stock counter — seeding one from `total_capacity` resurrects
every sold ticket (ADR-004).

The demo client at `/` takes you from the event page through the waiting room to a PDF ticket. Use
the card selector on the checkout screen to drive the interesting branches: `pm_card_declined`
declines and **keeps your seats**, `pm_card_error` fails the provider. The email lands in Mailpit at
[localhost:8025](http://localhost:8025).

### The React client

A second client implements [`FE_SPEC.md`](FE_SPEC.md) in full. It runs against the same backend and
is **development-only** — nginx serves no static root and the cluster still serves the demo client.

```bash
cd frontend
npm install
cp .env.example .env.local          # leave the Stripe key BLANK to drive the stub gateway
npm run dev                         # http://localhost:5173, /api proxied to :8080
npm test                            # vitest
```

With no `VITE_STRIPE_PUBLISHABLE_KEY` the checkout offers the stub's outcomes directly — succeed,
decline, gateway outage, 3-D Secure — which is the easiest way to walk the failure branches in a
browser. Set a `pk_test_` key, and start the backend with `STRIPE_ENABLED=true`, to drive the real
provider.

```bash
./mvnw test                         # 228 tests, green in any class order (ADR-061). Needs Docker:
                                    # every integration test runs real PostgreSQL, Redis and,
                                    # for fulfilment, RabbitMQ containers
```

| Service | Where | Credentials |
| :--- | :--- | :--- |
| App | http://localhost:8080 | — |
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
compose.yaml                    profiles: (default) · cluster · loadtest · stripe
Dockerfile                      multi-stage, JRE 21, non-root
.env.example                    copy to .env (see "Environment files")
docker/secrets/gen-env.sh       real secrets into .env; prints the admin password once
docker/redis/redis.conf         noeviction · notify-keyspace-events Ex · AOF
docker/nginx/nginx.conf         least_conn · SSE unbuffered · X-Forwarded-For
docker/seed/                    seed.sh (one sale) · seed-concurrent.sh (five, for the drill)
docker/k6/flash-sale.js         load harness; asserts zero overbooking
docker/k6/concurrent-sales.js   E sales at once (ADR-049); pair with pool-pressure.sh
docker/scripts/                 dev-up, fan-out / expiry / failover checks, pool-pressure, sold-count
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

**MVP built and running.** All nine modules, the full journey from landing page to emailed PDF, and
a test suite that proves the guarantees rather than asserting them —
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) is the reference for what exists, what is
deliberately deferred, where the security gaps are, and what comes next.

The design behind it took four passes:

- **Pass 1 — correctness.** ADR-001…018: overbooking holes, contradictory checkout flows,
  cross-replica bugs, missing constraints.
- **Pass 2 — best-practice alignment.** ADR-019…025: found a permanent inventory leak (a Redis
  mutation inside a SQL transaction), a missing admission-session tier, no shared error contract, and
  three transaction-boundary violations.
- **Pass 3 — edge-case and UX stress test.** ADR-026…030: a Wi-Fi → cellular handover deleted buyers
  from the queue, per-tier sell-outs were invisible to people waiting for them, promotion batch size
  ignored the connection pool, and per-attempt grace extensions blew the hold ceiling.
- **Pass 4 — implementation.** ADR-031…033: a facade edge that every diagram omitted, an advisory
  lock that could not guard a Redis-only worker, and one exception advice in place of seven. Plus
  the defects the build itself surfaced, recorded in the MVP overview.

33 ADRs record every decision and the failure it prevents.
