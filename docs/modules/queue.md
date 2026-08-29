# Module: `queue`

> **Status:** first-pass correction. Aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md).
> A detailed second pass is planned before implementation.

**Package:** `com.flashseats.queue` · **Phase:** 2 · **Storage:** Redis only

---

## 1. Scope

The virtual waiting room. Orders arrivals by millisecond, streams live positions over SSE, and mints
single-use HMAC passes at a rate the rest of the system can absorb.

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
| `queue:pass:{sid}` | String — signed token | **120 s** | active pass (ADR-006) |
| `queue:passes:{eventId}` | ZSET — score = pass expiry | until sale end | live-pass count for admission control |
| `queue:hb:{sid}` | String | 30 s | liveness; lets abandoned entries be evicted |
| `queue:events:{eventId}` | Pub/Sub channel | — | promotion fan-out across replicas (ADR-007) |

`queue:passes` exists so `livePasses` can be counted with
`ZCOUNT queue:passes:{eventId} <now> +inf` — self-cleaning, no leak, no separate counter to
decrement.

---

## 4. Join

`POST /api/v1/queue/join`

1. `bot` filter: `fsid` cookie, session + IP buckets, reCAPTCHA ≥ 0.5.
2. `CatalogFacade.getWindowStatus(eventId) == OPEN`, else `409 SALE_NOT_OPEN`. v1 never checked
   this, so joins before the sale silently succeeded (ADR-016).
3. Place in line:

```redis
ZADD queue:waiting:{eventId} NX <epochMillis> <sessionId>
SET  queue:hb:{sessionId} 1 EX 30
```

**`NX` is essential.** Plain `ZADD` *updates* an existing member's score, so a page refresh or a
double-click on "Join Sale" reset the timestamp and sent the user to the **back** of the line —
directly contradicting v1's own stated FIFO fairness guarantee (ADR-008).

---

## 5. SSE stream

`GET /api/v1/queue/stream`

| Event | Payload | Cadence |
| :--- | :--- | :--- |
| `position-update` | `{position, aheadOfYou, estWaitSeconds}` | 2 s |
| `queue-promoted` | `{passToken, expiresInSeconds: 120}` | on promotion |
| `sale-exhausted` | `{soldOutAt}` | terminal |
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
livePasses = ZCOUNT queue:passes:{eventId} <now> +inf
admittable = min(batchSize, remainingStock − livePasses)

if remainingStock == 0 and livePasses == 0:
    PUBLISH sale-exhausted ; drain the ZSET ; stop
if admittable <= 0: skip this tick

for sid in ZRANGE queue:waiting:{eventId} 0 admittable-1:
    if not EXISTS queue:hb:{sid}: ZREM ; continue        -- abandoned
    passToken = HMAC-SHA256({eventId, sid, exp: now+120s, nonce}, secret)
    SET  queue:pass:{sid} <passToken> EX 120
    ZADD queue:passes:{eventId} <now+120s> <sid>
    ZREM queue:waiting:{eventId} <sid>
    PUBLISH queue:events:{eventId} {sid, passToken}
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

---

## 7. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `POST` | `/api/v1/queue/join` | public + reCAPTCHA |
| `GET` | `/api/v1/queue/status` | public — includes the pass if minted |
| `GET` | `/api/v1/queue/stream` | public — SSE |

```java
public interface QueueFacade {
    /** HMAC signature + live queue:pass:{sid}. Used by hold before reserving. */
    boolean verifyPassToken(String passToken, String userSessionId, long eventId);

    /** Single-use enforcement: called by hold immediately after a successful reservation.
     *  Deletes queue:pass:{sid} and removes the entry from queue:passes:{eventId}. */
    void revokePassToken(String userSessionId);
}
```

`revokePassToken` existed in v1 but **no flow ever called it**. A pass therefore remained valid for
its full lifetime and could mint unlimited holds — enough for one promoted session to drain a tier.
It is now invoked on the first successful hold, and the TTL dropped from 300 s to 120 s so it no
longer shadows the hold window (ADR-006).

**Events:** `UserPromotedEvent(sid, eventId, at)` · `SaleExhaustedEvent(eventId, at)` — monitoring
only.

---

## 8. Edge cases

| Case | Handling |
| :--- | :--- |
| Refresh mid-queue | `ZADD NX` preserves position; SSE reconnects with `Last-Event-ID` |
| Tab closed after promotion | Pass expires in 120 s, unused. No stock was touched |
| Promotion while SSE is down | Pass is in Redis; `/queue/status` returns it on reconnect |
| Promoter on replica A, SSE on replica B | Pub/Sub fan-out (ADR-007) |
| Abandoned entries inflating estimates | `queue:hb:{sid}` heartbeat; lazy `ZREM` during promotion |
| Sold out while users wait | `sale-exhausted`, queue drains |
| Sale window closes | `sale-closed`, queue drains |
| Redis down | `503 QueueUnavailable`; SSE closes; client retries with backoff |
| Forged pass token | HMAC verification fails → `403` |
| Replayed pass after use | Key deleted by `revokePassToken` → `403` |
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
6. `queue:hb:{sid}` heartbeat so wait estimates stay honest.
7. Sale-window check on join (ADR-016).
8. SSE heartbeat, `Last-Event-ID` reconnect, polling fallback, and required Nginx settings
   documented.
9. "Position #1" prose replaced with the batch-promotion reality it always was.
