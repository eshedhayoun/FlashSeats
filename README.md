# FlashSeats

**Author:** Eshed Hayoun

FlashSeats is a high-concurrency ticket flash-sale engine. **10,000 people want 500 tickets, and
they all arrive in the same second.** Exactly 500 must sell, nobody may be charged for a seat they
did not get, and the 9,500 who miss out must find out quickly. The system is built as a modular
monolith on Java 21 and Spring Boot 4, runs as three replicas behind nginx, and has a React client.

**Measured:** 10,000 simulated buyers across five concurrent sales sold 2,500 of 2,500 seats, with
zero oversold and the stock invariant exact on every tier ([§4.3](#43-load-test)).

## Highlights

The hard problems, and where each one is solved and tested:

| Problem | Solution | Code | Proof |
| :--- | :--- | :--- | :--- |
| Thousands of buyers race for the last seat | One Redis Lua script reads, compares and decrements atomically. "Sold out" and "counter missing" are different return codes, so a lost key never reads as sold out | [`stock_reserve.lua`](src/main/resources/redis/stock_reserve.lua) | [`StockReserveConcurrencyIT`](src/test/java/com/flashseats/catalog/StockReserveConcurrencyIT.java) |
| Three replicas all see the same hold expire | A conditional `UPDATE … WHERE status = 'ACTIVE'` in PostgreSQL; only the caller with rowcount 1 returns the seats, and only after its commit | [`TicketHoldRepository`](src/main/java/com/flashseats/hold/repository/TicketHoldRepository.java) | [`HoldLifecycleIT`](src/test/java/com/flashseats/hold/HoldLifecycleIT.java), [`hold-expiry-check.sh`](docker/scripts/hold-expiry-check.sh) |
| Checkout and the payment webhook settle the same charge | Every order transition is a compare-and-set on the `orders` row; a refund is claimed on that row before any money moves | [`OrderRepository`](src/main/java/com/flashseats/order/repository/OrderRepository.java), [`OrderCommitService`](src/main/java/com/flashseats/order/service/OrderCommitService.java) | [`SettlementArbiterIT`](src/test/java/com/flashseats/order/SettlementArbiterIT.java) |
| 10,000 buyers would exhaust the connection pool | A waiting room in a Redis sorted set; one promotion tick across the cluster spends a shared, atomic admission allowance | [`PromotionWorker`](src/main/java/com/flashseats/queue/service/PromotionWorker.java), [`promotion_budget.lua`](src/main/resources/redis/promotion_budget.lua) | [`GlobalPromotionBudgetIT`](src/test/java/com/flashseats/queue/GlobalPromotionBudgetIT.java) |
| A buyer's live connection is on another replica | Server-Sent Events, with promotions fanned out over Redis pub/sub | [`QueueBroadcaster`](src/main/java/com/flashseats/queue/service/QueueBroadcaster.java), [`QueuePubSubListener`](src/main/java/com/flashseats/queue/service/QueuePubSubListener.java) | [`fanout-check.sh`](docker/scripts/fanout-check.sh) |
| A confirmed order must always produce a ticket | Transactional outbox drained with `FOR UPDATE SKIP LOCKED`, RabbitMQ with dead-lettering, and an insert-then-send dedupe on the consumer | [`OutboxRelay`](src/main/java/com/flashseats/order/service/OutboxRelay.java), [`NotificationLogRepository`](src/main/java/com/flashseats/notification/repository/NotificationLogRepository.java) | [`OutboxRecoveryIT`](src/test/java/com/flashseats/order/OutboxRecoveryIT.java) |
| Nine modules must not tangle | Facade-only calls between modules, verified at build time by Spring Modulith | [`ModularityTests`](src/test/java/com/flashseats/app/ModularityTests.java) | `./mvnw test` |

Every non-obvious decision, and the defect it prevents, is one of 81 [Architecture Decision
Records](docs/00-architecture-decisions.md). [`docs/07-system-on-one-page.md`](docs/07-system-on-one-page.md)
is the shortest tour of the whole system.

**Quick start.** You need only Docker and a POSIX shell (Git Bash on Windows). The first run builds
the images and takes several minutes; give Docker at least 8 GB of memory.

```bash
docker/scripts/demo.sh     # secrets, a three-replica cluster, two open sales; prints the admin password
open http://localhost:8080           # the React client: event page, waiting room, checkout, PDF ticket
open http://localhost:8080/admin     # the operator console: sign in as admin with the printed password
open http://localhost:8025           # the local Mailpit inbox, where the ticket email arrives
```

## Contents

1. [Introduction](#1-introduction)
2. [System Specification](#2-system-specification)
3. [Implementation](#3-implementation)
4. [Demonstration](#4-demonstration)
5. [Conclusions](#5-conclusions)
- [Appendix A: Documentation](#appendix-a-documentation)
- [Appendix B: Running and Development](#appendix-b-running-and-development)

---

## 1. Introduction

### 1.1 The problem

In a flash sale, a fixed number of tickets goes on sale at an announced moment, and demand exceeds
supply many times over. For a popular concert, nearly all the traffic of the whole sale arrives in
its first seconds. A conventional web shop is built for steady traffic. Under this burst, it
either slows down until nobody can buy, or, worse, keeps selling after the tickets are gone.

### 1.2 Why it is hard

The first difficulty is **overselling**. Thousands of requests read "1 seat left" at the same
moment, and unless the reservation is a single atomic step, more than one of them succeeds. The
second is **money**: a payment can succeed while the order fails to save, or a buyer can retry and
be charged twice, so every path that moves money has to end exactly one way, confirmed or refunded.

The third is the **stampede** itself. If 10,000 buyers reach checkout at once, the database
connection pool is exhausted in milliseconds and nobody completes a purchase, so admission has to
be paced. Pacing has to work across **several servers at once**: replicas share one sale, so
timers, waiting-room notifications and seat restoration must each happen exactly once, not once per
replica. Finally, **bots** try to jump the line, and the rate limits that stop them must not block a
whole university sitting behind one NAT address.

### 1.3 Project goals

1. **Correctness first:** never oversell, never charge without a ticket, never lose a sold seat.
2. **A complete journey:** from the event page to a PDF ticket in the buyer's inbox.
3. **Fair and informative waiting:** a queue with a live position, and a clear "sold out" the
   moment the stock runs out.
4. **Horizontal scale:** stateless replicas, with 3–10 sales running at the same time.
5. **Evidence:** every guarantee checked by an automated test or a measured load drill.

### 1.4 What we built

The backend is a Spring Boot modular monolith with nine modules. Each module owns its own data, and
the module boundaries are verified at build time. PostgreSQL stores the durable ledger: holds,
orders, payments and the outbox. Redis stores the live stock counters and the waiting room.
RabbitMQ carries ticket fulfilment off the request path. A React client implements the buyer's
journey, and an operator console creates and runs sales. Payments go through a provider
abstraction: an in-process stub by default, with real Stripe (including 3-D Secure) behind a single
setting. The whole system starts with one command.

---

## 2. System Specification

### 2.1 Actors

| Actor | What they do |
| :--- | :--- |
| **Buyer** | An anonymous visitor identified by a signed session cookie. Browses events, joins the waiting room, reserves seats, pays, and receives a PDF ticket by email |
| **Operator** | Signs in to the operator console at `/admin`, or calls the admin API directly. Creates and deletes sales, pre-warms them, pauses and resumes them, rebuilds stock counters after a fault; through the API, also replays failed notifications and manages IP rules |
| **Payment provider** | The stub gateway or Stripe. Charges, refunds, and reports late settlements by webhook |

### 2.2 Functional requirements

| ID | Requirement | How it is met |
| :--- | :--- | :--- |
| **FR-1** | A buyer sees every sale and when it opens | The event list and a countdown driven by the server's clock, not the device's |
| **FR-2** | Waiting is fair and visible | Joining is idempotent, so a refresh keeps the buyer's place; the position updates live over Server-Sent Events (SSE), with polling as a fallback |
| **FR-3** | An admitted buyer chooses seats without racing anyone | A single-use pass (120 s) becomes an admission session (600 s); a hold (300 s) reserves the seats with one atomic step |
| **FR-4** | Paying is safe to retry | The price is computed on the server. A declined card keeps the seats for up to three attempts, 3-D Secure is supported, and a charge that lands after the seats are lost is refunded |
| **FR-5** | A purchase produces a ticket | A confirmed order is rendered as a PDF and emailed, and can be downloaded from the receipt page |
| **FR-6** | A reload or lost connection costs nothing | `GET /api/v1/sale/{eventId}/state` returns the buyer's exact position in the journey |
| **FR-7** | An operator creates and prepares sales | One form creates the event and its tiers, published and with stock prepared; a sale can be deleted until a minute before it opens, and is never edited |
| **FR-8** | An operator controls a live sale | Pause keeps every place in line; rebuild recounts the stock from the ledger after a fault; the console shows exact stock and warns when a sale would not sell |

Figure 1 follows one buyer through FR-1 to FR-5, including the paths a buyer takes when things do
not simply succeed.

```mermaid
stateDiagram-v2
    state "Event page" as Landing
    state "Waiting room" as Waiting
    state "Choosing seats" as Choosing
    state "Paying" as Paying
    state "Confirmed" as Confirmed
    state "Ticket emailed" as Ticketed
    state "Sold out" as SoldOut

    [*] --> Landing
    Landing --> Waiting : join, after the bot check
    Waiting --> Waiting : refresh or pause keeps the place
    Waiting --> Choosing : promoted, pass redeemed
    Waiting --> SoldOut : stock gone
    Choosing --> Paying : seats held, 300 s
    Paying --> Paying : card declined, seats kept
    Paying --> Choosing : hold released or expired
    Paying --> Confirmed : charge succeeds
    Confirmed --> Ticketed : PDF and email, asynchronously
    Ticketed --> [*]
    SoldOut --> [*]
```

*Figure 1. The buyer's journey. Three timers bound it: the pass (120 s), the admission session
(600 s) in which a buyer can compare tiers, release a hold and choose again, and the hold itself
(300 s, extended once while a payment is in progress).*

The full journey, with every edge case, is in [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md).

### 2.3 Non-functional requirements

| Requirement | Target |
| :--- | :--- |
| **No oversell** | Zero, under any load, any failure, and any number of replicas |
| **Exactly-once money** | Every settled charge ends confirmed or refunded, never both and never neither |
| **Concurrency** | 3–10 sales open at the same time; 10,000 buyers across them |
| **Latency** | Checkout p99 under 200 ms at normal load |
| **Horizontal scale** | Stateless replicas; any replica can serve any request |
| **Fault behaviour** | Failures that could cause an oversell stop sales rather than guess. Load-shedding components fail open |
| **Abuse resistance** | Session-first rate limits with a loose per-IP backstop; a challenge check on join |
| **Errors** | Every error is an RFC 7807 problem document with a stable error code |

### 2.4 Correctness invariants

These hold at all times, and the tests and drills check them:

1. `confirmed_sold + active_holds + remaining == total_capacity`, for every tier.
2. No order without exactly one settled hold.
3. No charge without an order row.
4. No confirmed order without an outbox row, so a confirmed order always produces a ticket.
5. Stock is restored **exactly once** per hold.
6. Module dependencies stay acyclic (`ApplicationModules.verify()`).

Invariant 1 is exported as the `flashseats.stock.drift` metric. Only a *sustained* non-zero value
counts as a fault: Redis and PostgreSQL are not read in one snapshot, so a single sample can catch a
hold in flight.

### 2.5 Scope and assumptions

Payments use an in-process stub by default, which drives every branch a real provider can take:
success, decline, provider outage and 3-D Secure. Real Stripe runs in test mode behind a single
setting, `STRIPE_ENABLED=true`. Email goes to a local Mailpit inbox by default; delivery through a
real SMTP relay is a configuration change, not a code change.

Buyers are anonymous: their identity is a signed session cookie, and there are no buyer accounts.
Operators use a web console over the same authenticated API as the scripts, with one operator
account. Sales are created and deleted but never edited or cancelled, and failed notifications and
IP rules are managed through the API only.

---

## 3. Implementation

### 3.1 Technology stack

| Layer | Technology |
| :--- | :--- |
| **Backend** | Java 21 with virtual threads, Spring Boot 4.1.1, Spring Modulith 2.1.1, Spring Security |
| **Data** | PostgreSQL 16 (Flyway migrations), Redis 7 (one primary with Sentinel, not Cluster) |
| **Messaging** | RabbitMQ 3.13 with dead-letter queues |
| **Payments** | Stripe Java 29.2.0, Resilience4j 2.3.0 circuit breakers |
| **Abuse control** | Bucket4j 8.14.0 (Redis-backed), reCAPTCHA v3 |
| **Fulfilment** | PDFBox 3.0.7, SMTP (Mailpit locally) |
| **Frontend** | React 18, TypeScript, MUI 6, Vite |
| **Infrastructure** | Docker Compose, nginx (`least_conn`), three application replicas |
| **Observability** | Spring Actuator, Micrometer, Prometheus |
| **Testing** | JUnit with Testcontainers 1.21.3, Vitest, Playwright, k6 |

All backend dependencies are declared in [`pom.xml`](pom.xml), with the reason beside any that was
removed.

### 3.2 Architecture

```mermaid
flowchart TB
    browser["Browser"] -->|HTTP and SSE| nginx["nginx · least_conn"]
    nginx --> apps["3 stateless app replicas<br/>Java 21, virtual threads"]
    apps --> redis[("Redis + Sentinel<br/>stock · line · timers")]
    apps --> postgres[("PostgreSQL<br/>holds · orders · outbox")]
    apps --> rabbit[["RabbitMQ<br/>ticket messages"]]
    apps --> stripe["Stripe<br/>or stub"]
    apps --> smtp["SMTP<br/>Mailpit locally"]
```

*Figure 2. Deployment. Any of the three replicas can serve any request. Promotions reach the
replica holding a buyer's live connection through Redis pub/sub, and Stripe's webhooks come back in
through nginx like any other request.*

The application replicas hold no state between requests. PostgreSQL is the authority for every
lifecycle: holds, orders, payments. Redis holds fast, reconstructible state: the stock counters, the
waiting room, and timers. If all of Redis is lost, PostgreSQL plus an operator rebuild restores it.

### 3.3 Modules

| Module | Owns | Storage |
| :--- | :--- | :--- |
| `bot` | Rate limits, IP rules, the challenge check on join | PG + Redis |
| `catalog` | Events, tiers, sale windows, **inventory**, creating and deleting sales | PG + Redis |
| `queue` | Waiting room, SSE, signed passes, admission control | Redis only |
| `hold` | Time-bound reservations, settle-once stock restoration | PG + Redis |
| `payment` | Payment provider, idempotency, webhooks, refunds | PG + Redis |
| `order` | The order ledger, **checkout orchestration**, outbox | PG only |
| `notification` | PDF tickets, email, dead-letter replay | PG + RabbitMQ |
| `saleflow` | Read-only "where am I in the sale" endpoint | none |
| `shared` | Open module: error codes, session identity, signed tokens, clock, PDF renderer | none |

Modules call each other only through a facade interface, and each module reads only its own tables
and Redis keys. The dependency graph is acyclic, and the build verifies it:

```mermaid
flowchart TB
    saleflow["saleflow<br/>read-only leaf"]
    order["order"]
    payment["payment"]
    notification["notification"]
    hold["hold"]
    queue["queue"]
    bot["bot"]
    catalog["catalog"]
    shared[["shared<br/>open module, used by all"]]

    saleflow --> order
    order --> payment
    order -.->|outbox, then RabbitMQ| notification
    payment -.->|PaymentSettledEvent| order
    order --> hold
    saleflow --> hold
    hold --> queue
    order --> queue
    saleflow --> queue
    queue -->|join only| bot
    queue --> catalog
    hold --> catalog
    order --> catalog
    saleflow --> catalog
```

*Figure 3. Module dependencies. Solid arrows are facade calls; the dashed ones are not. One is a
Spring event and the other a message RabbitMQ delivers. As facade calls, either would close a
cycle, which the build forbids.*

### 3.4 Key mechanisms

**1. Overbooking is prevented by an atomic reserve, not by the queue.**
The queue exists so that 9,500 people do not hit checkout at once, and so that they learn their
fate quickly. Correctness comes from `stock_reserve.lua`, a Redis script that reads the counter,
compares, and decrements in one atomic step, refusing to go below the requested quantity. The live
count exists only in Redis, under `catalog:stock:{event}:{tier}`, and only the `catalog` module
changes it.

**2. Exactly one replica restores the seats, decided in PostgreSQL.**
A hold ends in one of four ways: consumed, released, expired or swept. Three replicas may all try
to handle the same ending at once. Redis key-expiry notifications are pub/sub, so every replica
receives them. Exactly one caller wins this claim and restores the stock:

```sql
UPDATE ticket_holds SET status = ?, settled_at = now()
 WHERE hold_token = ? AND status = 'ACTIVE';    -- rowcount = 1 ⇒ you won
```

The stock is returned to Redis only **after** this claim commits. If the transaction rolls back,
the seats stay held rather than appearing twice.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> ACTIVE : Lua script takes the seats, then the row is written
    ACTIVE --> CONSUMED : order confirmed
    ACTIVE --> RELEASED : buyer releases
    ACTIVE --> EXPIRED : timer or sweeper
    CONSUMED --> [*] : seats sold
    RELEASED --> [*] : seats restored after commit
    EXPIRED --> [*] : seats restored after commit
```

*Figure 4. The hold lifecycle. Every exit from `ACTIVE` is the conditional update above, so
however many replicas see the same ending, the seats come back once.*

**3. A missing stock counter is a fault, not a cache miss.**
Repopulating a counter from `total_capacity` after a Redis failure would silently resurrect every
ticket already sold. Instead, the reserve script returns a distinct code, the API answers `503`, an
alarm fires, and an operator runs an explicit, locked rebuild from PostgreSQL.

**4. The waiting room works across replicas.**
Joining adds the buyer to a Redis sorted set with "add only if new" semantics, so a refresh never
moves them to the back. Once a second, a single promotion worker across the cluster admits a batch
sized by a shared, cluster-wide allowance, so ten concurrent sales cannot together exceed what the
connection pool can serve. Each promotion is published over Redis pub/sub, so it reaches whichever
replica holds that buyer's SSE connection.

**5. A settled charge ends exactly one way.**
The card is charged **before** the hold is consumed, and outside any database transaction. Three
layers stop a double charge: the client's idempotency key, a per-hold in-flight guard in Redis, and
`UNIQUE(hold_token)` on `orders`. If the buyer leaves mid-payment, the provider's webhook settles
the same charge. Both paths finish with a compare-and-set on the `orders` row. That row decides
whether the charge ends confirmed or refunded, and the two paths can never both win.

```mermaid
sequenceDiagram
    autonumber
    actor Buyer
    participant Order as order
    participant Hold as hold
    participant Payment as payment
    participant Provider as Stripe or stub

    Buyer->>Order: POST /orders/checkout
    Order->>Hold: check the hold, extend it once for payment
    Order->>Order: price on the server, create the order as PENDING
    Order->>Payment: authorize, outside any transaction
    Payment->>Provider: charge
    Provider-->>Payment: result
    alt declined, or 3-D Secure required
        Order-->>Buyer: 402, seats still held, the buyer retries
    else charge succeeded
        Order->>Order: one transaction: consume hold, CONFIRMED, outbox row
        Order-->>Buyer: 201 receipt, or a refund if the hold was lost meanwhile
    end
    Note over Order: after commit, the outbox relay sends the ticket through RabbitMQ
```

*Figure 5. Checkout. Money moves before the hold is consumed and outside any database transaction;
the order row decides how a charge ends.*

The reasoning behind each of these, and the defect it prevents, is recorded in
[`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md).

### 3.5 Development process

We built the system in four phases, each ending with a system that is *correct* rather than merely
*smaller*:

1. A correct transactional core on PostgreSQL alone.
2. The Redis fast path and the waiting room.
3. Bot defence and real payments.
4. Asynchronous fulfilment and scale.

Concurrency guarantees were never retrofitted. The design went through four review passes before
any code, and the built system through seventeen more, each logged in
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §13. Every non-obvious decision is recorded
as one of 81 Architecture Decision Records (ADRs), most of them naming the defect they fixed.

### 3.6 Testing strategy

| Level | Tool | What it proves |
| :--- | :--- | :--- |
| **Backend** | JUnit, Testcontainers | Module logic and the concurrency invariants against real PostgreSQL, Redis and RabbitMQ; module boundaries |
| **Frontend unit** | Vitest | Routing, state transitions, storage, countdowns, and the copy for every API error code |
| **Browser end-to-end** | Playwright | The full buyer journey against the real backend, on desktop and mobile layouts |
| **Cluster checks** | Shell scripts | Promotion fan-out across replicas, expiry restoring seats once, Sentinel failover |
| **Load drill** | k6 + `sold-count.sh` | Thousands of buyers across concurrent sales, and the stock invariant per tier afterwards |

### 3.7 Repository layout

```
pom.xml, mvnw, src/        the backend: one Maven project, at the root by Maven convention
frontend/                  the React client (Vite + TypeScript)
docker/                    infrastructure: redis/nginx config, seeds, secrets, k6, drill scripts
docs/                      ADRs, standards, journey, per-module specs
compose.yaml, Dockerfile   the local stack and the application image
```

---

## 4. Demonstration

### 4.1 The buyer's journey

`docker/scripts/demo.sh` generates secrets, builds and starts the three-replica cluster,
waits until it is healthy, and seeds two open sales. The screenshots below were taken on that
cluster at http://localhost:8080.

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/app-event.png" alt="Event page with the sale open and three ticket tiers">
      <p><b>1. Event page.</b> The sale is open. Each tier shows its price and a coarse
      availability label rather than an exact count.</p>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/app-waiting-room.png" alt="Waiting room showing position 701 in line">
      <p><b>2. Waiting room.</b> Position #701, behind 1,000 simulated buyers from the load
      harness. The position updates live over SSE, and closing the page keeps the place.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/app-choose-seats.png" alt="Seat selection with Floor tier and two tickets chosen">
      <p><b>3. Choosing seats.</b> Once promoted, the buyer has a ten-minute admission session to
      compare tiers. Reserving places a hold on the seats; nothing is charged yet.</p>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/app-card-declined.png" alt="Checkout after a declined card, with the seats still held">
      <p><b>4. A declined card.</b> The seats stay held and the countdown continues, so the buyer
      can retry. The demo gateway's selector drives success, decline, provider outage and
      3-D Secure.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/images/app-confirmation.png" alt="Order confirmation with the PDF ticket download">
      <p><b>5. Confirmation.</b> The order is confirmed. The PDF ticket can be downloaded here, and
      the same ticket is sent by email.</p>
    </td>
    <td width="50%" valign="top">
      <img src="docs/images/email-ticket.png" alt="Ticket email with the PDF attached">
      <p><b>6. The ticket email.</b> Delivered through a real SMTP provider, with the PDF
      attached. Rendering and sending run asynchronously through RabbitMQ, so a slow mail server
      never delays checkout.</p>
    </td>
  </tr>
</table>

By default, email goes to the local Mailpit inbox at http://localhost:8025. The automated and load
tests use it too, so they stay deterministic and never use up a provider's quota.

### 4.2 Automated tests

All suites pass, and the backend suite passes in any class order:

| Suite | Command | Result |
| :--- | :--- | :--- |
| Backend unit and integration | `./mvnw test` | 320 tests passing |
| Frontend unit | `npm test` | 78 tests passing |
| Browser end-to-end | `npm run test:e2e` | 27 specs passing, desktop and mobile layouts |

What each level covers is described in [§3.6](#36-testing-strategy); the run log is in
[`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §13.

### 4.3 Load test

**Setup.** Three application replicas behind nginx, with k6 generating buyers. Each buyer has its
own session and address and polls at the client's own 5-second cadence. Everything, k6 included,
ran on one ten-core development laptop. After each run, `docker/scripts/sold-count.sh` read the
ledger and checked the stock invariant for every tier.

**One sale of 500 seats** (`k6`, 300 and 2,000 buyers): exactly 500 sold, zero oversold, zero
inventory errors, zero rate-limited requests, and zero stock drift on every replica.

**Five concurrent sales of 500 seats each** (`k6-concurrent`, 2,500 seats in total):

| Buyers | Confirmed sold | Oversold | Stock drift | Checkout p50 / p99 | Status p50 / p99 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 300 | 2,500 / 2,500 | 0 | 0 | 10 ms / 70 ms | 2 ms / 11 ms |
| 2,000 | 2,500 / 2,500 | 0 | 0 | 14 ms / 195 ms | 1 ms / 18 ms |
| 10,000 | 2,500 / 2,500 | 0 | 0 | 1.2 s / 7.6 s | 45 ms / 5.1 s |

**Interpretation.** Correctness held at every load: every seat sold, no event oversold, the
invariant was exact on every tier, and no buyer gave up waiting. Checkout p99 stayed under 200 ms
up to 2,000 buyers. At 10,000 it rose to 7.6 s. At that load the laptop's CPU averaged 93 %, and
k6 alone used 2–3.5 of the ten cores it shared with the system it was measuring. The limit was the
host's CPU, not the database connection pool. The latency at 10,000 therefore reflects the test
machine, not a limit we can attribute to the system. Measuring that would need a separate
load-generating host.

### 4.4 Multi-replica checks

Three behaviours are correct on one instance and can break on three, so each has its own script
that runs against the live cluster:

| Check | Script | What it shows | Recorded result |
| :--- | :--- | :--- | :--- |
| **Promotion fan-out** | `fanout-check.sh` | A promotion reaches the buyer whichever replica holds their live connection | 30 of 30 buyers promoted, spread 10/10/10 across the three replicas |
| **Hold expiry** | `hold-expiry-check.sh` | An expired hold returns its seats exactly once, although every replica receives the expiry event | Restored exactly once, in 669–749 ms |
| **Redis failover** | `sentinel-failover-check.sh` | Sentinel promotes a replica, and the old primary rejoins as a replica | Passed |
| **Selling after a failover** | by hand, against the cluster | A promoted Redis node may have lost writes, so selling must stop until the counters are rebuilt | All three replicas refused to sell with a retryable `503`, not "sold out"; one rebuild resumed selling on all three |

The real payment provider was checked separately, on a fresh clone of this repository with a Stripe
test-mode key. `stripe-check.sh` passed: a real charge confirmed the order, a 3-D Secure card
returned a challenge, a webhook was delivered, and its redelivery was dismissed as a duplicate. In a
browser, the Stripe card form handled a declined card (seats kept), a successful retry, and a
completed 3-D Secure challenge. All load and cluster runs used the stub gateway.

### 4.5 The operator console

<img src="docs/images/admin-console.png" alt="Operator console listing an upcoming sale, two open sales and an ended one, with their actions" width="720">

`http://localhost:8080/admin`, signed in as `admin` with the password `demo.sh` prints.
Each sale shows its window, its exact remaining stock per tier, and only the actions the server
would accept right now:

**New sale** creates the event and all its tiers in one form; the sale must open at least two
minutes later, so that its stock can be prepared before buyers arrive. **Pre-warm** appears only
for an upcoming sale whose stock is not ready, and **Rebuild stock** recounts an open sale from the
ledger when the console reports "Stock is not trusted", for example after a Redis restart. **Pause**
and **Resume** stop and restart a sale without losing anyone's place. **Delete** appears only until
a minute before the sale opens, when no buyer can be part of it yet. The login lives in the tab's
memory only, so a reload or a closed tab signs out.

---

## 5. Conclusions

### 5.1 Findings

**Correctness belongs in the data layer, not in the flow.** The queue reduces load, but the only
thing that prevents an oversell is a single atomic operation on a single owner's counter. Every
guarantee we trust is a constraint, a conditional update or an atomic script, rather than
discipline at the call sites. It follows that **correctness and performance are separate claims**:
at 10,000 buyers the stock result was exact while checkout latency rose to seconds. A system under
too much load has to fail slow, never wrong.

**Measure before tuning.** Before the last round of optimisation, the 10,000-buyer drill sold only
22–28 % of the seats. The cause was not the database: the most-called endpoint opened two new TCP
connections per request, and per-request instrumentation that nobody read took a quarter of the CPU
samples. Fixing both took the same drill to 100 %. Earlier, an admission limit derived from a
formula had capped sales at 76 % while the connection pool sat 89 % idle.

**One replica hides the hardest bugs.** Waiting-room notifications and seat restoration were
correct on one instance and broken on three, and only multi-replica tests revealed it. More
generally, **each test level answers a different question**: integration tests proved the
invariants, browser tests found journey defects that unit tests could not, and only the load drill
showed where the time actually went.

### 5.2 Limitations

Latency at 10,000 buyers was measured on a shared laptop and reflects its CPU, so the system's own
ceiling remains unmeasured. A Redis restart or failover stops the affected sales until an operator
rebuilds their counters; this is deliberate, because a counter that may have lost writes must not
keep selling. For the same reason, checkout does not survive a Redis outage: it fails closed, the
safe direction for a payment.

On the product side, a sale cannot be edited or cancelled once created. It can be deleted until a
minute before it opens and paused after that. Operator authentication is a single configured
account rather than an identity provider, and buyers have no accounts: once the session cookie
expires after a day, the emailed receipt link is the only way back to an order. The full list,
including the security posture, is in [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) §9–§10.

### 5.3 Summary

FlashSeats sells exactly the seats it has, to exactly the buyers who paid for them. It does so
across three replicas and up to ten simultaneous sales, and it can show this with a reproducible
drill. The main lesson is that a claim such as "no overselling" is worth something only once it is
an invariant the system checks, and a number anyone can reproduce with one command.

---

## Appendix A: Documentation

| Document | What it covers |
| :--- | :--- |
| [`docs/07-system-on-one-page.md`](docs/07-system-on-one-page.md) | **Read first.** The whole system on one page: journey, module graph, checkout sequence, and what looks removable but is not |
| [`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md) | 81 ADRs: every non-obvious decision and the failure it prevents |
| [`docs/01-system-architecture.md`](docs/01-system-architecture.md) | Stack, module map, dependency graph, deployment |
| [`docs/02-high-level-design.md`](docs/02-high-level-design.md) | Infrastructure and the concurrency model |
| [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) | **The authoritative user journey**, step by step |
| [`docs/04-implementation-roadmap.md`](docs/04-implementation-roadmap.md) | The four phases, each with exit criteria |
| [`docs/05-global-standards.md`](docs/05-global-standards.md) | **Cross-cutting contract**: RFC 7807, error registry, idempotency, transaction and facade rules |
| [`docs/06-mvp-overview.md`](docs/06-mvp-overview.md) | **What is actually built**: scope, security posture, limitations, the review-pass log |
| [`docs/08-running-and-development.md`](docs/08-running-and-development.md) | Local setup, environment files, email, the cluster, load drills, Docker layout |
| [`FE_SPEC.md`](FE_SPEC.md) | **Front-end specification**: view state machine, API map, storage, SSE, timers, copy, the Playwright suite, the operator console |
| [`docs/modules/`](docs/modules/) | One page per module: what it owns, exposes, and must never do |

When a module spec disagrees with an ADR, **the ADR wins** and the module spec is stale.

## Appendix B: Running and Development

The [Quick start](#flashseats) at the top is all the demo needs. For development, see
[`docs/08-running-and-development.md`](docs/08-running-and-development.md):

```bash
cp .env.example .env
docker/scripts/dev-up.sh                    # infrastructure + a sale that is actually open
./mvnw spring-boot:run                      # the API on :8080
cd frontend && npm install && npm run dev   # the React client on :5173
./mvnw test                                 # 320 backend tests; needs Docker
```

## License

[MIT](LICENSE).
