# Global Standards

> **The conformance target for every module.** These rules are cross-cutting: they are stated once
> here and referenced — never restated, never varied — by the seven module specifications.
>
> During the 2nd-pass rewrite, a module spec is *done* when it conforms to every section below.
> Decisions are justified in [`00-architecture-decisions.md`](00-architecture-decisions.md).

---

## 1. API standard — RFC 7807, not an envelope

Every error response is an RFC 7807 `application/problem+json` document. Spring Boot 4 supports
`ProblemDetail` natively.

**We do not use an `ApiResponse<T>` wrapper.** An envelope fights HTTP rather than using it: it
forces a second unwrap on every client, decouples the payload from the status code, and breaks
caching and conditional requests. Success responses are the resource itself.

### Error shape

```json
{
  "type":     "https://flashseats.dev/problems/hold-expired",
  "title":    "Reservation expired",
  "status":   410,
  "detail":   "Your 5-minute reservation ended. Nothing was charged.",
  "instance": "/api/v1/orders/checkout",
  "code":     "HOLD_EXPIRED",
  "traceId":  "0af7651916cd43dd8448eb211c80319c",
  "retryable": false
}
```

### Fixed extension members

`code` and `traceId` are present on **every** problem response. The rest appear only where the
table says so, and always with these exact names and types — the SPA switches on them.

| Member | Type | Where it appears |
| :--- | :--- | :--- |
| `code` | string | always — the stable machine-readable identifier from §2 |
| `traceId` | string | always — W3C trace id, for support |
| `retryable` | boolean | any failure the client may usefully retry |
| `retryAfterSeconds` | integer | `429`, and `503` from a circuit breaker |
| `attemptsRemaining` | integer | payment declines |
| `expiresAt` | RFC 3339 | hold/admission/pass expiry problems |
| `resumeUrl` | string | 3-D Secure and other continuation flows |
| `violations` | array | `400` validation — `[{field, message}]` |

### Status codes — one meaning each

| Status | Meaning here | Never used for |
| :--- | :--- | :--- |
| `400` | Malformed or invalid input | business rule failures |
| `401` | Missing/invalid session or pass | authorisation failures |
| `403` | Identity known, action forbidden (bot, blacklist) | expired tokens |
| `404` | Resource does not exist | resources you may not see (use `404`, never `403`, to avoid enumeration) |
| `409` | Valid request, conflicting state (sold out, already consumed) | expiry |
| `410` | The thing existed and has expired | anything recoverable |
| `402` | Payment declined | gateway outages |
| `422` | Semantically invalid (quantity over tier max) | syntax errors |
| `429` | Rate limited — always with `retryAfterSeconds` | bot detection |
| `503` | Dependency unavailable or circuit open — always with `retryAfterSeconds` | business failures |

`409` versus `410` is a real distinction, not a nicety: `409` means *try something else*, `410`
means *that opportunity is gone*. The SPA renders different screens.

### Advice structure

- **Each module owns a `@RestControllerAdvice`** for its own exceptions, living in that module's
  `exception` package. A single global advice would import every module's exception types into one
  class and break the boundary `ApplicationModules.verify()` enforces.
- **One global fallback advice** lives in the shared kernel (§8) with `@Order(LOWEST_PRECEDENCE)`.
  It handles bean validation, malformed JSON, and anything unhandled — mapping the last to a bare
  `500` with a `traceId` and **no internal detail**.
- Exceptions never cross module boundaries. A facade throws only exceptions its own module owns.

### URL and versioning

`/api/v1/...`, plural nouns, kebab-case. Admin under `/api/v1/admin/...`, guarded by Spring Security
(`ROLE_ADMIN`) — "Admin Only" in a spec means an enforced role, not a comment.

---

## 2. Canonical error-code registry

One namespace across all modules. `code` values are **stable API contract** — renaming one is a
breaking change.

| Code | Status | Module | Meaning / client action |
| :--- | :--- | :--- | :--- |
| `VALIDATION_FAILED` | 400 | shared | Fix `violations` and resubmit |
| `INTERNAL_ERROR` | 500 | shared | Show `traceId`, offer retry |
| `RATE_LIMITED` | 429 | bot | Back off `retryAfterSeconds` |
| `BOT_VERIFICATION_FAILED` | 403 | bot | Re-run reCAPTCHA |
| `IP_BLOCKED` | 403 | bot | Terminal; contact support |
| `SESSION_INVALID` | 401 | bot | Reload to obtain a fresh `fsid` |
| `EVENT_NOT_FOUND` | 404 | catalog | — |
| `TIER_NOT_FOUND` | 404 | catalog | — |
| `SALE_NOT_OPEN` | 409 | catalog | Show the countdown |
| `SALE_CLOSED` | 409 | catalog | Terminal |
| `INVENTORY_UNAVAILABLE` | 503 | catalog | **Fault** — retry; alarm fires server-side |
| `PREWARM_WINDOW_CLOSED` | 409 | catalog | Admin only |
| `NOT_IN_QUEUE` | 404 | queue | Rejoin |
| `QUEUE_PASS_INVALID` | 401 | queue | Rejoin |
| `QUEUE_PASS_EXPIRED` | 410 | queue | Rejoin |
| `ADMISSION_EXPIRED` | 410 | queue | Rejoin the queue |
| `ADMISSION_REQUIRED` | 401 | queue | Not admitted to the sale |
| `QUEUE_UNAVAILABLE` | 503 | queue | Retry with backoff |
| `SALE_EXHAUSTED` | 409 | queue | Sold out. **Not terminal** — exhaustion is derived from live stock and clears when seats return (ADR-035) |
| `INSUFFICIENT_STOCK` | 409 | hold | Choose another tier |
| `HOLD_NOT_FOUND` | 404 | hold | — |
| `HOLD_EXPIRED` | 410 | hold | Reservation gone; nothing charged |
| `HOLD_ALREADY_SETTLED` | 409 | hold | Already consumed or released |
| `HOLD_LIMIT_EXCEEDED` | 409 | hold | Release the existing hold first |
| `QUANTITY_EXCEEDS_LIMIT` | 422 | hold | Max 6 per order |
| `PAYMENT_DECLINED` | 402 | payment | Retry — see `attemptsRemaining` |
| `PAYMENT_ATTEMPTS_EXHAUSTED` | 402 | payment | Terminal |
| `PAYMENT_ACTION_REQUIRED` | 402 | payment | 3-D Secure — follow `resumeUrl` |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 503 | payment | Circuit open; hold retained, **no payment attempt consumed**, and the retry genuinely works (ADR-034) |
| `DUPLICATE_PAYMENT` | 409 | payment | A charge is already in flight. Do **not** re-enable the pay button; poll `/sale/{id}/state`. Bounded by `stale-pending-seconds` — it can no longer mean "forever" (ADR-034) |
| `WEBHOOK_SIGNATURE_INVALID` | 400 | payment | Gateway only |
| `ORDER_NOT_FOUND` | 404 | order | — |
| `ORDER_ALREADY_CONFIRMED` | 409 | order | Return the existing receipt |
| `CHECKOUT_WINDOW_CLOSED` | 409 | order | Past the 15-minute grace |
| `INSUFFICIENT_TIME_REMAINING` | 409 | order | Too little of the hold left to start a charge that could finish (ADR-030). Nothing charged; the order is left resumable |
| `ORDER_REFUNDED` | 409 | order | A settled charge was refunded because the seats could not be delivered (ADR-012). Distinct from `HOLD_EXPIRED`, whose promise that nothing was charged would be false |
| `NOTIFICATION_LOG_NOT_FOUND` | 404 | notification | Admin only |

---

## 3. Idempotency doctrine

**Every mutating endpoint declares its idempotency key and its guarantee.** A module spec that
introduces a mutating endpoint without stating both is incomplete.

Three surfaces, three mechanisms:

| Surface | Mechanism | Guarantee |
| :--- | :--- | :--- |
| **Client** (button mashing, retries) | A server-side natural key, never a client-chosen string | exactly-once |
| **Gateway** (Stripe) | The client's `idempotencyKey` forwarded as Stripe's `Idempotency-Key` | exactly-once charge |
| **Consumer** (RabbitMQ) | `UNIQUE` constraint + insert-then-act | at-least-once delivery, effectively-once side effect |

Rules:

1. **Anchor on a natural key, not a client token.** Checkout anchors on `orders.hold_token UNIQUE`;
   a client that regenerates its token cannot bypass that. Client tokens are a *fast path*, never
   the guarantee.
2. **The database constraint is the guarantee.** A preceding `SELECT` is a race, not a check.
   Insert first and let the unique violation be the signal.
3. **Replay returns the original result**, not an error, when the operation genuinely completed
   (a confirmed checkout replays as `200` + receipt).
4. **In-flight is `409`, not a wait.** Never block a request waiting for a concurrent duplicate.
5. **Short in-flight TTLs.** 90 s, roughly the gateway timeout — never hours. A crash must not lock
   a key for a day.
6. **A claim is released when the work did not happen.** A claim guards an operation in progress, so
   an operation that did not occur must give it back. The notification claim was permanent: a
   transient SMTP outage dead-lettered the message *and* kept the claim, so replaying it from the
   DLQ acknowledged without sending (ADR-038). Release it with the same conditional `UPDATE` shape —
   `WHERE status = 'DLQ'` — so a send genuinely in progress, or one that succeeded, is untouched.
7. **"In-flight" must have an end.** Every in-flight state needs a rule for when it is no longer in
   flight, or it becomes terminal by accident. A `PENDING` order answered every retry with `409`
   forever, stranding buyers holding live seats behind a charge they never made (ADR-034). Bound it
   by the same TTL that bounds the in-flight guard, and resume past it.
8. **A claim's outcome is a rowcount, not an exception.** `INSERT … ON CONFLICT DO NOTHING`, not
   insert-and-catch. This is not style: a flush that violates a constraint marks the transaction
   rollback-only, so the `catch` block's "already handled, carry on" cannot actually return — it
   throws `UnexpectedRollbackException` at commit and the caller reads it as a failure. The
   notification consumer dead-lettered every redelivered message for exactly this reason (ADR-038).
   The constraint is still the guarantee; only how the answer arrives has changed.

---

## 4. Transaction boundaries

**The rule: a SQL transaction may contain only SQL.**

### Never inside `@Transactional`

- HTTP calls (Stripe, reCAPTCHA, Google)
- SMTP
- RabbitMQ publish or ack
- **Redis writes**
- PDF rendering, or any CPU-heavy work
- `Thread.sleep`, retries, or circuit-breaker calls

A transaction holds row locks and a pooled connection. With virtual threads the pool is the
system's real concurrency limit (§7), so one slow call inside a transaction throttles everything.

### The three patterns

**(a) External call before the transaction.** Checkout charges Stripe *first*, then opens a
transaction to commit the result (ADR-001). The transaction contains only the writes.

**(b) Non-transactional side effects after commit.**

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderConfirmed(OrderConfirmedEvent e) { /* Redis cleanup — best-effort */ }
```

Anything here **must be safe to lose**. Redis hold cleanup qualifies: if it never runs, the key
expires and the expiry handler finds `status = CONSUMED` and correctly does nothing (ADR-019).

**(c) Outbox relay — three short transactions, never one.**

```
tx1:  UPDATE outbox_events SET status='PROCESSING', claimed_at=now()
       WHERE id IN (SELECT id FROM outbox_events WHERE status='PENDING'
                     ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100)
       RETURNING *;                                   -- commit immediately
      publish to RabbitMQ                             -- OUTSIDE any transaction
tx2:  UPDATE outbox_events SET status='PROCESSED', processed_at=now() WHERE id = ANY(?)
```

Publishing inside `tx1` would hold row locks across a network call to the broker. A crash between
`tx1` and `tx2` re-publishes on the next sweep of stale `PROCESSING` rows — at-least-once, which
the consumer's unique constraint absorbs.

The same shape applies to the notification consumer: claim the log row in one short transaction,
render and send outside any transaction, record the outcome in another.

### Authority

**PostgreSQL is the authority for every lifecycle state. Redis holds timers and hot data.** Where
the two disagree, PostgreSQL wins and Redis is repaired. This is what makes ADR-019 work.

---

## 5. Facade contract rules

A facade is the *only* legal cross-module surface. Every one obeys:

1. **Synchronous, in-process.** No HTTP, no messaging. A facade call is a method call.
2. **Never `@Transactional` itself.** The caller owns the transaction boundary; a facade
   participates in whatever is already open. (`consumeHold` is deliberately called from inside the
   order transaction and must join it — ADR-019.)
3. **Records in, records out.** Immutable Java records only. **Never a JPA entity** — a detached
   entity leaks a lazy-loading proxy and a mapping across a boundary that is supposed to be opaque.
4. **Module-owned exceptions only.** `HoldFacade` throws hold exceptions; it never surfaces a
   `DataIntegrityViolationException`.
5. **No `Optional` for absence that is an error.** Throw the module's `*NotFoundException`; reserve
   `Optional` for genuinely optional reads.
6. **One store per call where possible.** A facade that touches PostgreSQL *and* Redis *and* calls
   another facade is doing orchestration and belongs in a service.
7. **Declared in the module's `facade` package**; the implementation is package-private where the
   language allows.
8. **The graph stays acyclic.** Adding an edge requires checking `ApplicationModules.verify()`.

### The one shared write: the inventory counter

Named pattern: **shared counter, single-writer contract.**

- `catalog` **owns** `catalog:stock:{eventId}:{tierId}` and `tier_inventory` — schema, lifecycle,
  seeding, rebuild, reconciliation.
- `hold` is the **sole writer** during a sale, in *both* storages, via exactly two operations
  (`hold_reserve.lua` / `hold_restore.lua` in Phase 2+; one CTE statement in Phase 1).
- Nothing else reads or writes them.

This is a deliberate, bounded exception, not an oversight: the stock decrement and the reservation
that justifies it must be atomic, and splitting them across two modules would open a crash window
that leaks inventory. Documenting it once as a contract is better than pretending it isn't there.

---

## 6. Resilience placement

**Circuit breakers belong only at true external boundaries.** Wrapping an in-process facade call in
a circuit breaker adds latency and a failure mode while protecting nothing.

| Boundary | Breaker | Retry | Fallback |
| :--- | :--- | :--- | :--- |
| Stripe | 50% / 20 calls, 30 s open | 3×, exponential, **transport only** | `503`, hold retained |
| reCAPTCHA | 50% / 20 calls, 30 s open | 1×, 3 s timeout | **fail open**, `degraded=true`, alarm |
| SMTP | none (Rabbit retries) | 3× via broker, 5 s / 30 s / 2 m | DLQ + admin replay |
| PostgreSQL / Redis | none | none | fail fast, `503` |

### Retry classification — the rule that matters

**Retry transport failures. Never retry business failures.**

A card decline is a *correct answer*, not a fault: retrying it three times triples the fraud
signal against the customer's card and changes nothing. Retry on connect/read timeouts, `5xx`, and
broker errors. Never on `4xx`, declines, or validation.

### Fallback semantics

Every fallback declares whether it fails **open** or **closed**:

- **reCAPTCHA fails open** — availability over enforcement, with rate limits as the compensating
  control. A deliberate trade (ADR-011).
- **Inventory fails closed** — a missing stock counter is `503`, never "sold out", and never a
  reseed (ADR-004).

Any new external dependency must state which it is.

---

## 7. Concurrency standards

### Virtual threads

`spring.threads.virtual.enabled=true` carries requests and SSE connections. Two consequences that
are easy to miss:

**1. `synchronized` pins the carrier thread.** On JDK 21 a virtual thread that blocks inside a
`synchronized` block pins its carrier; JEP 491 only fixes this in JDK 24. Under a flash-sale spike
this presents as a throughput collapse that looks like a Redis or database problem.

- Use `ReentrantLock`, never `synchronized`, around anything that can block.
- Audit third-party libraries for `synchronized` on blocking paths. *(This is a direct reason
  Redisson was dropped — ADR-022.)*
- Never call a blocking operation inside a `synchronized` block.

**2. The connection pool becomes the concurrency limit.** Virtual threads remove the thread-count
ceiling, so unbounded work queues on HikariCP instead of the web container. Size the pool
deliberately and alarm on `hikaricp_connections_pending` — that metric, not CPU, is the saturation
signal.

### Locking

- **Distributed locks: `pg_try_advisory_xact_lock(key)`.** Transaction-scoped, released
  automatically on commit or rollback, and impossible to leak. No extra dependency (ADR-022).
- **Row-level contention: conditional `UPDATE`.** `UPDATE … WHERE … AND status='ACTIVE'` with a
  row-count check beats `SELECT FOR UPDATE` then update — one round trip and no lock held across
  application logic.
- **Prefer no lock at all.** Most of this system's concurrency is handled by atomic conditional
  updates and Lua scripts. If a design needs a lock, ask what makes it un-atomic first.

### Scheduled work across replicas

Every `@Scheduled` job runs on **all three replicas**. Each must be one of:

- **Idempotent by claim** — the sweeper and the outbox poller use conditional updates and
  `SKIP LOCKED`; concurrent execution is harmless.
- **Singleton by advisory lock** — the promotion worker takes
  `pg_try_advisory_xact_lock(hash(eventId))` and skips the tick if it cannot get it.

A job that is neither is a bug.

---

## 8. Shared kernel

`com.flashseats.shared` — declared to Spring Modulith as an **open module**, so every module may
depend on it without creating boundary violations.

**May live there:**

- `ProblemDetail` factory and the `ErrorCode` enum from §2
- The global fallback `@RestControllerAdvice`
- `SessionId` — a value type wrapping the verified `fsid`
- `Money` / `AmountCents` and `Currency` value types
- Clock abstraction (`Clock` bean) so timers are testable
- Base pagination and validation annotations

**May never live there:**

- Any entity, repository, or table
- Any business rule, calculation, or policy
- Any DTO shared between exactly two modules — that belongs in the callee's `facade` package
- Anything that would make two modules change together

The test: if adding something to `shared` means two modules must be redeployed in lockstep for a
business change, it does not belong there.

---

## 9. Observability

Metric naming: `flashseats.<module>.<subject>.<unit>`. Every module exposes at minimum its own
error rate by `code`, and the latency of any external call it makes.

Required alarms:

| Metric | Alarm | Why |
| :--- | :--- | :--- |
| `flashseats.stock.drift` | **any non-zero** | inventory accounting has diverged — page |
| `hikaricp_connections_pending` | > 0 sustained | the real saturation signal under virtual threads |
| `flashseats.outbox.lag.seconds` | > 60 | fulfilment is stalling |
| `flashseats.dlq.depth` | > 0 | tickets are not reaching buyers |
| `flashseats.queue.promotion.rate` | 0 while depth > 0 | the queue has stalled |
| `flashseats.payment.decline.ratio` | > 0.2 | gateway or configuration problem |
| `jvm.threads.pinned` | > 0 | virtual-thread pinning (§7) |

`stock.drift` compares the live counter against
`total_capacity − confirmed_sold − active_holds` every 60 s. It is the system's canary.

---

## 10. Module specification template

Every module doc adopts this outline in the 2nd pass, in this order:

```
1. Scope
   1.1 Responsibility  ·  1.2 Forbidden  ·  1.3 Phase
2. Package layout
3. Data
   3.1 PostgreSQL (DDL)  ·  3.2 Redis keys (table: key, type, TTL, owner)
   3.3 State machine (if the module owns lifecycle state)
4. Interfaces
   4.1 REST (method, path, auth, idempotency key, error codes)
   4.2 Facade (full Java signatures)
   4.3 Events published / consumed
5. Transaction boundaries       <- what runs inside @Transactional, what does not
6. Idempotency                  <- key and guarantee per mutating endpoint
7. Resilience                   <- external boundaries, fail open/closed
8. Edge cases                   <- table: case, handling
9. Metrics
10. Changes from previous version
```

Sections 5, 6, 7 and 9 are new in the 2nd pass — their absence is exactly what the audit found.

### Definition of done

- [ ] Every REST endpoint lists auth, idempotency key, and its error codes from §2
- [ ] Every error code appears in the §2 registry
- [ ] Transaction boundaries are explicit; no external call sits inside `@Transactional`
- [ ] Facade signatures are complete Java and obey §5
- [ ] External calls declare fail-open or fail-closed
- [ ] Scheduled jobs state how they behave on three replicas
- [ ] Redis keys list owner and TTL
- [ ] Metrics named per §9
- [ ] No contradiction with any ADR
