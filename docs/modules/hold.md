# Module: `hold`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.hold` · **Storage:** PostgreSQL (authority) + Redis (timer only)
**Depends on:** `catalog`, `queue`, `shared`

---

## 1. Scope

The reservation lifecycle: create, read, extend, release, consume, expire. **This is the module that
prevents overbooking.** `ticket_holds` is the authority on whether seats are held; the Redis counter
is only a count.

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `ticket_holds` | token, session, event, tier, quantity, status, `expires_at`, `extended_count`, settle reason |

Two indexes are load-bearing:

- `idx_holds_one_active_per_session` — a **partial unique** index capping a session at one live hold.
  A double-click therefore fails at the database, not in a check-then-act.
- `idx_holds_sweeper (expires_at) WHERE status = 'ACTIVE'` — what makes the sweeper cheap.

| Redis key | TTL | Purpose |
| :--- | :--- | :--- |
| `hold:{token}` | hold TTL | expiry timer. **A hint, never an authority** (ADR-048) |

The timer's value is the token, and nothing reads it — the expiry *event* carries the key name,
which is all the listener needs. A value nobody parses cannot drift out of step with the row that
owns the truth. This is the `holdmeta` key ADR-019 deleted, and it is not coming back.

---

## 3. What it exposes

| Method | Path | Auth |
| :--- | :--- | :--- |
| `POST` | `/api/v1/holds` | `fsid` + `X-Admission-Token` |
| `GET` | `/api/v1/holds/{holdToken}` | `fsid` + ownership |
| `DELETE` | `/api/v1/holds/{holdToken}` | `fsid` + ownership |

A hold that is not yours answers `404`, not `403`, so tokens cannot be enumerated.

**Facade:** `getActiveHold`, `findActiveHold`, `consumeHold`, `grantGrace`, `discardTimer`,
`sumActiveQuantityForTier`. (`releaseHold` exists with **no production caller** — see §6.)

---

## 4. The two rules that govern every method

### 4.1 A reservation and the stock movement that justifies it are ordered, not atomic

They cannot be atomic: the counter is in Redis and the row is in PostgreSQL, and **Redis does not
roll back**. So they are ordered and compensated.

```
reserve in Redis          ← outside any transaction
write the hold row        ← its own transaction, flushed so the constraint speaks
    on constraint failure → restore, then 409/410
publish TicketHeldEvent
    AFTER_COMMIT          → arm the hold:{token} timer
```

**Compensation runs only on a constraint rejection**, because that is the one failure whose outcome
is certain. A flush that violates the one-live-hold index leaves no row, so the seats are
unambiguously ours to return. **A failure at commit is ambiguous** — the row may exist, and returning
seats that are still held is an oversell. Those fall through to `flashseats.stock.drift` and a
rebuild, which is the safe direction.

### 4.2 A hold leaves `ACTIVE` only via the settle-once claim

One conditional `UPDATE ... WHERE status = 'ACTIVE'`. Rowcount 1 means you won; anything else means
somebody else did. Every ending that returns seats funnels through it, which makes "restored exactly
once per hold" true **by construction** rather than by call-site discipline.

**The `INCRBY` happens after that claim commits**, in an `AFTER_COMMIT` listener. Incrementing inline
would hand the seats out and then let a rollback put the hold straight back to `ACTIVE` — both on
sale and still held. The sweeper makes the danger concrete: it settles a whole batch in one
transaction, so one failure part-way through would return every earlier hold's seats while leaving
those holds live.

**`consume` is the exception that proves it.** It runs `Propagation.MANDATORY` inside `order`'s
transaction and deliberately restores nothing — the seats were bought. If that transaction rolls
back, the claim rolls back with it and the hold expires normally. That is why the claim lives in SQL
and not in Redis.

---

## 5. Expiry — two mechanisms, one guarantee

| | Sweeper | Timer |
| :--- | :--- | :--- |
| Trigger | `@Scheduled`, every 10 s | `hold:{token}` keyspace expiry |
| Delivery | polls the table | **at-most-once pub/sub** |
| Role | **the guarantee** | latency only — measured at 339 ms vs up to 10 s |

**The timer is an accelerator and can never be the guarantee.** A replica that is restarting, or
whose connection drops for a moment, loses the event permanently and nothing redelivers it.

**The listener re-reads the row and settles only what the sweeper would have.** Three reasons this is
not pedantry: `grantGrace` moves a hold's expiry in PostgreSQL, so the original timer fires
mid-payment; AOF `everysec` can resurrect or lose a key relative to its row; an operator can flush
the key. A hold found alive is **re-armed**, which keeps a grace-extended hold on the fast path.

**Exactly-once across replicas needs no coordination.** Keyspace expiry is broadcast, so all three
replicas run the listener, all three reach the claim, and exactly one `UPDATE` returns 1. Restoring
stock in the listener directly — rather than through the claim — is how a naive implementation
triples a tier's inventory.

This requires `notify-keyspace-events Ex` on the server. `E` is the key-**event** channel; `K`
publishes the event name to a per-key channel and the listener never fires (ADR-003).

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **`releaseHold` has no production caller** | The facade method, the `HoldReleaseReason` enum and the service method behind them are reached only from a test. Nothing in production releases a hold this way, and ADR-001 is why: a decline deliberately **retains** the hold. Slated for deletion |
| **`sumActiveQuantityForTier` has no supporting index** | It filters `tier_id + status = 'ACTIVE'`; `idx_holds_event_tier` needs a leading `event_id` and `idx_holds_sweeper` leads on `expires_at`. The drift gauge runs it per tier, per event, per replica, every 60 s, over a table that accumulates every hold ever created. Needs a partial index on `(tier_id) WHERE status = 'ACTIVE'` |
| **No conversion metric** | `flashseats.hold.conversion.ratio` is specified in `03` §7 and not built — it is the number the 1.5× oversubscribe factor is currently guessing at |

---

## 7. What it must never do

- Touch `catalog:stock:*` directly. Stock moves through `CatalogFacade`.
- Mutate Redis inside a SQL transaction, or arm a timer before the row commits.
- Restore stock anywhere but behind a won settle-once claim.
- Restore stock on a `CONSUMED` hold. There is no `CONSUMED → RELEASED` transition (ADR-001).
- Grant more than one grace extension per hold. Per-attempt would allow 300 + 3×120 = 660 s and make
  three deliberate declines a cheap way to squat on inventory (ADR-030).
- Trust the `hold:{token}` key over the row.
- Accept a queue **pass** as authorisation. `POST /holds` requires an **admission token**; the pass
  was already spent at `/queue/admit` (ADR-020).
