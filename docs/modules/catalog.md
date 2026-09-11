# Module: `catalog`

> **Status:** aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md) and
> [`../05-global-standards.md`](../05-global-standards.md). Structural rewrite to the §10 template
> is pending.

**Package:** `com.flashseats.catalog` · **Phase:** 1 · **Storage:** PostgreSQL + Redis

---

## 1. Scope

Owns event metadata, ticket tiers, sale windows, and **all inventory**. Serves high-volume browse
reads, publishes the server clock for the pre-sale countdown, seeds Redis counters before a sale,
and owns the recovery procedure when those counters are lost.

**Forbidden:** creating holds, processing payments, managing queue positions, writing orders.

**Inventory ownership, precisely.** `catalog` owns `catalog:stock:{eventId}:{tierId}` outright and
is the only module that touches it. `hold` moves stock by calling `CatalogFacade.tryReserve` /
`restore`, which run the two scripts here; earlier drafts had `hold` execute them against catalog's
key and called that the one shared key in the system. It no longer exists (ADR-046).

---

## 2. Package layout

```
com.flashseats.catalog
├── controller   # public browse endpoints, admin prewarm/rebuild
├── service      # metadata reads, window derivation, prewarm, stock rebuild
├── facade       # CatalogFacade (+ Impl) — the only cross-module surface
├── repository   # JPA repositories, Redis stock template
├── model        # Event, TicketTier, TierInventory, EventWindowStatus
├── dto          # EventDetailDTO, TierSummaryDTO, TierAvailabilityDTO
└── event        # EventPrewarmedEvent, StockRebuiltEvent
```

---

## 3. Schema

### `events`

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | `BIGSERIAL` PK | |
| `title` | `VARCHAR(255)` NOT NULL | |
| `description` | `TEXT` | |
| `venue_name` | `VARCHAR(255)` NOT NULL | in the ticket PDF |
| `event_start_time` | `TIMESTAMPTZ` NOT NULL | **added** — the PDF and email need it |
| `sale_start_time` | `TIMESTAMPTZ` NOT NULL | |
| `sale_end_time` | `TIMESTAMPTZ` NOT NULL | |
| `status` | `VARCHAR(32)` NOT NULL | `DRAFT`, `PUBLISHED`, `CANCELLED` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` NOT NULL | |

### `ticket_tiers`

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | `BIGSERIAL` PK | |
| `event_id` | `BIGINT` FK → `events(id)` NOT NULL | |
| `tier_name` | `VARCHAR(100)` NOT NULL | |
| `price_cents` | `BIGINT` NOT NULL `CHECK (>= 0)` | |
| `currency` | `CHAR(3)` NOT NULL DEFAULT `'USD'` | **added** (ADR-013) |
| `total_capacity` | `INT` NOT NULL `CHECK (> 0)` | immutable once the sale opens |
| `max_per_order` | `INT` NOT NULL DEFAULT `6` | **added** (ADR-017) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` NOT NULL | |

### `tier_inventory` — **dropped in `V7__redis_inventory.sql`**

It was the live counter in Phase 1, mutated by one row-locked conditional `UPDATE`. Redis holds that
count now, and the table became write-only: pre-warm and rebuild wrote it, nothing read it, and the
rebuild derives its numbers from `ticket_tiers` / `order_items` / `ticket_holds` without consulting
it. A write-only table named `tier_inventory` with a column named `remaining` reads exactly like the
source of truth it is not, so it went (ADR-046).

**There is no copy of the count in PostgreSQL.** The database holds what the count is *derived
from*; that derivation is the rebuild below. Losing a key is a fault to repair from the ledger, and
never a reason to guess.

Dropping it cost V1's `CHECK (remaining >= 0)`, described there as the database-level guarantee that
an oversell could not be persisted. It guarded a number nobody read. The guarantee is now
`stock_reserve.lua` refusing to decrement below the requested quantity in one atomic step, watched
by `flashseats.stock.drift`.

### Window status — derived, never stored

| `windowStatus` | Condition |
| :--- | :--- |
| `UPCOMING` | `status = PUBLISHED` and `now < sale_start_time` |
| `OPEN` | `status = PUBLISHED` and `sale_start_time ≤ now < sale_end_time` |
| `CLOSED` | `status ≠ PUBLISHED`, or `now ≥ sale_end_time` |

Gates: `queue/join` requires `OPEN`; `POST /holds` requires `OPEN`; `orders/checkout` allows `OPEN`
or `CLOSED` within 15 min of `sale_end_time` (ADR-016).

---

## 4. Redis

| Key | Type | TTL | Notes |
| :--- | :--- | :--- | :--- |
| `catalog:stock:{eventId}:{tierId}` | String (integer) | none | **the** live remaining count; `noeviction` required |
| `catalog:vouch:{eventId}` | String (`run_id`) | none | which Redis instance last derived this event's counters from scratch (ADR-046) |
| `catalog:meta:{eventId}` | String (JSON) | 60 s | browse-read cache — **not built** |

**`maxmemory-policy noeviction` is mandatory.** A `TTL = −1` key is not protected from an LRU
eviction policy; losing a stock counter mid-sale is the worst failure the system has.

### Pre-warm

```
if windowStatus != UPCOMING: reject 409 PREWARM_WINDOW_CLOSED
for each tier:  SETNX catalog:stock:{eventId}:{tierId} <total_capacity>
SET catalog:vouch:{eventId} <current run_id>
```

`SETNX` makes a repeated trigger a no-op. The `UPCOMING` gate is the important part: once the sale
is `OPEN`, seeding from `total_capacity` would resurrect every ticket already sold (ADR-004).

Pre-warm vouches for the event because its counters are sound by construction: nothing has been sold
from a sale that has not opened, so they owe nothing to whatever a restart may have lost.

### Missing counter = fault, not cache miss

**The previous version of this document specified falling back to `total_capacity` and
repopulating on a cache miss. That was the single most dangerous line in the design.** A Redis
eviction, cold restart, or `FLUSHDB` mid-sale would have silently restored the entire inventory.

Correct behaviour while `OPEN`:

1. `stock_reserve.lua` returns `-2`; the API returns `503 INVENTORY_UNAVAILABLE`; the alarm fires.
2. Browse reads report `UNKNOWN` — never `SOLD_OUT`. Clients render it neutrally and keep the tier
   selectable (ADR-040). There is no longer a stale column to degrade to, and inventing one would be
   worse than saying so (ADR-046).
3. Recovery is an explicit rebuild.

### Rebuild — `POST /api/v1/admin/events/{id}/rebuild-stock`

```sql
remaining = tt.total_capacity
  - COALESCE((SELECT SUM(oi.quantity) FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
               WHERE oi.tier_id = tt.id AND o.status = 'CONFIRMED'), 0)
  - COALESCE((SELECT SUM(th.quantity) FROM ticket_holds th
               WHERE th.tier_id = tt.id AND th.status = 'ACTIVE'), 0)
```

No double counting: a `PENDING` order still has an `ACTIVE` hold; a `CONFIRMED` order's hold is
`CONSUMED`. Writes the counters and `catalog:vouch:{eventId}`.

**Every `ACTIVE` hold counts, including ones already past `expires_at`.** An expired hold the sweeper
has not reached still owns its seats and will have them restored, so filtering it out means nobody
subtracts them now and the sweeper hands them back a moment later — the same seats counted twice
(ADR-046).

**The ledger is read twice**, a settling window apart, and the smaller result is written. A reserve
decrements Redis just before its hold row commits, so one snapshot can miss a hold that is seconds
from existing and write a count that is too high — an oversell produced by the repair itself.

**It is served by `order`, not `catalog`.** The ledger spans three modules' tables and `order` is the
only module permitted to read all three. Putting it here would give `catalog` its first outbound
edge and cycle the graph (ADR-046).

The advisory lock is a PostgreSQL **transaction-scoped** one, `pg_try_advisory_xact_lock`: released
automatically, impossible to leak, no extra dependency. A second rebuild is refused with
`503 STOCK_REBUILD_IN_PROGRESS` rather than interleaved. Redisson was dropped in ADR-022 once this
was its only remaining use.

**Mandatory after any Redis restart** — AOF `everysec` can lose a second of `DECRBY`s, which reads
as inventory that does not exist. `StockEpoch` enforces that rather than trusting anyone to remember
it: an event whose `catalog:vouch:` marker names a different instance than the one running refuses
holds until it is rebuilt (ADR-046).

---

## 5. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `GET` | `/api/v1/events` | public |
| `GET` | `/api/v1/events/{eventId}` | public — `windowStatus`, `serverTime`, **bucketed** availability |
| `POST` | `/api/v1/admin/events/{eventId}/prewarm` | admin — `UPCOMING` only |
| `POST` | `/api/v1/admin/events/{eventId}/rebuild-stock` | admin — **served by `order`** (ADR-046) |
| `POST` | `/api/v1/admin/events/{eventId}/pause` | admin — halt promotions and new holds |

```java
public interface CatalogFacade {
    TierSummaryDTO   getTierSummary(long eventId, long tierId);   // throws TierNotFoundException
    EventWindowStatus getWindowStatus(long eventId);
    int              getRemainingForEvent(long eventId);           // -1 ⇒ any tier unreadable

    // Redis. Neither may run inside a SQL transaction (ADR-046).
    ReserveResult tryReserve(long eventId, long tierId, int quantity);
    void          restore(long eventId, long tierId, int quantity);

    // The rebuild, which `order` drives because only it can read the whole ledger.
    Map<Long, Integer> getTierCapacities(long eventId);
    Map<Long, Integer> getLiveCounters(long eventId);
    void               applyRebuild(long eventId, Map<Long, Integer> remainingByTier);
}

/** "No" has two meanings and they are not interchangeable (ADR-004). */
public enum ReserveResult { RESERVED, INSUFFICIENT, COUNTER_MISSING }

public record TierSummaryDTO(
    long eventId, long tierId, String tierName,
    long priceCents, String currency, int maxPerOrder,
    String eventTitle, String venueName, Instant eventStartTime,
    EventWindowStatus windowStatus) {}
```

`TierSummaryDTO` carries the venue and event time so `order` can snapshot a complete outbox payload
and `notification` never needs to call `catalog` (ADR-015).

**Events:**
* `EventPrewarmedEvent(eventId, tierIds, totalStock, at)` — **not built**
* `StockRebuiltEvent(eventId, perTierBefore, perTierAfter, at)` — **not built**; the rebuild logs
  before and after at `WARN`, and no consumer wanted the event
* `TierAvailabilityChangedEvent(eventId, tierId, level, at)` — **new (ADR-027)**. Fired when a tier
  crosses a bucket boundary (`PLENTY` → `LIMITED` → `SOLD_OUT`). `queue` consumes it and fans it out
  to the waiting room as a `tier-availability` SSE frame, so a buyer waiting specifically for VIP
  learns it is gone **while waiting** rather than after admission.

  Buckets, never exact counts: exact live inventory drives panic-buying and hands scalpers a feed.
  Thresholds are `SOLD_OUT` at 0, `LIMITED` below 10 % of `total_capacity`, else `PLENTY`, with
  hysteresis so a restored hold does not flap the banner.

### `AvailabilityLevel` has four values, and the fourth is not a bucket (ADR-040)

| Value | Meaning |
| :--- | :--- |
| `PLENTY` | above the threshold |
| `LIMITED` | at or below `limited-threshold-percent` of capacity |
| `SOLD_OUT` | the counter says zero |
| **`UNKNOWN`** | **there is no counter** — a fault to repair, never a sold-out tier |

`AvailabilityBuckets.of` takes the **raw** value, fault code included. The call site used to clamp
with `Math.max(remaining, 0)`, which turned `COUNTER_UNAVAILABLE` (`-1`) into `0` and published a
missing counter to every visitor as `SOLD_OUT` — ADR-004's prohibition, on the browse path, on the
first surface anyone loads. ADR-035 fixed the same substitution for the promoter and the reserve
path; this was the third caller.

Clients render `UNKNOWN` neutrally and keep the tier **selectable**. `POST /holds` is what actually
knows, and it already separates `409 INSUFFICIENT_STOCK` from `503 INVENTORY_UNAVAILABLE`.

---

## 6. Edge cases

| Case | Handling |
| :--- | :--- |
| Prewarm triggered twice | `SETNX` no-op |
| Prewarm attempted after sale opens | `409 PREWARM_WINDOW_CLOSED` |
| Stock counter missing mid-sale | `-2` → `503` → alarm → rebuild. **Never** reseed from capacity |
| Redis down | Browse reports `UNKNOWN`; holds `503`. There is no PostgreSQL copy to fall back to |
| Redis restarted | `StockEpoch` refuses holds for every event it vouched for under the old instance, until each is rebuilt |
| PostgreSQL slow mid-sale | Browse and reserve continue from Redis |
| Capacity increased mid-sale | Not supported. Requires pause → capacity change → rebuild |
| Price changed mid-sale | Not supported. Orders snapshot price at checkout |

**Exceptions:** `EventNotFoundException` → 404 · `TierNotFoundException` → 404 ·
`PrewarmWindowClosedException` → 409 · `InventoryUnavailableException` → 503 ·
`StockRebuildInProgressException` → 503.

---

## 7. Changes from v1

1. Cache-miss repopulation from `total_capacity` **removed** — replaced by fault + locked rebuild
   (ADR-004).
2. `tier_inventory` table added — the Phase 1 counter. Dropped again in Stage 1 (see §7, Stage 1).
3. `windowStatus` + `serverTime` added and enforced (ADR-016).
4. `event_start_time`, `currency`, `max_per_order` columns added.
5. `TierSummaryDTO` widened so `notification` needs no facade call.
6. `maxmemory-policy noeviction` and post-restart reconciliation made explicit.
7. Prewarm restricted to `UPCOMING`.
8. Admin `rebuild-stock` and `pause` endpoints added.

### Added in the 2nd pass

9. Rebuild lock is now `pg_try_advisory_xact_lock`, not a Redisson lock (ADR-022).
10. Public availability is exposed **bucketed** (`PLENTY` / `LIMITED` / `SOLD_OUT`) rather than as an
    exact integer. Exact counts drive panic-buying and hand scalpers a live inventory feed; industry
    practice is a coarse indicator. Exact values remain internal, for `hold` and for metrics.
11. Error codes aligned to the canonical registry in `05-global-standards.md` §2.

### Added in the 3rd pass

12. `TierAvailabilityChangedEvent` published on bucket transitions, so the waiting room can show
    per-tier sold-out state before admission (ADR-027).

### Changed in Stage 1 — the Redis fast path (ADR-046)

13. **`catalog:stock:{e}:{t}` is the live count, and PostgreSQL holds no copy of it.**
    `tier_inventory` is dropped; the rebuild derives from `ticket_tiers`, `order_items` and
    `ticket_holds`, which is where the count was always recoverable from.
14. **The scripts live here and are run from here.** `hold` calls `CatalogFacade`; the "single
    shared key" exception to module ownership is gone. Renamed `stock_reserve.lua` /
    `stock_restore.lua` since they no longer touch a hold key, and the reserve script no longer
    writes hold metadata — ADR-019 removed that authority long ago.
15. **`tryReserve` returns `ReserveResult`, not a boolean**, so "sold out" and "cannot read the
    counter" are separated inside the same atomic step. Recovering the distinction afterwards by
    re-reading the counter raced: a restore landing between the two calls turned a fault into an
    ordinary sell-out.
16. **Neither mutator may run inside a SQL transaction**, and both lost `Propagation.MANDATORY`.
    Redis does not roll back, so the transactional coupling the annotation promised no longer
    exists.
17. **`catalog:vouch:{eventId}` and `StockEpoch`** make "a rebuild is mandatory after a Redis
    restart" an enforced rule rather than a line in this document.
