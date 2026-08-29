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

**Inventory ownership, precisely.** `catalog` owns `catalog:stock:{eventId}:{tierId}` — it seeds,
reads, rebuilds and reconciles. `hold` is the only other module permitted to touch it, exclusively
through `hold_reserve.lua` / `hold_restore.lua`, because a decrement and the reservation that
justifies it must be one atomic operation. That exception is deliberate, narrow, and the only shared
key in the system.

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

### `tier_inventory` — **new**

```sql
CREATE TABLE tier_inventory (
    tier_id    BIGINT PRIMARY KEY REFERENCES ticket_tiers(id),
    event_id   BIGINT NOT NULL,
    remaining  INT    NOT NULL CHECK (remaining >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tier_inventory_event ON tier_inventory(event_id);
```

Phase 1: the live counter, mutated by a conditional `UPDATE`.
Phase 2+: Redis is the live counter; this column becomes last-known-good, refreshed on
reconciliation. `CHECK (remaining >= 0)` is the database-level guarantee that overbooking cannot be
persisted.

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
| `catalog:stock:{eventId}:{tierId}` | String (integer) | none | live remaining; `noeviction` required |
| `catalog:meta:{eventId}` | String (JSON) | 60 s | browse-read cache |

**`maxmemory-policy noeviction` is mandatory.** A `TTL = −1` key is not protected from an LRU
eviction policy; losing a stock counter mid-sale is the worst failure the system has.

### Pre-warm

```
if windowStatus != UPCOMING: reject 409 PREWARM_WINDOW_CLOSED
for each tier:  SETNX catalog:stock:{eventId}:{tierId} <total_capacity>
publish EventPrewarmedEvent
```

`SETNX` makes a repeated trigger a no-op. The `UPCOMING` gate is the important part: once the sale
is `OPEN`, seeding from `total_capacity` would resurrect every ticket already sold (ADR-004).

### Missing counter = fault, not cache miss

**The previous version of this document specified falling back to `total_capacity` and
repopulating on a cache miss. That was the single most dangerous line in the design.** A Redis
eviction, cold restart, or `FLUSHDB` mid-sale would have silently restored the entire inventory.

Correct behaviour while `OPEN`:

1. `hold_reserve.lua` returns `-2`; the API returns `503 INVENTORY_UNAVAILABLE`; the alarm fires.
2. Browse reads degrade to `tier_inventory.remaining` clearly marked as approximate.
3. Recovery is an explicit locked rebuild.

### Rebuild (`pg_try_advisory_xact_lock(hash('stock-rebuild', eventId))`)

```sql
remaining = tt.total_capacity
  - COALESCE((SELECT SUM(oi.quantity) FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
               WHERE oi.tier_id = tt.id AND o.status = 'CONFIRMED'), 0)
  - COALESCE((SELECT SUM(th.quantity) FROM ticket_holds th
               WHERE th.tier_id = tt.id AND th.status = 'ACTIVE'
                 AND th.expires_at > now()), 0)
```

No double counting: a `PENDING` order still has an `ACTIVE` hold; a `CONFIRMED` order's hold is
`CONSUMED`. Writes both Redis and `tier_inventory`, then publishes `StockRebuiltEvent`.

The lock is a PostgreSQL **transaction-scoped advisory lock**: released automatically on commit or
rollback, impossible to leak, and needing no extra dependency. Redisson was dropped in ADR-022 once
this was its only remaining use.

**Mandatory after any Redis restart** — AOF `everysec` can lose a second of `DECRBY`s, which reads
as inventory that does not exist.

---

## 5. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `GET` | `/api/v1/events` | public |
| `GET` | `/api/v1/events/{eventId}` | public — `windowStatus`, `serverTime`, **bucketed** availability |
| `POST` | `/api/v1/admin/events/{eventId}/prewarm` | admin — `UPCOMING` only |
| `POST` | `/api/v1/admin/events/{eventId}/rebuild-stock` | admin |
| `POST` | `/api/v1/admin/events/{eventId}/pause` | admin — halt promotions and new holds |

```java
public interface CatalogFacade {
    TierSummaryDTO   getTierSummary(long eventId, long tierId);   // throws TierNotFoundException
    EventWindowStatus getWindowStatus(long eventId);
    int              getRemaining(long eventId, long tierId);      // -1 ⇒ counter unavailable

    // Phase 1 only; superseded by hold_reserve.lua in Phase 2+
    boolean tryReserve(long eventId, long tierId, int quantity);
    void    restore(long eventId, long tierId, int quantity);
}

public record TierSummaryDTO(
    long eventId, long tierId, String tierName,
    long priceCents, String currency, int maxPerOrder,
    String eventTitle, String venueName, Instant eventStartTime,
    EventWindowStatus windowStatus) {}
```

`TierSummaryDTO` carries the venue and event time so `order` can snapshot a complete outbox payload
and `notification` never needs to call `catalog` (ADR-015).

**Events:**
* `EventPrewarmedEvent(eventId, tierIds, totalStock, at)`
* `StockRebuiltEvent(eventId, perTierBefore, perTierAfter, at)`
* `TierAvailabilityChangedEvent(eventId, tierId, level, at)` — **new (ADR-027)**. Fired when a tier
  crosses a bucket boundary (`PLENTY` → `LIMITED` → `SOLD_OUT`). `queue` consumes it and fans it out
  to the waiting room as a `tier-availability` SSE frame, so a buyer waiting specifically for VIP
  learns it is gone **while waiting** rather than after admission.

  Buckets, never exact counts: exact live inventory drives panic-buying and hands scalpers a feed.
  Thresholds are `SOLD_OUT` at 0, `LIMITED` below 10 % of `total_capacity`, else `PLENTY`, with
  hysteresis so a restored hold does not flap the banner.

---

## 6. Edge cases

| Case | Handling |
| :--- | :--- |
| Prewarm triggered twice | `SETNX` no-op |
| Prewarm attempted after sale opens | `409 PREWARM_WINDOW_CLOSED` |
| Stock counter missing mid-sale | `-2` → `503` → alarm → rebuild. **Never** reseed from capacity |
| Redis down | Browse degrades to PostgreSQL (approximate); holds `503` |
| Redis restarted | Rebuild is mandatory before resuming |
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
2. `tier_inventory` table added — the Phase 1 counter and the Phase 2+ reconciliation target.
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
