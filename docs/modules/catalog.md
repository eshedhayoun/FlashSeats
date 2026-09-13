# Module: `catalog`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.catalog` · **Storage:** PostgreSQL + Redis · **Depends on:** `shared`

---

## 1. Scope

Event metadata, sale windows, and **every movement of the inventory counter**. This module has no
outbound dependency on any other domain module, and it must not acquire one — `catalog` sits at the
bottom of the graph and everything above it reads through `CatalogFacade`.

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `events` | title, venue, event start, **sale window**, status (`DRAFT`/`PUBLISHED`/`PAUSED`/`CANCELLED`) |
| `ticket_tiers` | name, `price_cents`, currency, `total_capacity`, `max_per_order` |

| Redis key | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `catalog:stock:{e}:{t}` | String | **none** | **the live inventory count** |
| `catalog:vouch:{e}` | String | none | which Redis incarnation last derived this event's counters (ADR-046) |

**It also holds an in-process cache of both tables** (ADR-051) — event rows for 1 s, tier lists for
60 s. That is derived state, not owned state: dropping all of it costs a re-read and nothing else. Two
rules make it safe to have at all. The **TTL is the cross-replica invalidation**, because eviction
reaches only the replica that served the operator's call — so the event TTL is the bound on how long a
paused sale can still answer `OPEN` elsewhere, and it is a correctness setting rather than a
performance one. And the **window status is never cached**: it is derived from the row and the clock on
every call, since it flips with no write to evict on.

**There is no copy of the count in PostgreSQL.** `tier_inventory` was dropped in `V7` — it had
become a write-only copy of a number that had moved to Redis, with a stale column named `remaining`
that read exactly like the truth (ADR-046). The database holds the **ledger** the count can be
derived from: `ticket_tiers.total_capacity` minus what `order_items` sold minus what `ticket_holds`
is holding. That derivation is the rebuild, and it lives in `order` — the only module that can
legally read all three tables.

`noeviction` is a **correctness setting**, not tuning. A `TTL = -1` key is not protected from an LRU
policy, and evicting a live counter is the worst failure this system has.

---

## 3. What it exposes

### Endpoints

| Method | Path | Auth |
| :--- | :--- | :--- |
| `GET` | `/api/v1/events` | public — published events only |
| `GET` | `/api/v1/events/{eventId}` | public — the landing page |
| `POST` | `/api/v1/admin/events/{eventId}/prewarm` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/events/{eventId}/pause` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/events/{eventId}/resume` | `ROLE_ADMIN` |

**Availability is a bucket, never a count** (ADR-027): `SOLD_OUT` · `LIMITED` (< 10 %) · `PLENTY` ·
**`UNKNOWN`**. Publishing exact live inventory drives panic-buying and hands scalpers a free feed.

`UNKNOWN` is the one that matters. An unreadable counter was previously clamped to zero with
`Math.max`, which published "we cannot read our own inventory" to every visitor as "this tier is
gone" — ADR-004's failure mode reaching the landing page (ADR-040).

### Facade

Reads: `getEventSummary`, `getWindowStatus`, `getTierSummary`, `findOpenEventIds`,
`findManagedEventIds`, `getRemainingForEvent`, `getLiveCounters`, `getTierCapacities`.
Movement: `tryReserve`, `restore`, `applyRebuild`.

**`findOpenEventIds` and `findManagedEventIds` are not interchangeable.** *Managed* means open **or
paused** — what an operator is still answerable for. Pausing is what an operator does *while*
investigating a counter, so the drift gauge and the Redis-restart guard both need paused events
included; reusing "is it on sale?" to mean "should we still watch it?" meant a paused event's
rolled-back counters were flagged only once someone resumed and started selling from them (ADR-048).

---

## 4. Inventory movement — the four rules

**1. `tryReserve` answers three ways, atomically.** One Lua script, so readable-ness and
sufficiency are decided in the same step:

| Result | Meaning | Becomes |
| :--- | :--- | :--- |
| `RESERVED` | seats taken | carry on |
| `INSUFFICIENT` | genuinely sold out | `409` — try another tier |
| `COUNTER_MISSING` | **no counter at all** | `503`, alarm, locked rebuild |

Recovering that distinction afterwards by re-reading the counter *raced*: a concurrent restore
between the two calls turned a fault into an ordinary "sold out". The Lua return codes stay inside
the repository — `-1` means "sold out" at one layer and "no counter" one layer up, which is the
precise pair of meanings this design spends its effort keeping apart.

**2. Never reseed from `total_capacity` during a live sale.** `prewarm` refuses unless the window is
`UPCOMING`. Seeding an open sale would silently resurrect every ticket already sold (ADR-004).
Recovery during a live sale is a rebuild from the ledger, never a reseed.

**3. `restore` will not create a counter.** `INCRBY` treats a missing key as zero and creates it, so
a bare `INCRBY` would rebuild a lost counter out of whichever hold happened to expire next. The
`EXISTS` guard makes those seats invisible instead — reported by `flashseats.stock.drift` and
returned by an explicit rebuild. Invisible seats are lost revenue a rebuild recovers; phantom seats
are an oversell nothing recovers.

**4. Neither call may run inside a SQL transaction.** Redis does not roll back (ADR-023).

---

## 5. The restart guard

Redis AOF is `appendfsync everysec`, so a restart replays to about a second ago: the `DECRBY`s in
that second are gone while the `ticket_holds` rows they paid for remain. **The counters come back
high**, which is the one direction that oversells.

`catalog:vouch:{e}` records which Redis `run_id` last derived this event's counters from scratch. A
mismatch means selling is refused for that event until a rebuild runs.

**The verdict is derived, never consumed.** The first version stamped a shared key when it fired, so
whichever replica noticed used the signal up and the others sold on. Each replica reaches the same
conclusion independently, and a rebuild performed on one releases the event on all three (ADR-046).

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **No cross-replica cache invalidation** | Pause takes effect on the replica that served it immediately and on the others when their cached row expires — 1 s (ADR-051). A Redis invalidation channel would buy a fraction of a second on a control measured in seconds, and adds a failure mode a TTL does not have. It becomes worth building when an operator can *edit* an event, which is the gap below |
| **No create-event endpoint** | Events are seeded by a `dev`-profile seeder or by `docker/seed/seed.sql`. An operator cannot create a sale through the API |

---

## 7. What it must never do

- Depend on another domain module. It has no outbound edges and must keep none.
- Reseed a counter from `total_capacity` outside the `UPCOMING` window.
- Create a counter in `restore`.
- Let a Lua return code escape the repository.
- Publish a count, or clamp `COUNTER_UNAVAILABLE` into a number.
- **Sum an empty tier list and call it zero.** An event with no tiers is *unknowable* inventory, and
  answering `0` makes the promotion worker mark it exhausted — permanently, since that marker clears
  only when `remaining > 0`. Every event exists before its tiers do, so this is on the normal path.
- Move stock inside a SQL transaction.
- **Cache a window status, or anything else derived from the clock.** Cache the row; derive on read.
- **Cache a miss.** Rows are inserted out of band by the seed scripts, so a remembered "no such event"
  outlives the insert that created it.
- **Serve pre-warm or a rebuild from the cache.** Both write counters derived from the tier list: a
  probably-right list produces a definitely-wrong counter (ADR-004, ADR-046).
- **Load a cache entry inside `computeIfAbsent`.** Blocking JDBC inside `ConcurrentHashMap`'s per-bin
  monitor pins carrier threads (ADR-022, ADR-051).
