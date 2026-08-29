# Module: `hold`

> **Status:** first-pass correction. Aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md).
> A detailed second pass is planned before implementation.

**Package:** `com.flashseats.hold` · **Phase:** 1 (PostgreSQL) → 2 (Redis) · **Storage:** PostgreSQL + Redis

---

## 1. Scope

Grants exclusive, time-bound seat reservations to users who have passed the waiting room, and
guarantees that reserved inventory returns to the pool **exactly once** when the reservation ends —
whichever way it ends.

This module is where overbooking is prevented. The queue shapes traffic; `hold` enforces
correctness.

**Forbidden:** processing payments, pricing, issuing queue passes, writing orders.

---

## 2. Package layout

```
com.flashseats.hold
├── config       HoldProperties, RedisKeyspaceConfig
├── controller   HoldController
├── dto          CreateHoldRequestDTO, HoldResponseDTO, HoldSummaryDTO
├── event        TicketHeldEvent, TicketHoldExpiredEvent,
│                TicketHoldConsumedEvent, TicketHoldReleasedEvent
├── exception    HoldNotFound(404), HoldExpired(410), InsufficientStock(409),
│                HoldAlreadySettled(409), HoldLimitExceeded(409),
│                InventoryUnavailable(503)
├── facade       HoldFacade + impl
├── model        HoldStatus, TicketHoldEntity
├── repository   TicketHoldJpaRepository, HoldRedisRepository (Lua)
└── service      HoldService, RedisKeyspaceListener, HoldReconciliationSweeper
```

---

## 3. State machine

```
                  ┌──── extendHold (once, ≤ +120s, ceiling 420s from creation)
                  │                                   │
                  ▼                                   │
   create ───► ACTIVE ───────────────────────────────┘
                 │
                 ├── consumeHold   ──► CONSUMED   (terminal — became an order)
                 ├── releaseHold   ──► RELEASED   (terminal — cancelled/failed)
                 └── TTL / sweeper ──► EXPIRED    (terminal — abandoned)
```

**No transition leaves a terminal state.** In particular there is no `CONSUMED → RELEASED`, which
is why checkout charges *before* consuming (ADR-001).

### The settle-once claim

Every terminal transition begins by claiming the right to restore stock. Exactly one caller —
across every replica, across every code path — wins.

| Phase | Primitive | Winner |
| :--- | :--- | :--- |
| 2+ | `GETDEL holdmeta:{holdToken}` | the caller that receives a non-nil value |
| 1 | `UPDATE ticket_holds SET status=? WHERE hold_token=? AND status='ACTIVE'` | `rowcount = 1` |

Everyone else receives `nil` / `rowcount = 0` and does nothing. No locks, no coordination.

This one primitive fixes three separate defects in v1:

1. **Triple restoration.** Redis keyspace expiry is pub/sub — **all three replicas** receive
   `__keyevent@0__:expired`, and each restored the stock. A 2-ticket hold returned 6 tickets.
2. **The undefined shadow record.** v1 told the listener to "read the shadow backup record or
   extract metadata", but that record was never specified anywhere — and a key's Hash fields are
   already gone when its expiry event fires. `holdmeta` is that record, written inside the same Lua
   script and outliving the hold by 24 hours.
3. **The release/expire race.** `releaseHold` and a concurrent TTL expiry could both restore.

---

## 4. Schema

```sql
CREATE TABLE ticket_holds (
    id               BIGSERIAL PRIMARY KEY,
    hold_token       VARCHAR(64)  NOT NULL UNIQUE,
    user_session_id  VARCHAR(255) NOT NULL,
    event_id         BIGINT       NOT NULL,
    tier_id          BIGINT       NOT NULL,
    quantity         INT          NOT NULL CHECK (quantity > 0 AND quantity <= 6),
    status           VARCHAR(32)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    extended_count   INT          NOT NULL DEFAULT 0,   -- added: enforces the grace ceiling
    settled_at       TIMESTAMPTZ,                        -- added: when it left ACTIVE
    settle_reason    VARCHAR(64),                        -- added: CONSUMED | USER_CANCEL | TTL | SWEEPER
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_holds_session        ON ticket_holds(user_session_id);
CREATE INDEX idx_holds_event_tier     ON ticket_holds(event_id, tier_id);
CREATE INDEX idx_holds_sweeper        ON ticket_holds(expires_at) WHERE status = 'ACTIVE';

-- ADR-017: at most one live hold per session per event
CREATE UNIQUE INDEX idx_holds_one_active_per_session
    ON ticket_holds(user_session_id, event_id) WHERE status = 'ACTIVE';
```

That last partial unique index turns "one active hold per session" into a database guarantee rather
than a check that races.

### Redis (Phase 2+)

| Key | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `hold:{holdToken}` | Hash | **300 s** | drives expiry timing; serves `GET /holds/{token}` |
| `holdmeta:{holdToken}` | String `"{eventId}:{tierId}:{qty}:{sid}"` | **86400 s** | the settle-once claim ticket |
| `catalog:stock:{e}:{t}` | String | none | owned by `catalog`; mutated here only |

`holdmeta` must outlive `hold:` — it is what makes the expiry event actionable at all.

---

## 5. Reserve

### Phase 1

```sql
UPDATE tier_inventory SET remaining = remaining - :q
 WHERE tier_id = :t AND remaining >= :q;     -- rowcount = 1 ⇒ reserved
INSERT INTO ticket_holds (...) VALUES (..., 'ACTIVE', now() + interval '300 seconds');
```

Row-locked by PostgreSQL, guarded by `CHECK (remaining >= 0)`. Overbooking is impossible in the MVP.

### Phase 2+ — `hold_reserve.lua`

```lua
local stockKey, holdKey, metaKey = KEYS[1], KEYS[2], KEYS[3]
local qty, ttl = tonumber(ARGV[1]), tonumber(ARGV[2])
local sid, eventId, tierId, expiresAt = ARGV[3], ARGV[4], ARGV[5], ARGV[6]

local stock = redis.call('GET', stockKey)
if stock == false then return -2 end          -- counter ABSENT ⇒ FAULT (ADR-004)
if tonumber(stock) < qty then return -1 end   -- genuinely insufficient

redis.call('DECRBY', stockKey, qty)
redis.call('HSET', holdKey,
    'userSessionId', sid, 'eventId', eventId, 'tierId', tierId,
    'quantity', tostring(qty), 'status', 'ACTIVE', 'expiresAt', expiresAt)
redis.call('EXPIRE', holdKey, ttl)
redis.call('SET', metaKey, eventId..':'..tierId..':'..qty..':'..sid, 'EX', 86400)
return 1
```

| Return | Meaning | HTTP |
| :--- | :--- | :--- |
| `1` | reserved | `201` |
| `-1` | genuinely sold out | `409 INSUFFICIENT_STOCK` |
| `-2` | **counter missing — fault** | `503 INVENTORY_UNAVAILABLE` + alarm |

v1's script used `GET stockKey or "0"`, collapsing "missing" into "sold out". Distinguishing them is
what lets the system detect Redis loss instead of quietly telling every buyer the sale ended.

### Request flow

1. `QueueFacade.verifyPassToken(token, sid, eventId)` → 401
2. `CatalogFacade.getTierSummary()` → 404; `windowStatus == OPEN` → 409
3. `quantity ≤ min(6, tier.maxPerOrder)`; no existing `ACTIVE` hold for this session → 409
4. reserve (above)
5. `QueueFacade.revokePassToken(sid)` — **the pass is single-use** (ADR-006)
6. insert the audit row; publish `TicketHeldEvent`

---

## 6. Restore

### Expiry (Phase 2+)

```
Redis TTL fires → __keyevent@0__:expired → EVERY replica receives it
  → each attempts GETDEL holdmeta:{token}
  → exactly one wins → INCRBY catalog:stock:{e}:{t} qty
  → UPDATE ticket_holds SET status='EXPIRED', settled_at=now(), settle_reason='TTL'
  → publish TicketHoldExpiredEvent
```

Requires `notify-keyspace-events Ex` (off by default in Redis). `E` selects the key-**event**
channel `__keyevent@0__:expired`, whose message is the expired key's name. `K` selects the keyspace
channel, whose message is the event name instead — with `Kx` the listener never fires. Shipped in
[`docker/redis/redis.conf`](../../docker/redis/redis.conf).

### Sweeper — the actual guarantee

`HoldReconciliationSweeper` runs every 30 s (10 s in Phase 1):

```sql
SELECT * FROM ticket_holds WHERE status = 'ACTIVE' AND expires_at < now() LIMIT 500;
```

and performs the identical claim per row.

**Keyspace pub/sub is at-most-once.** A dropped connection, a restarting replica, or a network blip
loses the event permanently. The listener is therefore a *latency optimisation*; the sweeper is what
makes expiry correct. Disabling the listener entirely must not change any outcome — that is a
Phase 2 exit criterion.

---

## 7. Interfaces

| Method | Path | Auth | Notes |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/holds` | `X-Queue-Pass-Token` + `fsid` | `201` |
| `GET` | `/api/v1/holds/{holdToken}` | `fsid` must own it | status + `ttlRemainingSeconds` |
| `DELETE` | `/api/v1/holds/{holdToken}` | `fsid` must own it | immediate release |

```json
// POST /api/v1/holds
{ "eventId": 10024, "tierId": 501, "quantity": 2 }
```

`userSessionId` is **not** accepted from the body, and `X-Session-ID` is **not** accepted on the
cancel endpoint. Identity comes from the signed `fsid` cookie only (ADR-010). `GET` is
ownership-checked — v1 left it public, allowing hold-token enumeration.

```java
public interface HoldFacade {
    /** Read-only, ownership-checked. Does NOT mutate. */
    HoldSummaryDTO getActiveHold(String holdToken, String userSessionId);

    /** Settle-once claim → CONSUMED. Idempotent: a second call throws HoldAlreadySettled. */
    HoldSummaryDTO consumeHold(String holdToken);

    /** Settle-once claim → RELEASED, restoring stock. */
    void releaseHold(String holdToken, String reason);

    /** Bounded grace. Once only; ≤ +120s; ceiling 420s from creation.
     *  Pushes BOTH the Redis TTL and ticket_holds.expires_at. */
    Instant extendHold(String holdToken, int seconds);
}

public record HoldSummaryDTO(
    String holdToken, String userSessionId, long eventId, long tierId,
    int quantity, HoldStatus status, Instant expiresAt, Instant createdAt) {}
```

v1 declared `validateAndConsumeHold` while every other document called it `consumeHold`; v1 also
called `isHoldActiveForSession` but callers actually needed the hold's contents. `extendHold` was
used by two documents and declared on none — and §1 of v1 called the window "strict,
non-extendable", contradicting both.

**Pushing `ticket_holds.expires_at` on extension is not optional.** Without it the sweeper reclaims
the seat mid-3-D-Secure.

---

## 8. Edge cases

| Case | Handling |
| :--- | :--- |
| Two requests, last ticket | Atomic reserve; exactly one `201`, one `409` |
| Expiry event lost | Sweeper claims within 30 s |
| Expiry broadcast to 3 replicas | `GETDEL` claim — one restore |
| `releaseHold` racing TTL expiry | Same claim — one restore |
| Double `consumeHold` | Second call → `HoldAlreadySettled` (409) |
| Checkout submitted at t=300.1 s | `expires_at` compared server-side; `410 Gone` |
| Stock counter missing | `-2` → `503`, **never** treated as sold out |
| Session already holds seats | `409 HOLD_LIMIT_EXCEEDED` (partial unique index) |
| App crashes between Lua and audit insert | `holdmeta` still expires → sweeper has no PG row, but the keyspace claim restores stock; drift alarm catches any residue |
| User wants a different tier | `DELETE` the hold, then re-hold — but the pass was consumed, so they re-enter the queue. *Flagged for second pass: this may be too harsh.* |

**Exceptions:** `HoldNotFound` 404 · `HoldExpired` 410 · `InsufficientStock` 409 ·
`HoldAlreadySettled` 409 · `HoldLimitExceeded` 409 · `InventoryUnavailable` 503.

---

## 9. Changes from v1

1. **Settle-once claim** (`GETDEL holdmeta` / conditional `UPDATE`) replaces the Redisson-locked
   sweeper and fixes triple restoration (ADR-003).
2. `holdmeta:{token}` defined — v1 referenced an undefined "shadow backup record".
3. Reserve script distinguishes `-2` (fault) from `-1` (sold out) (ADR-004).
4. `extendHold` declared, bounded, and reconciled with the "non-extendable" contradiction (ADR-006).
5. `consumeHold` naming unified across all documents.
6. Pass revoked on first successful hold (ADR-006).
7. Partial unique index enforcing one `ACTIVE` hold per session per event (ADR-017).
8. `userSessionId` removed from the request body; `GET` ownership-checked (ADR-010).
9. `extended_count`, `settled_at`, `settle_reason` columns added.
10. Sweeper documented as the correctness guarantee, the listener as an optimisation.
