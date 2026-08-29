# System Architecture

**High-concurrency flash-sale ticketing platform — pattern, stack, and module map.**

---

## 1. Pattern: Modular Monolith

One Spring Boot deployable, divided into seven domain-isolated Java packages. This removes
distributed-transaction and service-discovery complexity while keeping the boundaries strict enough
that any module could later be extracted into its own service.

The boundaries are not honour-system. `spring-modulith-starter-core` plus a single
`ApplicationModules.verify()` test fails the build if any module reaches past another module's
`facade` package. A module reads only its own tables and its own Redis key prefixes — the one
deliberate, documented exception is described in
[`03-end-to-end-flow.md`](03-end-to-end-flow.md#the-one-shared-key-and-its-contract).

The application is **stateless**. All state lives in Redis and PostgreSQL, so any replica can serve
any request — with one caveat that shapes the design: an `SseEmitter` is unavoidably held in one
replica's heap, which is why queue promotions fan out over Redis Pub/Sub (ADR-007).

---

## 2. Technology stack

### Confirmed by `pom.xml`

| Technology | Version | Where and why |
| :--- | :--- | :--- |
| **Java** | 21 | Virtual threads (Loom) carry tens of thousands of concurrent requests and long-lived SSE connections on a small thread pool. Requires `spring.threads.virtual.enabled=true`. |
| **Spring Boot** | **4.1.1** | Framework for all modules: REST, DI, data access. |
| **Spring Modulith** | **2.1.1** | Compile-time enforcement of the module boundaries this document asserts. |
| **PostgreSQL** | 16 | ACID ledger: orders, order items, outbox, hold audit, payments, notification logs, bot rules. |
| **Redis** | 7 | Stock counters, queue ZSET, hold TTLs, rate-limit buckets, idempotency, promotion pub/sub. |
| **RabbitMQ** | 3.13 | Async fulfilment between `order` and `notification`. |
| **springdoc-openapi** | 3.1.0 | Live API documentation. |
| **Lombok** | managed | Boilerplate reduction. |

> Earlier drafts of these documents said "Spring Boot 3.x". The build is on **Boot 4.1.1** with
> **Modulith 2.1.1**, and the base package is `com.flashseats.flashseats`, not `com.app`. All docs
> now reflect the build.

### Added per phase

| Technology | Coordinates | Phase | Why |
| :--- | :--- | :--- | :--- |
| **Redisson** | `org.redisson:redisson:3.50.0` | 2 | Distributed locks for stock rebuild and reconciliation. Plain artifact, not the Boot-3 starter. |
| **Bucket4j** | `com.bucket4j:bucket4j_jdk17-core` + `bucket4j_jdk17-lettuce:8.14.0` | 3 | Token-bucket rate limiting, **Redis-backed** so limits are global across replicas. |
| **Stripe** | `com.stripe:stripe-java:29.2.0` | 3 | Payment gateway (test mode). |
| **Resilience4j** | `io.github.resilience4j:resilience4j-circuitbreaker` + `-retry:2.3.0` | 3 | Circuit breaking and retry around Stripe. Core modules, wired programmatically. |
| **PDFBox** | `org.apache.pdfbox:pdfbox:3.0.7` | 4 | In-memory PDF ticket rendering. |
| **Thymeleaf** | `spring-boot-starter-thymeleaf` | 4 | HTML email templates. |
| **Nginx / k6 / Mailpit** | Docker Compose | 4 | Load balancing, load generation, local SMTP capture. |

Frontend: React + TypeScript (Vite), MUI, and the browser-native `EventSource` API for the queue
stream.

> **Boot 4 compatibility note.** `resilience4j-spring-boot3` and `redisson-spring-boot-starter`
> target Boot 3.x autoconfiguration. We use the plain library artifacts and declare the beans
> ourselves — a few lines of `@Configuration` in exchange for not fighting autoconfiguration.

---

## 3. Modules

| # | Module | Responsibility | PostgreSQL | Redis |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **`bot`** | Signed `fsid` cookie, Redis-backed rate limits, reCAPTCHA v3, IP reputation | `ip_rules`, `bot_audit_logs` | `bot:rate:session:*`, `bot:rate:ip:*`, `bot:block:*`, `bot:captcha:*` |
| 2 | **`catalog`** | Event metadata, tiers, sale windows, **inventory ownership** | `events`, `ticket_tiers`, `tier_inventory` | `catalog:stock:{e}:{t}` |
| 3 | **`queue`** | Virtual waiting room, SSE streaming, HMAC passes, admission control | *none* | `queue:waiting:*` (ZSET), `queue:pass:*`, `queue:passes:*`, `queue:hb:*`, `queue:events:*` (pub/sub) |
| 4 | **`hold`** | Time-bound reservations, atomic stock movement, settle-once restoration | `ticket_holds` | `hold:{token}`, `holdmeta:{token}` |
| 5 | **`payment`** | Stripe integration, idempotency, webhook reconciliation, refunds | `payment_transactions` | `payment:inflight:{holdToken}` |
| 6 | **`order`** | ACID ledger, checkout orchestration, transactional outbox | `orders`, `order_items`, `outbox_events` | *none* |
| 7 | **`notification`** | PDF rendering, email delivery, DLQ replay | `notification_logs` | *none* (RabbitMQ + SMTP) |

### Dependency graph

```
filter  ──► bot
hold    ──► queue, catalog
order   ──► hold, catalog, payment
payment ──( PaymentSettledEvent · webhook only )──► order
order   ──( outbox → RabbitMQ )──► notification
```

Acyclic by construction. `payment` deliberately does **not** call `HoldFacade`: grace extension is
requested by `order`, a decline retains the hold on purpose, and abandonment is handled by the TTL.
Removing that one edge is what keeps the graph verifiable (ADR-005).

---

## 4. Load-bearing safeguards

| Safeguard | Prevents |
| :--- | :--- |
| Atomic Redis Lua reserve (Phase 2+) / conditional `UPDATE` (Phase 1) | Overbooking under concurrency |
| `UNIQUE(hold_token)` on `orders` | One hold producing two orders |
| **Settle-once claim** (`GETDEL holdmeta` / conditional `UPDATE`) | Stock restored 3× by 3 replicas |
| Reserve returns `-2` on a missing counter | Silently resurrecting sold inventory |
| `maxmemory-policy noeviction` | An LRU policy evicting a `TTL = −1` stock key |
| Post-restart stock reconciliation | AOF `everysec` losing a second of decrements |
| Redis Pub/Sub promotion fan-out | Passes lost to a different replica's SSE connection |
| Admission bounded by remaining capacity | Queueing users into a sold-out sale |
| `ZADD NX` | A page refresh sending a user to the back of the line |
| Single-use, revoked queue pass | One session draining a tier |
| Server-side pricing | Client-supplied charge amounts |
| Outbox in the order transaction | Paid orders with no ticket email |
| `FOR UPDATE SKIP LOCKED` | Three replicas publishing the same event |
| `UNIQUE(order_number, kind)`, insert-then-send | Duplicate ticket emails |
| Signed `fsid` cookie; `receiptToken` on lookups | Session spoofing and order-number IDOR |
| Session-first rate limiting | Blocking every user behind one NAT gateway |
| `stock.drift` alarm | Silent inventory divergence |

Every row exists because the first-pass design lacked it. Rationale for each is in
[`00-architecture-decisions.md`](00-architecture-decisions.md).

---

## 5. Deployment

```
                    [ clients ]
                         │
                 [ Nginx  ·  least_conn ]
                 proxy_buffering off (SSE)
                         │
        ┌────────────────┼────────────────┐
   [ app 1 ]        [ app 2 ]        [ app 3 ]      ← stateless, Java 21 virtual threads
        └────────────────┼────────────────┘
        ┌────────────────┼────────────────┐
   [ Redis 7 ]     [ PostgreSQL 16 ]  [ RabbitMQ ]
   primary +        AOF everysec       DLX + DLQ
   Sentinel         noeviction
```

**Single Redis primary with Sentinel, not Cluster.** `hold_reserve.lua` touches keys in different
hash slots and would fail with `CROSSSLOT` on Cluster; keyspace notifications are also per-node.
This workload is a handful of keys at a few hundred thousand ops/s — nowhere near a single primary's
ceiling (ADR-018).

**Nginx must be configured for SSE:** `proxy_buffering off`, `proxy_read_timeout 3600s`,
`proxy_set_header Connection ''`, HTTP/1.1. Buffered responses turn the live queue into a page that
appears frozen.
