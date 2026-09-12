# Module: `queue`

> **Describes what is built.** Where something is planned rather than built it says so. No class
> names: a class list drifts silently, owned state and exposed contract do not
> ([`../../CLAUDE.md`](../../CLAUDE.md), "Updating the docs is part of the change").

**Package:** `com.flashseats.queue` · **Storage:** Redis only · **Depends on:** `catalog`, `shared`

---

## 1. Scope

The virtual waiting room. Orders arrivals by arrival time, streams live positions over SSE, mints
single-use HMAC passes at a rate the rest of the system can absorb, and exchanges those passes for
**admission sessions** that let a buyer browse the sale without losing their place.

**The queue does not prevent overbooking — `hold` and `catalog` do.** It exists so the thousands who
will not get tickets do not all reach checkout at once, and so they find out quickly rather than
slowly. That framing is why admission is bounded by real capacity, and why the queue is
correctness-neutral: delete the whole module and the sale is still correct, just brutal.

---

## 2. What it owns

**PostgreSQL: nothing.** This module has no table and no entity.

| Redis key | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `queue:waiting:{e}` | ZSET, score = arrival ms | sale end + retention | the line. **Never deleted** (ADR-035) |
| `queue:pass:{e}:{sid}` | String, signed | 120 s | the single-use promotion pass |
| `queue:passes:{e}` | ZSET, score = expiry | sale end + retention | live passes, so a count is one `ZCOUNT` |
| `queue:admit:{e}:{sid}` | String, signed | 600 s | proof of admission into the sale (ADR-020) |
| `queue:admissions:{e}` | ZSET, score = expiry | sale end + retention | live admissions, same trick |
| `queue:admission-budget` | ZSET, member = `{eventId}:{sessionId}`, score = expiry | rolling | cluster-wide pending-pass and active-admission budget (ADR-049) |
| `queue:exhausted:{e}` | String | sale end + retention | derived sold-out marker; deleted the moment stock returns (ADR-035) |
| `queue:promote:{e}` | String | 900 ms | makes the promotion tick a singleton across replicas (ADR-032) |
| `queue:events:{e}` | Pub/Sub | — | promotion fan-out to whichever replica holds the SSE connection (ADR-007) |

Every per-buyer key is **scoped by event**. One visitor in two concurrent sales would otherwise have
one promotion overwrite the other (ADR-036).

Nothing here is authoritative. Lose the lot and the sale is still correct — buyers lose their place
in line, which is a fairness failure, not a correctness one.

---

## 3. What it exposes

### Endpoints

| Method | Path | Auth | Notes |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/queue/join` | `fsid` cookie | `202`. Idempotent — rejoining preserves position |
| `GET` | `/api/v1/queue/status` | `fsid` cookie | polling fallback; returns the pass if one was minted |
| `POST` | `/api/v1/queue/admit` | `fsid` + `X-Queue-Pass-Token` | spends the pass, mints the admission session |
| `GET` | `/api/v1/queue/stream` | `fsid` cookie | SSE, 1 h timeout |

`eventId` arrives in the **body** on both `POST`s, per `FE_SPEC.md` §2.

### SSE frames

| Frame | Payload | Cadence |
| :--- | :--- | :--- |
| `position-update` | `{position, aheadOfYou, estWaitSeconds}` | 2 s, **clamped monotonic non-increasing** |
| `queue-promoted` | `{passToken, expiresInSeconds}` | on promotion |
| `sale-exhausted` | `{soldOutAt}` | when derived — **not terminal**, it un-derives if stock returns |
| `sale-closed` | `{closedAt}` | terminal; the stream is completed |
| *(comment)* | `:hb` | 15 s |

`estWaitSeconds` is `null` when unknown, never a `-1` sentinel. Every frame carries an incrementing
`id:` so a reconnect can send `Last-Event-ID`.

### Facade

Three methods, all read-or-revoke, none transactional:

- `verifyAdmission(token, sessionId, eventId)` — signature **and** live Redis key. Used by `hold`.
- `revokeAdmission(sessionId, eventId)` — called by `order` from `AFTER_COMMIT` once an order confirms.
- `getQueueState(sessionId, eventId)` — read-only rehydration for `saleflow` (ADR-025).

The pass is never exposed. It is minted, published and spent entirely inside this module.

---

## 4. The promotion tick

Once per second, per open event, on whichever replica wins `queue:promote:{e}`:

```
remaining = CatalogFacade.getRemainingForEvent(e)
if remaining == COUNTER_UNAVAILABLE:  pause this event, promote nobody

trim + re-expire queue:passes:{e}, queue:admissions:{e}, queue:waiting:{e}

if remaining > 0:                          DEL queue:exhausted:{e}
elif no live passes and no live admissions: SETNX queue:exhausted:{e}; publish once; stop

admittable = min(promotionBatchSize,
                 floor(remaining × oversubscribeFactor) − pendingPasses − liveAdmissions)

for sid in ZRANGE queue:waiting:{e} 0 admittable-1:
    mint pass → SET queue:pass:{e}:{sid} EX 120
    ZADD queue:passes:{e} <expiry> <sid>
    ZREM queue:waiting:{e} <sid>
    PUBLISH queue:events:{e}
```

**Four things here are load-bearing:**

- **`ZADD NX` on join.** A plain `ZADD` *updates* the score, so a refresh or a double-click sends the
  buyer to the back of the line (ADR-008).
- **Nobody is ever evicted.** An abandoned entry reaches the front, is promoted, never claims its
  pass, and that pass expires in 120 s — capacity returns on its own. Evicting on a missed heartbeat
  deleted live buyers during an ordinary Wi-Fi → cellular handover (ADR-026).
- **Exhaustion is derived, never destructive.** The waiting ZSET is left intact: the trigger is a
  live inventory read, and a released hold makes it wrong seconds later (ADR-035).
- **`PUBLISH` is what reaches the browser.** The promoter runs on one replica; the buyer's emitter
  lives in another's heap. Without fan-out, roughly two-thirds of promotions vanish on three
  replicas — and the bug is invisible on one (ADR-007).

**The batch size is per sale, and the global budget is the first limit.**
ADR-028 derives `batchSize ≤ hikariMax × 1.5` for a single sale. This worker loops every open event
and applies the cap per event. ADR-049 additionally reserves from `queue:admission-budget` before
issuing a pass, so concurrent sales draw from one cluster-wide occupancy limit. Expired reservations
are removed atomically by the reserve script; completed admissions release their member explicitly.

---

## 5. Ordering

**FIFO by arrival millisecond, and not configurable.** `flashseats.queue.ordering` was declared in
`application.properties` with no backing field and was silently ignored; the property is gone.

ADR-024 records the case for a randomized draw — arrival-millisecond FIFO rewards whoever has the
lowest RTT and the most aggressive automation — and the change is one line, the ZSET score. It is
**specified, not built.**

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **The broadcaster does 4 Redis round trips per connection per tick** | Admission `GET`, pass `GET`, exhausted `EXISTS`, waiting `ZRANK`. The `EXISTS` is per *event* and is being re-read per *session*. Measured fine at 2,000 VUs on **one** sale; the cost is linear in connections × events, so that evidence does not carry to `E = 5` |
| **The emitter registry is a flat map** | Keyed on session id alone, so "sessions watching event X" streams the whole map. `O(connections × events)` per tick |
| **No queue metrics** | Depth, promotion rate and active SSE connections are all specified in `03` §7 and none is built |
| **`tier-availability` frame** | ADR-027 specifies pushing per-tier availability into the waiting room. Not built |

---

## 7. What it must never do

- Read or write inventory. `remaining` comes from `CatalogFacade`; a `COUNTER_UNAVAILABLE` pauses
  promotion rather than guessing.
- Create holds, take payment, or write orders.
- Touch PostgreSQL.
- Delete `queue:waiting:{e}`. It expires with the sale; nothing deletes it (ADR-035).
- Reintroduce a liveness key. `queue:hb:{sid}` was deleted in Pass 7 — written on every join and
  every status poll, read by nobody. ADR-026's rule needs no key: the queue drains by promotion and
  nothing is ever evicted. Liveness telemetry, if ever wanted, is a Micrometer counter.
- Treat a Lua or facade sentinel as a count. `COUNTER_UNAVAILABLE` is a fault, never "sold out"
  (ADR-004, ADR-040).
