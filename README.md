# FlashSeats

A high-concurrency ticket flash-sale engine, built as a **modular monolith** on Java 21 and Spring
Boot 4.

The problem it solves: **10,000 people want 500 tickets and they all arrive in the same second.**
Exactly 500 must sell. Nobody may be charged for a seat they do not get. The 9,500 who miss out must
find out quickly.

### Run it

Needs only Docker and a POSIX shell (Git Bash on Windows):

```bash
docker/scripts/professor-demo.sh     # secrets, a three-replica cluster, two open sales; prints the admin password
open http://localhost:8080           # the React client: the event page, the waiting room, checkout, the PDF ticket
open http://localhost:8025           # local Mailpit inbox (development only)
```

At checkout, the card selector drives every branch with no account: success, a decline that **keeps
your seats**, a provider outage, and 3-D Secure. **What is not done, and why, is listed in
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §9.** Development setup is under
[Getting started](#getting-started).

---

## Documentation

Read in this order:

| Document | What it covers |
| :--- | :--- |
| [`docs/07-system-on-one-page.md`](docs/07-system-on-one-page.md) | **Read first.** The whole system on one page — journey, module graph, checkout sequence, where each concept lives, and what looks removable but is not |
| [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) | 79 ADRs — every non-obvious decision and the failure it prevents. Read before changing a decision |
| [`docs/01-system-architecture.md`](docs/01-system-architecture.md) | Stack, module map, dependency graph, deployment |
| [`docs/02-high-level-design.md`](docs/02-high-level-design.md) | Infrastructure and the concurrency model |
| [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) | **The authoritative user journey**, step by step |
| [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md) | Four phases, each with exit criteria |
| [`docs/05-global-standards.md`](docs/05-global-standards.md) | **Cross-cutting contract** — RFC 7807, error registry, idempotency, transaction rules, facade rules |
| [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) | **What is actually built** — scope, security posture, next stages, review-pass log |
| [`FE_SPEC.md`](FE_SPEC.md) | **Front-end specification** — view state machine, API map, storage, SSE, timers, copy, design system, and the Playwright suite |
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
| **Frontend** | **The React client in [`frontend/`](frontend/)** (React 18, MUI 6, Vite, Playwright) — the deliverable, built to [`FE_SPEC.md`](FE_SPEC.md). A single-file API demo is served at `/` by the backend for walking the API with no build step |

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

Prerequisites: JDK 21 and Docker, plus Node 22 and npm to run the React client outside Docker (the
cluster's nginx image builds it with `node:22-alpine`).

```bash
cp .env.example .env
docker/scripts/dev-up.sh            # preflight + infrastructure + a sale that is actually open
./mvnw spring-boot:run              # the API on :8080, with a minimal API demo page at /
cd frontend && npm install && npm run dev   # the React client on :5173
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
   the built-in `dev-only-change-me` secrets. This uses the local Mailpit container at
   `mailpit:1025`; messages are visible at `http://localhost:8025` and are not delivered to real
   recipients.
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

### Email configuration

Mailpit is intentionally retained in Compose for local development and integration testing. It is
not a production mail service. The application reads the SMTP settings from `.env`:

| Variable | Local default | Preview/production |
| :--- | :--- | :--- |
| `SMTP_HOST` | `mailpit` | The team's SMTP relay hostname |
| `SMTP_PORT` | `1025` | The relay's TLS/submission port |
| `SMTP_USERNAME` | empty | Relay username, if required |
| `SMTP_PASSWORD` | empty | Relay password, if required |
| `SMTP_AUTH` | `false` | `true` when the relay requires authentication |
| `SMTP_STARTTLS` | `false` | `true` when the relay requires STARTTLS |
| `SMTP_FROM` | `tickets@flashseats.dev` | An approved sender address |

Before deploying the preview, set all `SMTP_*` values in the preview environment to the real
relay. In particular, `SMTP_HOST` must not be `mailpit`; the Mailpit service is only the local
fallback supplied by `.env.example`. After deployment, place a test order and verify the message
arrives in the intended mailbox, then check the Mailpit UI remains empty for that order.

#### Optional real-email demonstration with Resend

The evaluator does not need a Resend account. For a personal demonstration, create a Resend
account, verify the sender address, and edit only your ignored `.env` with:

```env
SMTP_HOST=smtp.resend.com
SMTP_PORT=587
SMTP_USERNAME=resend
SMTP_PASSWORD=re_your_resend_api_key
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_FROM=onboarding@resend.dev
```

Restart the Compose stack after changing these values. The ticket generated by a successful
purchase can then be delivered to the buyer's real email address. Revert those values to the
Mailpit defaults when sharing the repository or demonstrating a clean first run.
becaouse resend and pretty much any other equivilent service whould require a domain to expand 
the amount of emails aprove to send it can only send a ticket in email to 
sherers@post.bgu.ac.il but the program clearly have the capability to be expanded to this direction
fairly easily.
make sure to not use concurrency with this mail set up as the resend teir were using have 300/day
limit.

The remaining Compose and load-test overrides are also listed in `.env.example`, including
`APP_MEM_LIMIT`, `K6_FIRST_ID`, `K6_EVENTS`, `K6_WAIT_SECONDS`, `K6_POLL_SECONDS`,
`FLASHSEATS_CATALOG_METADATA_CACHE_ENABLED`, and
`FLASHSEATS_QUEUE_GLOBAL_ADMISSION_BUDGET_PER_TICK`. They can be left at their documented defaults
for the first run.

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

Under `spring-boot:run`, `:8080` serves only the API and a minimal single-file demo page — enough to
walk the journey with no build step, and labelled as such. The client to use is the React one, on
`:5173` in development and on `:8080` under the cluster. In either, the checkout's card selector
drives the interesting branches: `pm_card_declined` declines and **keeps your seats**,
`pm_card_error` fails the provider. The email lands in Mailpit at
[localhost:8025](http://localhost:8025).

### The React client

The React client implements [`FE_SPEC.md`](FE_SPEC.md). Under `--profile cluster` the nginx image
builds it and serves it at `:8080` (ADR-068); for development, run it on its own:

```bash
cd frontend
npm install
cp .env.example .env.local          # leave the Stripe key BLANK to drive the stub gateway
npm run dev                         # http://localhost:5173, /api proxied to :8080
npm test                            # vitest unit tests
npm run test:e2e                    # the FE_SPEC §8 Playwright suite against the real backend;
                                    # needs `./mvnw spring-boot:run` (the dev profile) on :8080
```

The Docker build deliberately excludes `frontend/.env.local`, so a developer's optional Stripe
publishable key cannot change the evaluator image. Configure the cluster's payment mode with the
root `.env` (`STRIPE_ENABLED=false` for the deterministic stub, or `true` for real Stripe).

With no `VITE_STRIPE_PUBLISHABLE_KEY` the checkout offers the stub's outcomes directly — succeed,
decline, gateway outage, 3-D Secure — which is the easiest way to walk the failure branches in a
browser. Set a `pk_test_` key, and start the backend with `STRIPE_ENABLED=true`, to drive the real
provider.

```bash
./mvnw test                         # 311 tests, green in any class order (ADR-061). Needs Docker:
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
docker/seed/seed.sh                                 # one sale of 500, pre-warmed
docker compose --profile loadtest run --rm -e VUS=300 k6               # one sale
docker/seed/seed-concurrent.sh                      # five sales of 500
docker compose --profile loadtest run --rm -e VUS=10000 k6-concurrent  # five at once
docker/scripts/sold-count.sh                        # what actually sold, and the invariant per tier
```

`VUS` counts **buyers**: each k6 VU drives ten of them, each with its own session and address, and
they poll at the client's own 5 s fallback cadence (ADR-079). On a ten-core laptop, 10,000 buyers
sell all 2,500 seats of five concurrent sales with no oversell; the latency tail at that size is the
laptop, where k6 takes 2–3.5 of the ten cores. Each replica has a 1.5 GiB memory limit
(`APP_MEM_LIMIT` to change it): without one, the JVMs size their heaps against the whole Docker VM
and get OOM-killed under load (ADR-062). What the drills measured is in
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §9 and §11.

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
docker/k6/waiting-room.js       the waiting room alone: join and poll, no checkout
docker/scripts/professor-demo.sh   the evaluator's one command (ADR-068)
docker/scripts/dev-up.sh        the local development entry point
docker/scripts/fanout-check.sh  promotion fan-out across replicas, 30/30 or it fails
docker/scripts/hold-expiry-check.sh   expiry restores seats once, across replicas
docker/scripts/sentinel-failover-check.sh   Sentinel promotes a replica and the old primary rejoins
docker/scripts/redis-master-cli.sh    redis-cli against whichever node is primary now
docker/scripts/pool-pressure.sh pending connections and drift per replica, during a drill
docker/scripts/sse-cadence.sh   how fast position frames actually arrive, during a drill
docker/scripts/sold-count.sh    what actually sold, and the stock invariant per tier
docker/scripts/stripe-check.sh  a real Stripe charge, 3-D Secure and a redelivered webhook
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

Invariant 1 is the `flashseats.stock.drift` metric. Alarm on **sustained** non-zero: Redis and
PostgreSQL are not read in one snapshot, so a single sample can catch a hold in flight (ADR-046).

---

## Project status

**MVP built and running.** All nine modules, the full journey from landing page to emailed PDF, and
a test suite that proves the guarantees rather than asserting them —
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) is the reference for what exists, what is
deliberately deferred, where the security gaps are, and what comes next.

The design behind it took four passes before any code:

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

Fifteen review passes over the built code followed — correctness, the Redis fast path, the
three-replica cluster, the operator surface, real payments, concurrent sales, the React client and
the 10,000-buyer drill — each logged in [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §13.
79 ADRs record every decision and the failure it prevents.
