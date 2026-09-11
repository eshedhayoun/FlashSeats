# Module: `saleflow`

> **New in the 2nd pass** ([ADR-025](../00-architecture-decisions.md)). Conforms to
> [`../05-global-standards.md`](../05-global-standards.md).

**Package:** `com.flashseats.saleflow` · **Phase:** 2 · **Storage:** none

---

## 1. Scope

A **read-only composition module**. It owns one endpoint — `GET /api/v1/sale/{eventId}/state` —
which aggregates four facades into a single payload the SPA calls on every mount to recover exactly
where this session is in the journey.

**Forbidden:** any write, any storage, any table, any Redis key, any business rule. If a decision is
being made here, it belongs in the module that owns the state.

### Why it exists

A tab reload at any point previously lost everything. The SPA had no way to discover that this
session is admitted to the sale, holds two VIP seats with 90 seconds left, and has a payment
in flight. Every production ticketing SPA calls exactly one state endpoint on mount.

### Why it is its own module

It cannot live in `queue`: `queue` would need `HoldFacade`, but `hold → queue` already exists, so
that edge would create a cycle and `ApplicationModules.verify()` would fail the build.

A **leaf** — depending on many, depended on by none — is the standard Modulith answer, and it is
architecturally cheap: no storage, no state, no lifecycle. It also gives `OrderFacade` its first
real caller, resolving the YAGNI finding that its only stated consumers were tools that do not exist.

---

## 2. Package layout

```
com.flashseats.saleflow
├── controller   SaleStateController
├── service      SaleStateAssembler      # pure composition; no decisions
└── dto          SaleStateDTO, QueueStateDTO, HoldStateDTO, OrderStateDTO
```

No `repository`, no `model`, no `facade`, no `event` — their absence is the specification.

---

## 3. Interface

| Method | Path | Auth | Idempotency |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/sale/{eventId}/state` | `fsid` cookie | safe; no key needed |

```json
{
  "eventId": 10024,
  "windowStatus": "OPEN",
  "serverTime": "2026-08-30T10:04:12Z",
  "queue": {
    "state": "ADMITTED",
    "position": null,
    "estWaitSeconds": null,
    "admissionExpiresAt": "2026-08-30T10:12:40Z"
  },
  "hold": {
    "holdToken": "hld_9f8b2c1a4d3e2f10b98a",
    "tierId": 501,
    "tierName": "VIP Admission",
    "quantity": 2,
    "expiresAt": "2026-08-30T10:07:55Z"
  },
  "order": {
    "orderNumber": "TK-98213",
    "status": "PENDING"
  }
}
```

`queue.state` ∈ `NOT_JOINED` | `WAITING` | `PROMOTED` | `ADMITTED` | `EXHAUSTED` | `CLOSED`.
`hold` and `order` are `null` when absent.

`serverTime` is returned on every response so every countdown in the UI — queue estimate, admission
session, hold timer — runs on the server's clock rather than the device's (ADR-016).

### Composition

```java
QueueFacade.getQueueState(sid, eventId)      // state, position, admission expiry
HoldFacade.findActiveHold(sid, eventId)      // Optional
OrderFacade.findPendingOrder(sid, eventId)   // Optional
CatalogFacade.getEventDetail(eventId)        // window status, tier names, server time
```

Four in-process calls, no I/O beyond what each facade already does. The assembler makes **no
decisions** — it maps four `Optional`s into one DTO. Any conditional logic beyond null-handling is a
sign that a rule has leaked out of its owning module.

---

## 4. Transaction boundaries

None. Every call is a read. The controller is **not** `@Transactional`.

## 5. Idempotency

Not applicable — `GET` is safe and has no side effects.

## 6. Resilience

Fails **soft, per section**. If `HoldFacade` throws, `hold` comes back `null` with a
`partial: ["hold"]` marker rather than failing the whole response: a rehydration endpoint that
returns `500` because one sub-read failed is worse than one that returns most of the picture. The
SPA renders what it has and retries.

The exception is `CatalogFacade` — without `windowStatus` and `serverTime` there is nothing
meaningful to render, so that failure surfaces as `503`.

## 7. Edge cases

| Case | Handling |
| :--- | :--- |
| No `fsid` cookie | `bot` filter issues one; response shows `NOT_JOINED` |
| Reload while waiting | `WAITING` + current position; SPA reopens the SSE stream |
| Reload after promotion, pass unspent | `PROMOTED` + `passToken`; SPA calls `/queue/admit` |
| Reload while admitted, no hold | `ADMITTED`, `hold: null` — tier picker |
| Reload mid-checkout | `hold` + `order: PENDING` — resume the payment form |
| Reload after confirmation | `order: CONFIRMED` — receipt |
| Sale sold out while away | `EXHAUSTED` |
| One sub-read fails | that section `null` + `partial`; the rest still renders |

## 8. Metrics

`flashseats.saleflow.state.latency` · `flashseats.saleflow.partial.rate` (a rising partial rate is
an early warning that one of the four modules is degrading).
