# Module: `saleflow`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.saleflow` · **Storage:** none
**Depends on:** `catalog`, `queue`, `hold`, `order`, `shared`

---

## 1. Scope

One endpoint. It composes four facade reads into the payload that rehydrates the client after a tab
reload, and it **makes no decisions** (ADR-025).

**A read-only leaf: nothing depends on it, and nothing may.**

---

## 2. What it owns

Nothing. No table, no Redis key, no state of any kind.

---

## 3. What it exposes

| Method | Path | Auth |
| :--- | :--- | :--- |
| `GET` | `/api/v1/sale/{eventId}/state` | `fsid` cookie |

Returns the window status, a server clock, and three optional sections — queue, hold, order — plus a
`partial` list naming any section that could not be read.

---

## 4. Why it is a separate module

Because there is nowhere else to put it. The endpoint needs `catalog`, `queue`, `hold` **and**
`order`. Putting it in `queue` would require `queue → hold`, while `hold → queue` already exists —
a cycle, and `ApplicationModules.verify()` fails the build. **A leaf that depends on many and is
depended on by none is the standard answer.**

This is also why it must stay a leaf. The moment anything depends on `saleflow`, the graph has a new
path through four modules at once.

---

## 5. It fails soft, per section

If the queue read throws, `queue` comes back `null`, `"queue"` is added to `partial`, and the rest
still renders. **`catalog` is the exception** — without a window status and a server clock there is
nothing meaningful to draw, so that failure surfaces.

The only judgement this module exercises is which failures are survivable. Any conditional logic
beyond null-handling would be a business rule that had leaked out of the module owning it.

**Rehydration reports the latest order whatever its status** (ADR-037). Filtering to "in flight"
states meant a completed purchase vanished on reload and the buyer was invited to re-buy what they
already owned.

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **It reads the event row twice** | `getEventSummary` for the window and clock, then `getQueueState` resolves the window again internally. Four database transactions per rehydration, two of them the same row. Caching in `catalog` removes this without touching the assembler's purity |

---

## 7. What it must never do

- Be depended on by anything.
- Decide anything. No branching on phase, no inferred next step, no derived eligibility — the client
  owns the view state machine (`FE_SPEC.md` §1).
- Read another module's tables or Redis keys directly.
- Write anything at all.
