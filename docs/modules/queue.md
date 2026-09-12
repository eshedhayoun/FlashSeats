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
| `queue:exhausted:{e}` | String | sale end + retention | derived sold-out marker; deleted the moment stock returns (ADR-035) |
| `queue:promote:{e}` | String | 900 ms | makes the promotion tick a singleton across replicas (ADR-032) |
| `queue:budget` | String | one promotion interval | **the cluster-wide admission allowance** (ADR-049) |
| `queue:events:{e}` | Pub/Sub | — | promotion fan-out to whichever replica holds the SSE connection (ADR-007) |

Every per-buyer key is **scoped by event**. One visitor in two concurrent sales would otherwise have
one promotion overwrite the other (ADR-036). **`queue:budget` is the deliberate exception**, and it is
the opposite kind of thing: one counter every sale and every replica is meant to share, so scoping it
by event would reproduce the per-sale accounting ADR-049 exists to replace.

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
| `tier-availability` | `{tiers:[{tierId, level}]}` | on change — each replica diffs the buckets it last sent (ADR-027) |
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

Once per second, over the open events **in shuffled order**, on whichever replica wins
`queue:promote:{e}`:

```
remaining = CatalogFacade.getRemainingForEvent(e)
if remaining == COUNTER_UNAVAILABLE:  pause this event, promote nobody

trim + re-expire queue:passes:{e}, queue:admissions:{e}, queue:waiting:{e}

if remaining > 0:                          DEL queue:exhausted:{e}
elif no live passes and no live admissions: SETNX queue:exhausted:{e}; publish once; stop

admittable = min(promotionBatchSize,
                 floor(remaining × oversubscribeFactor) − pendingPasses − liveAdmissions)

front    = ZRANGE queue:waiting:{e} 0 admittable-1
granted  = claim(queue:budget, want = |front|)       ← cluster-wide, atomic, fails closed
if granted == 0: promote nobody

for sid in first `granted` of front:
    mint pass → SET queue:pass:{e}:{sid} EX 120
    ZADD queue:passes:{e} <expiry> <sid>
    ZREM queue:waiting:{e} <sid>
    PUBLISH queue:events:{e}
```

**Five things here are load-bearing:**

- **The shuffle.** Every replica reads the open events in the same ascending order and claims the
  shared allowance as it reaches each sale, so a fixed order lets the lowest event id take the whole
  allowance every tick while the others stand still. The per-event batch size cannot save it: once the
  cluster allowance is smaller than `promotionBatchSize`, that cap never binds (ADR-049).

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

**The batch size is per sale; the allowance is per cluster.** ADR-028 derives
`batchSize ≤ hikariMax × 1.5` for a single sale, and this worker loops every open event, so at `E`
concurrent sales the cluster was admitting `R × E × batchSize` per second into one shared pool —
measured at 31.3 s checkout p99 and 202 connections pending. `queue:budget` is claimed first and the
batch size is now the secondary cap (ADR-049).

**The tick needs no database connection**, and that is load-bearing rather than tidy (ADR-051). It
reads the open-event set, the event row and the tier list from `catalog`'s metadata cache and moves
everything else in Redis. Before that, `findOpenEventIds()` was a pooled query once a second — so under
pressure the promoter waited in the same queue as the buyers it existed to admit, one wait measured at
**16 s inside a 1 s tick**. A tick that does not run promotes nobody, a waiting room that does not drain
keeps polling, and the polling is what saturated the pool: the component bounding admission was inside
the loop it was bounding.

Three properties of the claim matter: it is **atomic** (one Lua script, so two replicas promoting two
sales in the same second cannot both read the allowance as untouched), it is **against demand** (the
worker asks for the number of sessions actually at the front, so a quiet sale leaves the rest of the
allowance for sales that can use it), and it **fails closed** (no allowance, no promotion this tick —
a late promotion costs patience, an unbudgeted one costs the pool).

---

## 5. Ordering

`flashseats.queue.ordering` supports two ZSET score strategies:

- **`FIFO`** — default; score is the join timestamp in epoch milliseconds. Explicable, and decided by
  whoever has the lowest RTT.
- **`RANDOM`** — score is a **fresh uniform draw taken at join**, bounded at 2^53 so it stays exactly
  representable as the double a ZSET score is.

**The draw must not be derived from the session id.** Deriving it — `SHA-256(eventId:sessionId)` was
the first implementation — is idempotent *and precomputable*: ids cost nothing to mint, so a bot
generates candidates offline until it holds a low draw and walks to the front deterministically, which
is the automation advantage ADR-024 exists to remove. `ZADD NX` already makes a fresh draw idempotent,
because a rejoin's draw is discarded and the place the first join won is the place that stands.

Open under `RANDOM`, and the reason it is not the default: a later joiner can draw lower, so a waiting
buyer's true position can worsen while `position-update` is clamped monotonic non-increasing (ADR-007).
Closing the draw at a fixed moment is the answer and is not built.

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **The broadcaster does 3 Redis round trips per connection per tick** | Admission `GET`, pass `GET`, waiting `ZRANK`. The exhausted `EXISTS` is now hoisted to once per event per sweep; the remaining three are per session and not yet pipelined. Measured fine at 2,000 VUs on **one** sale; the cost is linear in connections × events, so `docker/scripts/sse-cadence.sh` at `E = 5` is what decides whether it needs the pipeline |
| **No per-event queue metrics** | `flashseats.queue.admissions` and `flashseats.queue.admission.budget.denied` are built and **untagged**, so they answer "is the cluster promoting?" and not "is *this* sale promoting?". Depth and active SSE connections are still unbuilt (`03` §7) |
| **No `Last-Event-ID` replay** | The stream sends live state and heartbeats, but does not replay missed frames after a disconnect |

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
