# Running and Development

> How to run FlashSeats locally, configure it, and drive the multi-replica cluster and load drills.
> The project report is the [`README`](../README.md); this page holds the operational detail it links
> to. For the one-command demo, `docker/scripts/professor-demo.sh` is all that is needed.

---

## 1. Getting started

Prerequisites: JDK 21 and Docker, plus Node 22 and npm to run the React client outside Docker (the
cluster's nginx image builds it with `node:22-alpine`).

```bash
cp .env.example .env
docker/scripts/dev-up.sh            # preflight + infrastructure + a sale that is actually open
./mvnw spring-boot:run              # the API on :8080, with a minimal API demo page at /
cd frontend && npm install && npm run dev   # the React client on :5173
```

Under `spring-boot:run`, `:8080` serves only the API and a minimal single-file demo page. That page
is enough to walk the journey with no build step, and it is labelled as such. The client to use is
the React one: on `:5173` in development, and on `:8080` under the cluster. In either, the
checkout's card selector drives the interesting branches: `pm_card_declined` declines and **keeps
your seats**, and `pm_card_error` fails the provider. The email lands in Mailpit at
[localhost:8025](http://localhost:8025).

| Service | Where | Credentials |
| :--- | :--- | :--- |
| App | http://localhost:8080 | — |
| PostgreSQL | `localhost:5432` | `flashseats` / `flashseats` |
| Redis | `localhost:6379` | no auth (dev) |
| RabbitMQ UI | http://localhost:15672 | `flashseats` / `flashseats` |
| Mailpit UI | http://localhost:8025 | — |

### Why `dev-up.sh` and not `docker compose up -d`

A bare `docker compose up -d` works on a clean machine. Later, it can fail in three ways, and each
one looks like a broken backend:

| Symptom | Cause |
| :--- | :--- |
| `Port 8080 was already in use` | Another process. Often it is this project's own nginx or a replica, in which case the browser shows a working app that is *not* your code |
| `401 QUEUE_PASS_INVALID` mid-journey | A `--profile cluster` stack was left running. Those replicas share this Redis and PostgreSQL but sign queue passes with the real secrets from `.env`, while a local run falls back to `dev-only-change-me`. Whichever app mints the pass, the other rejects it |
| Every event reads `CLOSED` | `CatalogDevSeeder` seeds **only when the database is empty**, deliberately, so a restart never resets a live sale. Once the volume holds anything, nothing reopens the windows |

The script checks for all three. It fixes the two that are safe to fix, and on a port conflict it
refuses to continue rather than kill a process that might not be ours. `--reset` wipes the volumes
for a clean seeded sale. The script never writes a stock counter: seeding one from
`total_capacity` would resurrect every sold ticket (ADR-004).

---

## 2. Environment files

There are two example files. Each is copied once to a git-ignored file that you then edit. No other
environment files exist.

| File | Committed? | Created by | Read by |
| :--- | :--- | :--- | :--- |
| `.env.example` | yes | — | a template, nothing reads it |
| `.env` | **no** | `cp .env.example .env`, then `docker/secrets/gen-env.sh` | `docker compose`, which passes it to every container |
| `frontend/.env.example` | yes | — | a template, nothing reads it |
| `frontend/.env.local` | **no** | `cp frontend/.env.example frontend/.env.local` | Vite (`npm run dev`) |

Run these steps once per machine, in this order:

1. **`cp .env.example .env`** is enough for local development. The `dev` and `test` profiles boot
   on the built-in `dev-only-change-me` secrets. Mail goes to the local Mailpit container at
   `mailpit:1025`; messages appear at `http://localhost:8025` and are never delivered to real
   recipients.
2. **`docker/secrets/gen-env.sh`** is needed before `--profile cluster`. It replaces every default
   secret in `.env` with a fresh one and prints the admin password **once**; `.env` keeps only its
   bcrypt digest. The script is idempotent and leaves any value that is already real alone.
   `SecretsGuard` refuses to start any profile other than `dev`/`test` on a default secret
   (ADR-039), which is why the cluster will not come up without this step.
3. **`export FLASHSEATS_ADMIN_PLAINTEXT='…'`** sets the password step 2 printed. The admin-facing
   scripts (`seed.sh`, `seed-concurrent.sh`, `pool-pressure.sh`) read it, because `.env` no longer
   holds anything they can log in with. Keep it in your shell, not in a file.
4. **`cp frontend/.env.example frontend/.env.local`** is needed only if you run the React client.
   Leave the Stripe key blank to use the stub gateway, which is what the backend runs by default.

`.gitignore` covers `.env`, every `.env.*` variant (backups included) and `frontend/.env.local`, and
lets both `.env.example` files through. To check a path, run
`git check-ignore --no-index -v <path>`.

The remaining Compose and load-test overrides are also listed in `.env.example`, including
`APP_MEM_LIMIT`, `K6_FIRST_ID`, `K6_EVENTS`, `K6_WAIT_SECONDS`, `K6_POLL_SECONDS`,
`FLASHSEATS_CATALOG_METADATA_CACHE_ENABLED`, and
`FLASHSEATS_QUEUE_GLOBAL_ADMISSION_BUDGET_PER_TICK`. You can leave them at their documented defaults
for the first run.

---

## 3. Email configuration

Mailpit stays in Compose on purpose, for local development and integration testing. It is not a
production mail service. The application reads the SMTP settings from `.env`:

| Variable | Local default | Preview/production |
| :--- | :--- | :--- |
| `SMTP_HOST` | `mailpit` | The team's SMTP relay hostname |
| `SMTP_PORT` | `1025` | The relay's TLS/submission port |
| `SMTP_USERNAME` | empty | Relay username, if required |
| `SMTP_PASSWORD` | empty | Relay password, if required |
| `SMTP_AUTH` | `false` | `true` when the relay requires authentication |
| `SMTP_STARTTLS` | `false` | `true` when the relay requires STARTTLS |
| `SMTP_FROM` | `tickets@flashseats.dev` | An approved sender address |

Before deploying a preview, set all `SMTP_*` values in that environment to the real relay. In
particular, `SMTP_HOST` must not be `mailpit`, which is only the local default from
`.env.example`. After deploying, place a test order, check that the message arrives in the intended
mailbox, and check that the Mailpit UI stays empty for that order.

### Optional real-email demonstration with Resend

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

Restart the Compose stack after changing these values. The ticket from a successful purchase is
then delivered to the buyer's real email address. Revert to the Mailpit defaults before sharing
the repository or demonstrating a clean first run.

The free Resend tier sends only to its account owner until a domain is verified, and it has a
daily sending limit. Keep Mailpit for load and concurrency tests: they exercise the same email
pipeline without exhausting an external quota or delivering thousands of real messages. A
production deployment needs only a verified domain and the matching `SMTP_*` values; the
application code does not change.

---

## 4. The React client

The React client implements [`FE_SPEC.md`](../FE_SPEC.md). Under `--profile cluster`, the nginx
image builds it and serves it at `:8080` (ADR-068). For development, run it on its own:

```bash
cd frontend
npm install
cp .env.example .env.local          # leave the Stripe key BLANK to drive the stub gateway
npm run dev                         # http://localhost:5173, /api proxied to :8080
npm test                            # vitest unit tests
npm run test:e2e                    # the FE_SPEC §8 Playwright suite against the real backend;
                                    # needs `./mvnw spring-boot:run` (the dev profile) on :8080
```

On a fresh machine, run `npm ci` and `npx playwright install chromium` once before the E2E suite.
Run `docker/scripts/dev-up.sh --reset` when the test database needs a fresh open sale.

The cluster's nginx image builds the client from `frontend/`, so it **includes**
`frontend/.env.local`: a publishable key there is baked into the client served at `:8080`. The
backend's payment mode is set in the root `.env`: `STRIPE_ENABLED=false` for the deterministic stub,
`true` for real Stripe. Set both sides together. To return to the stub, blank the key, set
`STRIPE_ENABLED=false`, and rerun `professor-demo.sh`, which rebuilds the image.

With no `VITE_STRIPE_PUBLISHABLE_KEY`, the checkout offers the stub's outcomes directly: succeed,
decline, gateway outage, 3-D Secure. This is the easiest way to walk the failure branches in a
browser. To drive the real provider, set a `pk_test_` key and start the backend with
`STRIPE_ENABLED=true`.

---

## 5. Backend tests

```bash
./mvnw test                         # 311 tests, green in any class order (ADR-061). Needs Docker:
                                    # every integration test runs real PostgreSQL, Redis and,
                                    # for fulfilment, RabbitMQ containers
```

---

## 6. Multi-replica cluster and load drills

```bash
docker compose --profile cluster up -d --build     # Nginx + 3 app replicas on :8080
docker/seed/seed.sh                                 # one sale of 500, pre-warmed
docker compose --profile loadtest run --rm -e VUS=300 k6               # one sale
docker/seed/seed-concurrent.sh                      # five sales of 500
docker compose --profile loadtest run --rm -e VUS=10000 k6-concurrent  # five at once
docker/scripts/sold-count.sh                        # what actually sold, and the invariant per tier
```

`sold-count.sh` gives the correctness verdict once the traffic stops. For every tier it reports
each term of the stock invariant:

```text
confirmed_sold + active_holds + remaining = total_capacity
```

`VUS` counts **buyers**. Each k6 VU drives ten buyers, each with its own session and address, and
they poll at the client's own 5 s fallback cadence (ADR-079). Each replica has a 1.5 GiB memory
limit (change it with `APP_MEM_LIMIT`); without one, the JVMs size their heaps against the whole
Docker VM and get OOM-killed under load (ADR-062). The measured results are in the README's
[Load test](../README.md#43-load-test) section; the full evidence is in
[`06-mvp-overview.md`](06-mvp-overview.md) §9 and §11.

**Test with three replicas, not one.** Promotion pub/sub fan-out (ADR-007) and settle-once stock
restoration (ADR-003) both behave perfectly on a single instance and break on three if implemented
naively. A single-instance test cannot catch either bug.

For a real Stripe test, copy both example environment files, set a matching test publishable key
and secret key, and set `STRIPE_ENABLED=true`. For a real-email test, use the Resend settings in
[Email configuration](#3-email-configuration). Never commit provider keys. Keep Mailpit and the stub
payment gateway enabled for repeatable demonstrations and load tests.

---

## 7. Docker layout

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

- **`redis.conf`**:
  - `maxmemory-policy noeviction`: a `TTL = -1` key is *not* safe from an LRU policy.
  - `notify-keyspace-events Ex`: this is the key-**event** channel; with `Kx` the hold-expiry
    listener would never fire.
  - AOF `everysec`.

  The stock Redis image with its defaults passes the tests and loses inventory later.
- **`nginx.conf`**:
  - `proxy_buffering off` and a 3600 s read timeout on `/api/v1/queue/stream`.
  - `worker_connections 20480`: the default of 1024 is used up by about 500 waiting users.
