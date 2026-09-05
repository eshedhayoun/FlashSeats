# Module: `notification`

> **Status:** aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md) and
> [`../05-global-standards.md`](../05-global-standards.md). Structural rewrite to the §10 template
> is pending.

**Package:** `com.flashseats.notification` · **Phase:** 4 · **Storage:** PostgreSQL + RabbitMQ + SMTP

---

## 1. Scope

Consumes finalised order events, renders PDF tickets with Apache PDFBox, compiles HTML email bodies
with Thymeleaf, and delivers via SMTP — entirely off the checkout request path.

**Forbidden:** reading inventory, changing order status, validating payments, managing queue
positions, **and calling any other module's facade.**

That last rule is enforced by design: the outbox payload is a complete, self-contained snapshot, so
this module never needs to ask `catalog` or `order` anything (ADR-015).

---

## 2. Package layout

```
com.flashseats.notification
├── config       RabbitTopologyConfig, MailConfig, ThymeleafConfig
├── controller   NotificationAdminController
├── consumer     OrderConfirmedConsumer, OrderRefundedConsumer, DlqInspector
├── service      TicketPdfRenderer (PDFBox), EmailComposer (Thymeleaf),
│                EmailDispatcher (JavaMailSender), NotificationLogService
├── facade       NotificationFacade + impl
├── repository   NotificationLogRepository
├── model        NotificationLog, NotificationStatus, NotificationKind
├── dto          OrderConfirmedPayload, OrderRefundedPayload, NotificationStatusDTO
└── template     ticket-confirmation.html, refund-notice.html
```

---

## 3. Schema

```sql
CREATE TABLE notification_logs (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(64)  NOT NULL,
    kind            VARCHAR(32)  NOT NULL,   -- TICKET_DELIVERY | REFUND_NOTICE
    recipient_email VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- PENDING | SENT | FAILED | DLQ
    retry_count     INT          NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification UNIQUE (order_number, kind)
);
```

**`UNIQUE(order_number, kind)` is the idempotency guarantee**, and `kind` is what makes it possible
to send a buyer both a ticket email and — in the refund path — a separate notice.

v1 indexed `order_number` without a unique constraint and deduplicated with a preceding `SELECT`.
Two workers could both pass that `SELECT` and both send. Two tickets to one buyer is a support
incident; two *charges* would be worse, and the same pattern was in the outbox.

---

## 4. RabbitMQ topology

```
order.events.exchange  (topic, durable)
├── order.confirmed ──► notification.order-confirmed.queue
└── order.refunded  ──► notification.order-refunded.queue
        │  x-dead-letter-exchange     : notification.dlx.exchange
        │  x-dead-letter-routing-key  : notification.dead-letter
        ▼
notification.dlx.exchange (direct) ──► notification.order-confirmed.dlq
```

Manual acknowledgement mode. Prefetch 10.

### Failures are classified before they are retried (ADR-029)

| Class | Examples | Action |
| :--- | :--- | :--- |
| **Transient** | SMTP timeout, broker blip, transient OOM | 3 retries — 5 s / 30 s / 2 min → DLQ |
| **Deterministic** | Thymeleaf charset/encoding failure, malformed payload, PDFBox font or glyph error, invalid recipient | **straight to DLQ, no retries** |
| **Poison** | `x-death` count ≥ 5, any class | straight to DLQ + alarm |

A Thymeleaf encoding exception fails identically on every attempt. Retrying it three times over
2.5 minutes delays every other message, produces three identical stack traces, and reaches the same
DLQ. The `x-death` cap is the backstop against a render failure that corrupts consumer state and
produces an infinite redelivery loop.

Retry exists for *transient* failures — the same rule the payment module applies to card
declines (ADR-014), stated here for the async path.

---

## 5. Consumption

```
tx1 (short):  INSERT notification_logs (order_number, kind, recipient_email, status='PENDING')
                └─ unique violation ⇒ already handled ⇒ basicAck, stop   ← idempotency guard
              COMMIT

   NO TRANSACTION OPEN for any of the following:
2. PDFBox renders the ticket in memory (one page per line item)
3. Thymeleaf renders the HTML body
4. JavaMailSender → SMTP (Mailpit locally)

tx2 (short):  UPDATE notification_logs SET status='SENT', sent_at=now()
5. basicAck
✗ exception ⇒ basicNack(requeue=false) ⇒ retry chain ⇒ DLQ, status='DLQ'
```

**Steps 2–4 must not run inside a transaction** (ADR-023). PDF rendering is CPU-bound and SMTP is a
network call; holding a pooled connection across either would starve checkout, because under virtual
threads the connection pool is the system's real concurrency ceiling.

**Insert first, send second.** The unique-constraint violation — not a `SELECT` — is what stops a
duplicate, because it is atomic and a `SELECT` is not.

A crash between steps 4 and 5 can resend once on redelivery: at-least-once delivery of an email is
the accepted trade-off against never sending it at all. Stated so it is a known property.

### Payload (ADR-015)

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

`items` is an **array**, and the payload carries the event's title, venue and start time.

v1's payload was flat — `tierName` and `quantity` as scalars — so any multi-tier order would have
rendered a wrong PDF. It also included `eventName`, which this module had no legal way to obtain,
since it may not call `CatalogFacade`.

### Refund notice

`ORDER_REFUNDED` renders `refund-notice.html`: what was charged, what was refunded, why the seats
could not be delivered, and expected timing. This path exists because a charge can settle after the
hold is gone (ADR-012) — v1 had no way to tell the buyer anything at all in that case.

---

## 6. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `POST` | `/api/v1/admin/notifications/resend/{orderNumber}` | admin |
| `GET` | `/api/v1/admin/notifications/logs/{orderNumber}` | admin |
| `GET` | `/api/v1/admin/notifications/dlq` | admin |

```java
public interface NotificationFacade {
    /** Bypasses RabbitMQ; renders and sends synchronously. Clears the SENT guard first,
     *  so it is an explicit admin override rather than an accidental duplicate. */
    void resend(String orderNumber, NotificationKind kind);

    NotificationStatusDTO getStatus(String orderNumber);
}
```

---

## 7. Edge cases

| Case | Handling |
| :--- | :--- |
| Duplicate message | Unique violation on insert → ack and skip |
| Two workers, same message | One insert wins; the other acks and skips |
| Worker crashes mid-render | Unacked → redelivered → unique violation → skip if already `SENT` |
| Crash between send and status update | The row is marked **`SENT`**, not `DLQ` (ADR-042). The redelivery finds it `SENT`, wins no claim, and is quietly acknowledged. Marking it `DLQ` would make it re-claimable under ADR-038 and send the buyer a **second** ticket — the one thing that claim design promises cannot happen |
| PDF text outside WinAnsi (Hebrew, CJK, emoji) | Sanitised before drawing (ADR-040 sibling fix): accents transliterate, anything else becomes `?`, and a warning is logged. The standard-14 fonts throw on these, and a font failure is *deterministic* — so it went straight to a DLQ that has no replay endpoint, costing a **paid** buyer their ticket outright. Embedding a Unicode TTF is the Stage 4 answer |
| SMTP down | 3 retries → DLQ → admin replay |
| PDF render failure (font/glyph/charset) | **Deterministic** → straight to DLQ + alarm, no retries (ADR-029) |
| PDFBox memory exhaustion | Transient → retry chain. Renderer is bounded: streamed output, one page per item, hard page cap |
| Redelivery loop | `x-death` ≥ 5 → DLQ + alarm regardless of class |
| Invalid recipient address | Immediate DLQ, no retries — retrying a malformed address never helps |
| RabbitMQ down | Outbox retains `PENDING` rows; drains on recovery. No orders lost |
| Multi-tier order | One PDF page per line item |
| Admin resend | Explicit override; a new `notification_logs` row is written |

**Exceptions:** `PdfGenerationException` (retry) · `EmailDeliveryException` (retry) ·
`InvalidRecipientException` (no retry) · `NotificationLogNotFoundException` 404.

---

## 8. Changes from v1

1. `UNIQUE(order_number, kind)` + insert-then-send replaces `SELECT`-based deduplication (ADR-015).
2. `kind` column added; `REFUND_NOTICE` path and template added (ADR-012).
3. Payload is a complete snapshot with a line-item **array** — multi-tier orders now render
   correctly, and no facade call is needed.
4. `order.refunded` routing key and queue added.
5. Invalid recipients dead-letter immediately instead of burning three retries.
6. At-least-once delivery documented explicitly as an accepted property.
7. DLQ inspection endpoint added.

### Added in the 2nd pass

8. **Rendering and SMTP explicitly moved outside any transaction**; the consumer is now two short
   transactions with the slow work between them (ADR-023).
9. Consumer declared to run on all replicas, made safe by the unique constraint rather than by
   coordination.
10. Error codes aligned to the canonical registry (std §2).

### Added in the 3rd pass

11. **Failure classification** — deterministic render failures bypass the retry chain entirely, and
    an `x-death` cap backstops redelivery loops (ADR-029).
