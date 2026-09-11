# Module: `queue`

> **Status:** aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md) and
> [`../05-global-standards.md`](../05-global-standards.md). Structural rewrite to the §10 template
> is pending.

**Package:** `com.flashseats.queue` · **Phase:** 2 · **Storage:** Redis only

---

## 1. Scope

The virtual waiting room. Orders arrivals, streams live positions over SSE, mints single-use HMAC
passes at a rate the rest of the system can absorb, and exchanges those passes for **admission
sessions** that let a buyer browse the sale without losing their place.

**The queue does not prevent overbooking — `hold` does.** The queue exists so the thousands who will
not get tickets do not all hit checkout at once, and so they find out quickly rather than slowly.
That framing matters: it is why admission is bounded by real capacity, and why the queue is
correctness-neutral enough to be skipped entirely in Phase 1.

**Forbidden:** reading inventory tables, creating holds, processing payments, writing orders.
Zero PostgreSQL state.

---

## 2. Package layout

```
com.flashseats.queue
├── controller   QueueController (join, status, SSE stream)
├── service      QueueService, PromotionWorker, PassTokenService (HMAC),
│                QueuePubSubListener        ← cross-replica SSE fan-out
├── facade       QueueFacade + impl
├── repository   QueueRedisRepository (ZSET ops)
├── model        QueueEntry, PassTokenPayload
├── dto          QueueStatusResponse, QueuePositionDTO
└── event        UserPromotedEvent, SaleExhaustedEvent
```

---

## 3. Redis keys

| Key | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `queue:waiting:{eventId}` | ZSET — score `epochMillis`, member `sid` | until sale end | the line |
| `queue:pass:{sid}` | String — signed token | **120 s** | single-use pass; exchanged at `/queue/admit` |
| `queue:admit:{eventId}:{sid}` | String — signed token | **600 s** | **admission session** — you are inside the sale (ADR-020) |
| `queue:admissions:{eventId}` | ZSET — score = expiry | until sale end | live-admission count for admission control |
| `queue:passes:{eventId}` | ZSET — score = pass expiry | until sale end | live-pass count for admission control |
| `queue:hb:{sid}` | String | **90 s** | **advisory only** — abandonment metric. Never used for eviction (ADR-026) |
| `queue:events:{eventId}` | Pub/Sub channel | — | promotion fan-out across replicas (ADR-007) |

The two ZSETs exist so live counts can be taken with `ZCOUNT … <now> +inf` — self-cleaning, no leak,
and no separate counter to decrement. Nothing here is authoritative: the queue holds no state that
matters after the sale.

---

## 4. Join

`POST /api/v1/queue/join`

1. `bot` filter: `fsid` cookie, session + IP buckets, reCAPTCHA ≥ 0.5.
2. `CatalogFacade.getWindowStatus(eventId) == OPEN`, else `409 SALE_NOT_OPEN`. v1 never checked
   this, so joins before the sale silently succeeded (ADR-016).
3. Place in line:

```redis
# score = epochMillis          when flashseats.queue.ordering = FIFO   (default)
# score = uniform random draw  when flashseats.queue.ordering = RANDOM (ADR-024)
ZADD queue:waiting:{eventId} NX <score> <sessionId>
SET  queue:hb:{sessionId} 1 EX 90        # advisory only (ADR-026)
```

**Ordering is configurable.** FIFO by arrival millisecond is intuitive and stays the default, but it
rewards whoever has the lowest network latency and the most aggressive automation — everyone fires
at `t = 0.000` and RTT decides the winner. Ticketmaster Verified Fan, SNKRS and DICE have largely
moved to a randomized draw for exactly this reason. The change is one line: the ZSET score.

**`NX` is essential.** Plain `ZADD` *updates* an existing member's score, so a page refresh or a
double-click on "Join Sale" reset the timestamp and sent the user to the **back** of the line —
directly contradicting v1's own stated FIFO fairness guarantee (ADR-008).

---

## 5. SSE stream

`GET /api/v1/queue/stream`

| Event | Payload | Cadence |
| :--- | :--- | :--- |
| `position-update` | `{position, aheadOfYou, estWaitSeconds}` | 2 s — **monotonic non-increasing** |
| `queue-promoted` | `{passToken, expiresInSeconds: 120}` | on promotion — client immediately calls `/queue/admit` |
| `sale-exhausted` | `{soldOutAt}` | terminal |
| `tier-availability` | `{tiers:[{tierId, level}]}` | on bucket change (ADR-027) |
| `sale-closed` | `{saleEndTime}` | terminal |
| *(comment)* | `:hb` | 15 s |

Every frame carries an incrementing `id:`; reconnects send `Last-Event-ID`. If the stream cannot be
established, the client polls `GET /api/v1/queue/status`, which returns the same state **including
a pass if one was already minted** — so a promotion is never lost to a dead socket.

**Nginx must be configured for SSE:** `proxy_buffering off`, `proxy_read_timeout 3600s`,
`proxy_set_header Connection ''`, HTTP/1.1. With default buffering the user sees a frozen page.

Emitters are held in a per-replica registry keyed by `sessionId`, with `onCompletion` /
`onTimeout` / `onError` all removing the entry and clearing the heartbeat key.

---

## 6. Promotion

Once per second per active event:

```
pendingPasses  = ZCOUNT queue:passes:{eventId}     <now> +inf
liveAdmissions = ZCOUNT queue:admissions:{eventId} <now> +inf
admittable = min(batchSize,
                 floor(remainingStock × oversubscribeFactor)
                   − pendingPasses − liveAdmissions)     -- factor defaults to 1.5 (ADR-020)

if remainingStock == 0 and pendingPasses == 0 and liveAdmissions == 0:
    PUBLISH sale-exhausted ; drain the ZSET ; stop
if admittable <= 0: skip this tick

for sid in ZRANGE queue:waiting:{eventId} 0 admittable-1:
    # NEVER skip or ZREM for a missing heartbeat. A Wi-Fi -> cellular handover
    # routinely exceeds any heartbeat TTL; evicting on it deletes live buyers
    # from the line through no fault of their own (ADR-026).
    passToken = HMAC-SHA256({eventId, sid, exp: now+120s, nonce}, secret)
    SET  queue:pass:{sid} <passToken> EX 120
    ZADD queue:passes:{eventId} <now+120s> <sid>
    ZREM queue:waiting:{eventId} <sid>
    PUBLISH queue:events:{eventId} {sid, passToken}

# The promotion worker is a @Scheduled job and therefore runs on ALL replicas.
# It takes pg_try_advisory_xact_lock(hash('promote', eventId)) and skips the tick
# if it cannot acquire it — singleton by lock (05-global-standards.md section 7).
```

Two load-bearing details:

**Admission is bounded by real capacity.** v1 promoted users unconditionally, so buyers waited
twenty minutes to receive a `409 INSUFFICIENT_STOCK`. Now the queue stops admitting when there is
nothing left to admit them to, and says so (ADR-008).

**`PUBLISH` is how the pass reaches the browser.** The promoter is a `@Scheduled` job running on one
replica; the `SseEmitter` lives in another replica's heap. Every replica subscribes to
`queue:events:{eventId}` and delivers to its own local emitters. Without this, behind three
round-robin replicas roughly **two-thirds of promotions silently vanish** — and the bug is invisible
on a single instance, which is exactly why it must be tested with ≥ 2 replicas (ADR-007).

`remainingStock` comes from `CatalogFacade.getRemaining()`. If it returns `-1` (counter
unavailable), promotion pauses rather than guessing.

### Batch size is bounded by the connection pool, not just by inventory (ADR-028)

```
promotionBatchSize ≤ hikariMaxPoolSize × 1.5        # 30 → 45
```

Admission control bounds admission by *inventory*; this bounds it by *capacity to serve*. They are
different limits and both apply. A tier with 5,000 remaining would otherwise admit 5,000 buyers into
a checkout path backed by 30 database connections — and under virtual threads nothing errors, it
just queues on HikariCP while p99 collapses. Alarm on `hikaricp_connections_pending`.

### Abandonment and wait estimates (ADR-026)

The queue **drains by promotion, never by eviction**. An abandoned entry reaches the front, is
promoted, never claims its pass, and the pass expires in 120 s — capacity returns on its own, and the
1.5× oversubscribe factor already prices in non-conversion.

`estWaitSeconds` is therefore computed from the **measured drain rate** (`ZCARD` delta over a
sliding 30 s window), not `position × assumedServiceTime`. Measuring the real rate accounts for
abandonment implicitly; eviction only ever approximated it.

---

## 7. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `POST` | `/api/v1/queue/join` | public + reCAPTCHA |
| `POST` | `/api/v1/queue/admit` | `X-Queue-Pass-Token` + `fsid` — exchanges the pass for a 600 s admission session |
| `GET` | `/api/v1/queue/status` | public — includes the pass if minted |
| `GET` | `/api/v1/queue/stream` | public — SSE |

```java
public interface QueueFacade {
    /** HMAC signature + live queue:admit:{eventId}:{sid}. Used by `hold` before reserving. */
    boolean verifyAdmission(String admissionToken, String userSessionId, long eventId);

    /** Called by `order` from AFTER_COMMIT once an order reaches CONFIRMED. */
    void revokeAdmission(String userSessionId, long eventId);

    /** Read-only rehydration for saleflow (ADR-025). */
    QueueStateDTO getQueueState(String userSessionId, long eventId);
}
```

`verifyPassToken` / `revokePassToken` are no longer facade methods — the pass is consumed inside
this module at `POST /api/v1/queue/admit`, so no other module ever sees it.

In v1 `revokePassToken` existed but **no flow called it**: a pass stayed valid for its full lifetime
and could mint unlimited holds, enough for one promoted session to drain a tier. The pass is now
single-use by construction, spent the moment it becomes an admission session (ADR-006, ADR-020).

**Events:** `UserPromotedEvent(sid, eventId, at)` · `SaleExhaustedEvent(eventId, at)` — monitoring
only.

---

## 8. Edge cases

| Case | Handling |
| :--- | :--- |
| Refresh mid-queue | `ZADD NX` preserves position; SSE reconnects with `Last-Event-ID` |
| Tab closed after promotion | Pass expires in 120 s, unused. No stock was touched |
| **Wi-Fi → cellular handover mid-queue** | Position preserved: no eviction (ADR-026), `fsid` cookie survives, SSE reconnects with `Last-Event-ID`. A promotion during the gap is recovered from `/queue/status` |
| Buyer's tier sells out while they wait | `tier-availability` frame marks it `SOLD_OUT` in the waiting room, before admission (ADR-027) |
| 10,000 arrive at the front at once | Batch size capped by the pool (ADR-028); the rest keep their place |
| Buyer wants a different tier | Release the hold; the **admission session survives**, so no re-queue (ADR-020) |
| Admission expires while browsing | `410 ADMISSION_EXPIRED`; rejoin the queue |
| Position jumps backwards after evictions | Clamped monotonic non-increasing before it is sent |
| Promotion while SSE is down | Pass is in Redis; `/queue/status` returns it on reconnect |
| Promoter on replica A, SSE on replica B | Pub/Sub fan-out (ADR-007) |
| Abandoned entries inflating estimates | Estimates come from the measured drain rate, not from position × service time (ADR-026) |
| Sold out while users wait | `sale-exhausted`, queue drains |
| Sale window closes | `sale-closed`, queue drains |
| Redis down | `503 QueueUnavailable`; SSE closes; client retries with backoff |
| Forged pass token | HMAC verification fails → `403` |
| Replayed pass after use | Key deleted at `/queue/admit` → `403` |
| Stock counter unavailable | Promotion pauses rather than over-admitting |

**Exceptions:** `InvalidQueueToken` 403 · `NotInQueue` 404 · `SaleNotOpen` 409 ·
`QueueUnavailable` 503.

---

## 9. Changes from v1

1. `ZADD NX` — a refresh no longer costs the user their place (ADR-008).
2. Redis Pub/Sub promotion fan-out — v1 would have dropped most promotions on a 3-replica
   deployment (ADR-007).
3. Admission bounded by `remainingStock − livePasses`; `sale-exhausted` / `sale-closed` events
   (ADR-008).
4. Pass TTL 300 s → **120 s**, single-use, revoked on first hold (ADR-006).
5. `queue:passes:{eventId}` ZSET added for self-cleaning live-pass counting.
6. `queue:hb:{sid}` heartbeat added (later demoted to advisory-only by ADR-026).
7. Sale-window check on join (ADR-016).
8. SSE heartbeat, `Last-Event-ID` reconnect, polling fallback, and required Nginx settings
   documented.
9. "Position #1" prose replaced with the batch-promotion reality it always was.

### Added in the 2nd pass

10. **Admission sessions** (600 s) between pass and hold — the industry-standard middle tier
    (ADR-020). `POST /api/v1/queue/admit` added.
11. Admission control counts admissions and applies a **1.5× oversubscribe factor**, because
    hold-to-order conversion is well under 100 % (ADR-020).
12. **Configurable ordering**: `FIFO` (default) or `RANDOM`. Arrival-millisecond FIFO rewards the
    lowest-latency bot; a random draw is what modern high-demand drops use (ADR-024).
13. Position clamped **monotonic non-increasing** — evictions ahead of you must never make the
    number go up.
14. Promotion worker declared **singleton by advisory lock** — it is `@Scheduled` and would
    otherwise run on all three replicas.
15. `getQueueState` added for `saleflow` rehydration (ADR-025).

### Added in the 3rd pass

16. **Eviction on missing heartbeat removed** — it deleted live buyers whose network handover
    exceeded 30 s. The queue drains by promotion (ADR-026).
17. Heartbeat TTL 30 s → 90 s, refreshed by any request, and demoted to metrics-only.
18. `estWaitSeconds` now derived from measured drain rate.
19. `tier-availability` SSE frame so buyers learn their tier sold out **while waiting** (ADR-027).
20. Batch size explicitly bounded by `hikariMaxPoolSize × 1.5` (ADR-028).
