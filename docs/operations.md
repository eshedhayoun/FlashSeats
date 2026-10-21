# Operations

Running FlashSeats locally, configuring it, testing it, and watching it under load. Architecture is
in [`architecture.md`](architecture.md); the API in [`api.md`](api.md).

## 1. Run the demo

Needs only Docker (give it 8 GB) and a POSIX shell.

```bash
docker/scripts/demo.sh            # secrets, 3-replica cluster, two open sales; prints the admin password
docker/scripts/demo.sh --reset    # the same, from empty volumes
```

| Service | Where | Credentials |
| :--- | :--- | :--- |
| React client | http://localhost:8080 | — |
| Operator console | http://localhost:8080/admin | `admin` + the printed password |
| Mailpit (ticket emails) | http://localhost:8025 | — |
| RabbitMQ | http://localhost:15672 | `flashseats` / `flashseats` |

Stop with `docker compose --profile cluster down`. The profile is required: without it the cluster
containers stay behind and the next `up` fails with `network … not found`.

## 2. Develop

Needs JDK 21, Docker, and Node 22 for the client.

```bash
cp .env.example .env
docker/scripts/dev-up.sh                   # PostgreSQL, Redis, RabbitMQ, Mailpit, and one open sale
./mvnw spring-boot:run                     # API on :8080 (dev profile)
cd frontend && npm install && npm run dev  # client on :5173, proxies /api to :8080
```

Use `dev-up.sh` rather than `docker compose up -d`. It refuses to start on a port conflict, stops
cluster replicas that would share this Redis but sign queue passes with different secrets, and
reopens a sale if the database already holds closed ones. `--reset` wipes the volumes. On the `dev`
profile the operator login is `admin` / `admin`.

## 3. Configuration

| File | Purpose |
| :--- | :--- |
| `.env` (from `.env.example`) | Read by Docker Compose. `docker/secrets/gen-env.sh` fills it with real secrets and prints the admin password once; only its bcrypt hash is stored |
| `frontend/.env.local` (from `frontend/.env.example`) | Vite settings. Leave the Stripe key blank to use the stub gateway |

Outside `dev` and `test` the app **refuses to start** on default, short or shared secrets, so run
`gen-env.sh` before the cluster. Scripts that call the operator API read
`FLASHSEATS_ADMIN_PLAINTEXT` from your shell.

**Payments.** The in-process stub is the default and drives success, decline, provider outage and
3-D Secure with no account. For real Stripe (test mode), set `STRIPE_API_KEY`, `STRIPE_ENABLED=true`
and a matching `VITE_STRIPE_PUBLISHABLE_KEY`, start with `--profile cluster --profile stripe`, and
copy the `whsec_` secret from `docker compose --profile cluster --profile stripe logs stripe` into
`STRIPE_WEBHOOK_SECRET`. That secret is minted per `stripe listen` session; a stale one rejects every
webhook silently. `docker/scripts/stripe-check.sh` verifies the setup end to end.

**Email.** Mailpit catches all mail locally. To send real mail, set the `SMTP_*` variables in `.env`
(host, port, username, password, `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_FROM`) and restart.

**Bot defence.** reCAPTCHA is off until `RECAPTCHA_SECRET` and `VITE_RECAPTCHA_SITE_KEY` are set; it
fails open if the provider is unreachable. `FLASHSEATS_TRUSTED_PROXIES` lists the proxies whose
`X-Forwarded-For` entries are honoured; `.env.example` sets nginx's address.

### Main tunables

All in `src/main/resources/application.properties`.

| Setting | Default |
| :--- | :--- |
| Queue pass / admission / hold | 120 s / 600 s / 300 s |
| Payment grace on a hold | +120 s once (ceiling 420 s) |
| Tickets per hold / active holds per session per sale | 6 / 1 |
| Charge attempts per hold | 3 |
| Promotion tick / cluster admission allowance | 1 s / 45 per tick |
| Oversubscription factor | 1.5 × remaining stock |
| Queue ordering | `FIFO` (or `RANDOM`) |
| Checkout after the sale ends | 15 min |
| Rate limits: session / IP | 20 burst, 10/s / 300 burst, 150/s |
| HikariCP pool | 30 connections, 3 s timeout |
| Hold sweeper / drift gauge / outbox poll | 10 s / 60 s / 1 s |

## 4. Tests

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs the backend and frontend unit
suites, the frontend build and the browser suite's type check on every pull request and on pushes to
`main` and `preview`. A pull request cannot merge until both jobs pass.

| Suite | Command | Count | Needs |
| :--- | :--- | :--- | :--- |
| Backend unit and integration | `./mvnw test` | 320 | Docker (Testcontainers runs PostgreSQL, Redis, RabbitMQ) |
| Frontend unit | `cd frontend && npm test` | 78 | — |
| Browser end-to-end | `cd frontend && npm run test:e2e` | 27 | Backend on `:8080` with an open sale (`dev-up.sh`) |

Cluster checks run against `--profile cluster`, because several behaviours are correct on one
replica and broken on three:

| Script | Proves |
| :--- | :--- |
| `docker/scripts/fanout-check.sh` | A promotion reaches the buyer whichever replica holds their stream |
| `docker/scripts/hold-expiry-check.sh` | An expired hold returns its seats exactly once |
| `docker/scripts/sentinel-failover-check.sh` | Sentinel promotes a replica and the old primary rejoins |
| `docker/scripts/stripe-check.sh` | A real charge, a 3-D Secure challenge and a redelivered webhook |

## 5. Load testing

```bash
docker compose --profile cluster up -d --build
docker/seed/seed-concurrent.sh                                         # five sales of 500
docker/scripts/pool-pressure.sh 300 &                                  # pool and drift per replica
docker compose --profile loadtest run --rm -e VUS=2000 k6-concurrent   # VUS = buyers
docker/scripts/sold-count.sh                                           # what sold, and the invariant per tier
```

Each k6 VU drives ten buyers, each with its own session and address, polling at the client's 5 s
cadence. `sold-count.sh` is the correctness verdict: k6 reports what the client saw, the ledger
reports what sold. Discard the first run after a restart (JIT warm-up). `docker/seed/seed.sh` with
the `k6` service runs a single sale; `k6-waiting-room` exercises only join and poll.

Results on one ten-core laptop, five concurrent sales of 500 seats:

| Buyers | Sold | Oversold | Checkout p50 / p99 |
| :--- | :--- | :--- | :--- |
| 300 | 2,500 / 2,500 | 0 | 10 ms / 70 ms |
| 2,000 | 2,500 / 2,500 | 0 | 14 ms / 195 ms |
| 10,000 | 2,500 / 2,500 | 0 | 1.2 s / 7.6 s |

At 10,000 the limit was the host CPU, shared with k6, not the connection pool
(`hikaricp_connections_pending` stayed at zero).

## 6. Monitoring

Metrics are at `/actuator/prometheus` on each replica (operator credentials). Scrape replicas
directly: nginx routes only `/actuator/health`, and the gauges are per replica. No Prometheus server
or dashboard ships with the stack.

| Metric | Alert when |
| :--- | :--- |
| `flashseats.stock.drift` | Non-zero across consecutive 60 s computations (one sample can catch a hold in flight) |
| `flashseats.stock.counters.missing` | Any non-zero: those tiers answer `503` |
| `hikaricp_connections_pending` | Sustained non-zero: the pool is saturated |
| `flashseats.queue.admissions` | Zero while a line has depth: promotion has stalled |
| `flashseats.outbox.lag.seconds` | Above a few seconds: fulfilment has stalled |
| `flashseats.dlq.depth` | Any non-zero: a paid buyer has no ticket yet |
| `flashseats.payment.refund.failed` · `flashseats.payment.charge.stray` | Any non-zero: money needs a person |
| `flashseats.payment.webhook.received` | Zero while orders are placed: likely a stale webhook secret |
| `flashseats.bot.limiter.unavailable` | Any non-zero: rate limiting is failing open |
| `resilience4j_circuitbreaker_state` | `open`: checkouts are failing fast |

## 7. Operator runbook

| Situation | Action |
| :--- | :--- |
| Console shows "Stock is not trusted" (Redis restart or failover) | **Rebuild stock** for each open sale |
| `INVENTORY_UNAVAILABLE` on an open sale | **Rebuild stock** |
| Something is wrong mid-sale | **Pause**; the line keeps its order, held seats can still be paid for. **Resume** when fixed |
| `dlq.depth` above zero | Fix the mail path, then resend from `POST /api/v1/admin/notifications/resend/{orderNumber}` |
| Every request answers `500` after a Redis failover | Should not happen: replicas reconnect through Sentinel. If it does, restart the app replicas |

Never reseed a stock counter by hand from capacity: it resurrects sold seats. Only pre-warm (before
the sale) and rebuild (from the ledger) set counters.

## 8. Known limitations

- **Latency at 10,000 buyers is the laptop's**, not a measured ceiling of the system; that needs a
  separate load-generating host.
- **A Redis restart or failover stops affected sales** until an operator rebuilds them: a counter
  that may have lost writes must not keep selling.
- **Checkout fails closed during a Redis outage.**
- **The stub gateway has no webhook**, so under the stub an ambiguous commit failure is resolved only
  by the buyer's retry. With Stripe, the webhook resolves it.
- **A pause does not stop the clocks**: a pause longer than an admission (600 s) sends those buyers
  to the back of the line.
- **Product scope**: one operator account, no buyer accounts (the session cookie lasts a day; after
  that the emailed receipt link is the way back), sales cannot be edited, and the ticket has no
  scannable barcode.

## 9. Docker layout

```
compose.yaml                 profiles: (default) · cluster · loadtest · stripe
Dockerfile                   multi-stage, JRE 21, non-root, G1, memory-limit aware
docker/redis/redis.conf      noeviction · keyspace expiry events · AOF everysec
docker/nginx/nginx.conf      least_conn · unbuffered SSE · client address forwarding
docker/secrets/gen-env.sh    real secrets into .env
docker/seed/                 demo, single-sale and concurrent-sale seeds
docker/k6/                   load scripts: flash-sale, concurrent-sales, waiting-room
docker/scripts/              demo, dev-up, cluster checks, drill instruments
```

Two settings are correctness, not tuning: Redis `noeviction` (an evicted stock counter resells sold
seats) and nginx's unbuffered SSE (buffering freezes the waiting room). Every script under `docker/`
must stay executable (mode 755).
