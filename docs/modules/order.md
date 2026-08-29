# Module: `order`

> **Status:** first-pass correction. Aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md).
> A detailed second pass is planned before implementation.

**Package:** `com.flashseats.order` · **Phase:** 1 · **Storage:** PostgreSQL only

---

## 1. Scope

The transactional authority and **the checkout orchestrator**. There is exactly one checkout
entry point in the system, and it lives here.

`order` validates the hold, prices the purchase server-side, reserves a durable order row, drives
the charge, and — in a single transaction — consumes the hold, writes the ledger, and enqueues
fulfilment. It also owns every compensation path: decline, refund, and webhook reconciliation.

**Forbidden:** touching stock counters, managing queue positions, rendering PDFs or sending email,
calling Stripe directly. **No Redis keys of any kind.**

---

## 2. Package layout

```
com.flashseats.order
├── controller   OrderController
├── service      OrderService + impl (orchestration), OutboxPublisher
├── facade       OrderFacade + impl
├── repository   OrderRepository, OrderItemRepository, OutboxEventRepository
├── model        Order, OrderItem, OutboxEvent, OrderStatus
├── dto          CheckoutRequestDTO, OrderReceiptDTO, OrderItemDTO, OrderSummaryDTO
├── event        PaymentSettledEventListener   ← webhook reconciliation
└── exception    InvalidHold(409), PaymentFailed(402), OrderNotFound(404),
                 OrderAlreadyExists(409), SaleWindowClosed(409)
```

---

## 3. Schema

```sql
CREATE TABLE orders (
    id                       BIGSERIAL PRIMARY KEY,
    order_number             VARCHAR(64)  NOT NULL UNIQUE,
    hold_token               VARCHAR(64)  NOT NULL UNIQUE,   -- ADR-002
    user_session_id          VARCHAR(255) NOT NULL,
    user_email               VARCHAR(255) NOT NULL,
    receipt_token            VARCHAR(128) NOT NULL,          -- signed capability (ADR-010)
    event_id                 BIGINT       NOT NULL,
    total_amount_cents       BIGINT       NOT NULL CHECK (total_amount_cents >= 0),
    currency                 CHAR(3)      NOT NULL DEFAULT 'USD',
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    payment_transaction_ref  VARCHAR(64),
    stripe_payment_intent_id VARCHAR(255),
    payment_attempts         INT          NOT NULL DEFAULT 0,
    failure_reason           VARCHAR(255),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_session ON orders(user_session_id);
CREATE INDEX idx_orders_intent  ON orders(stripe_payment_intent_id);
CREATE INDEX idx_orders_email   ON orders(user_email);
```

**`UNIQUE(hold_token)` is the strongest overbooking guard in the system**, and v1 did not have it —
`orders` carried no reference to the hold, the payment, or the Stripe intent at all, making webhook
correlation and support lookups impossible.

```sql
CREATE TABLE order_items (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    event_id         BIGINT NOT NULL,
    tier_id          BIGINT NOT NULL,
    tier_name        VARCHAR(100) NOT NULL,   -- snapshot
    quantity         INT    NOT NULL CHECK (quantity > 0),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_tier  ON order_items(tier_id);   -- for the stock-drift query

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,      -- ORDER_CONFIRMED | ORDER_REFUNDED
    payload        JSONB       NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count    INT         NOT NULL DEFAULT 0,   -- added
    last_error     VARCHAR(500),                     -- added
    processed_at   TIMESTAMPTZ,                      -- added
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox_events(created_at) WHERE status = 'PENDING';
```

`retry_count`, `last_error` and `processed_at` were missing, as was any purge policy — the table
grew forever. Purge `PROCESSED` rows older than 7 days.

### Status

```
 (none) ──► PENDING ──► CONFIRMED    terminal, success
              │  ▲
              │  └── retry after decline (same order_number)
              ├──► FAILED            retryable; hold retained
              └──► REFUNDED          terminal; charged but seats unobtainable
```

---

## 4. Checkout

`POST /api/v1/orders/checkout`

```json
{
  "holdToken": "hld_9f8b2c1a4d3e2f10b98a",
  "userEmail": "buyer@example.com",
  "paymentMethodId": "pm_card_visa",
  "idempotencyKey": "cli_4f2a9c81e70b"
}
```

`userSessionId` comes from the `fsid` cookie, never the body.

```
 1. HoldFacade.getActiveHold(holdToken, sid)         → 409 if missing/expired/not yours
 2. CatalogFacade.getTierSummary(eventId, tierId)    → price + window
 3. windowStatus OPEN, or CLOSED within 15 min of sale_end_time  → else 409
 4. amountCents = priceCents × quantity              SERVER-SIDE ONLY (ADR-013)
 5. find-or-create orders row on hold_token, status = PENDING
 6. HoldFacade.extendHold(holdToken, 120)            once; ceiling 420s
 7. PaymentFacade.authorize(orderNumber, amountCents, currency, pmId, idempotencyKey)
 8. @Transactional {
        HoldFacade.consumeHold(holdToken)            settle-once claim
        orders.status = CONFIRMED, payment refs recorded
        INSERT order_items
        INSERT outbox_events (ORDER_CONFIRMED, PENDING)
    }
 9. 201 Created + OrderReceiptDTO
```

### Find-or-create (ADR-002)

| Existing row | Behaviour |
| :--- | :--- |
| none | insert `PENDING` |
| `PENDING` | `409` — charge in flight |
| `FAILED` | reset to `PENDING`, retry on the **same** `order_number` (≤ 3 attempts) |
| `CONFIRMED` | `200` — return the existing receipt (idempotent replay) |
| `REFUNDED` | `409` — terminal |

### Why charge before consuming

Consuming first would need a `CONSUMED → RELEASED` transition the hold state machine forbids, and
would briefly release inventory the buyer is actively paying for. Charging first means a hold is
only ever destroyed by a transaction that is about to commit.

v1 specified **both** orderings across two different documents, and also specified two competing
orchestrators (`order` driving, and `PaymentSucceededEvent → order`). ADR-001 settles it.

---

## 5. Failure paths

| Case | Handling |
| :--- | :--- |
| Hold missing / expired / not yours | `409 HOLD_EXPIRED_OR_INVALID`. Nothing charged |
| Card declined | `FAILED`, **hold retained**, `402` with `retryable: true`, `attemptsRemaining` |
| 4th attempt | `402 PAYMENT_ATTEMPTS_EXHAUSTED`; hold released |
| Double submit | `payment:inflight` SETNX → `UNIQUE(hold_token)` → Stripe `Idempotency-Key` |
| Gateway timeout | `503`; order stays `PENDING`; the webhook settles it |
| **Charge OK, commit fails** | `PaymentFacade.refund()`, `REFUNDED`, `ORDER_REFUNDED` outbox event |
| **Webhook arrives, hold gone** | Refund, `REFUNDED`, buyer notified. v1 would have confirmed an order for re-sold seats (ADR-012) |
| Sale window closed | `409 SALE_WINDOW_CLOSED` |

### Webhook reconciliation

```java
@ApplicationModuleListener
void on(PaymentSettledEvent e) {
    // idempotent: no-op unless the order is still PENDING
    // hold consumable  → CONFIRMED + ORDER_CONFIRMED outbox event
    // hold gone        → refund + REFUNDED + ORDER_REFUNDED outbox event
}
```

This is the only inbound cross-module event, and it keeps the facade graph acyclic: `order → payment`
synchronously, `payment → order` only by event (ADR-005).

---

## 6. Interfaces

| Method | Path | Auth |
| :--- | :--- | :--- |
| `POST` | `/api/v1/orders/checkout` | `fsid` |
| `GET` | `/api/v1/orders/{orderNumber}` | `fsid` match **or** `?receiptToken=…` |

v1 left the lookup fully public against a guessable `TK-98213` reference, returning the buyer's
email — an IDOR (ADR-010).

```java
public interface OrderFacade {
    OrderSummaryDTO getOrderSummary(String orderNumber);
    boolean         isOrderConfirmed(String orderNumber);
}
```

### Outbox payload (ADR-015)

Self-contained — `notification` calls no facades and knows nothing about `catalog`:

```json
{
  "eventType": "ORDER_CONFIRMED",
  "orderNumber": "TK-98213",
  "receiptToken": "rcp_a91f…",
  "userEmail": "buyer@example.com",
  "totalAmountCents": 15000,
  "currency": "USD",
  "confirmedAt": "2026-08-30T10:04:12Z",
  "event": {
    "eventId": 101,
    "title": "Summer Fest 2026",
    "venueName": "Riverside Arena",
    "startTime": "2026-09-14T19:00:00Z"
  },
  "items": [
    { "tierId": 501, "tierName": "VIP Admission", "quantity": 2, "unitPriceCents": 7500 }
  ]
}
```

`items` is an **array**. v1's payload was flat (`tierName`, `quantity`) and would have rendered a
wrong PDF for any multi-tier order.

### Outbox publisher

```sql
SELECT * FROM outbox_events WHERE status = 'PENDING'
 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100;
```

`FOR UPDATE SKIP LOCKED` stops three replicas from publishing every event three times (ADR-009).

---

## 7. Changes from v1

1. Single checkout orchestration settled: `order` drives, charge first, consume second (ADR-001).
2. `hold_token UNIQUE` + find-or-create retry semantics (ADR-002).
3. `payment_transaction_ref`, `stripe_payment_intent_id`, `currency`, `receipt_token`,
   `payment_attempts`, `event_id`, `failure_reason` columns added.
4. Server-side pricing; no client-supplied amount (ADR-013).
5. Webhook reconciliation with auto-refund when the hold is gone (ADR-012).
6. `REFUNDED` is now actually reachable — v1 defined the enum value and never set it.
7. Order lookup access-controlled (ADR-010).
8. Outbox: `SKIP LOCKED`, retry columns, purge policy, complete payload with a line-item array.
9. Email collected at checkout — v1 required it `NOT NULL` but collected it nowhere.
10. Sale-window enforcement with a 15-minute post-close checkout grace (ADR-016).
