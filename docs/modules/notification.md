# Module: `notification`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.notification` · **Storage:** PostgreSQL · **Depends on:** nothing

---

## 1. Scope

Turns a confirmed order into a PDF ticket in a buyer's inbox.

**This module has zero outbound module dependencies** — it imports no other `com.flashseats` package.
Everything reaches it over RabbitMQ. That is what lets fulfilment be switched off
(`flashseats.notification.enabled=false`) while the rest of the system stays fully functional: orders
still commit and outbox rows still queue, they are simply not delivered until a replica with it
enabled drains them.

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `notification_logs` | **`UNIQUE(order_number, kind)`**, recipient, status, retry count, failure reason, sent at |

**The unique constraint *is* the idempotency guarantee.** The consumer inserts the row **before**
rendering or sending and lets the violation stop a duplicate. A preceding `SELECT` is a race that two
workers both pass, which is how a buyer receives two tickets.

The insert is `ON CONFLICT DO NOTHING` plus a rowcount — **not** `saveAndFlush` in a try/catch. A
caught `DataIntegrityViolationException` leaves the transaction rollback-only, so the `return` throws
`UnexpectedRollbackException` at commit (ADR-038).

`V8` adds a **partial index on `status = 'DLQ'`** — the rows an operator looks for, not the millions
they never will.

---

## 3. What it exposes

| Method | Path | Auth |
| :--- | :--- | :--- |
| `GET` | `/api/v1/admin/notifications/dlq` | `ROLE_ADMIN` |

The **resend** lives in `order`, not here: the payload to replay is in `outbox_events`.

**No facade.** Nothing calls into this module synchronously.

---

## 4. The consumer shape

Fixed by ADR-023:

1. **claim** the log row — one short transaction, committed
2. **render and send** — no transaction open
3. **record the outcome** — one short transaction
4. **acknowledge**

A crash between sending and acknowledging can resend once on redelivery. At-least-once delivery of an
email beats a design that can silently never send it.

**Failures are not retried here** (ADR-029). A malformed payload or a render failure fails
identically every time, so retrying burns minutes, delays every other message, produces three
identical stack traces and reaches the same dead-letter queue anyway. Transport failures are the
broker's concern.

**`DLQ` means the work did not happen.** A dead-lettered row is deliberately re-claimable so a replay
actually sends — but the mail server has already accepted the message by the time the outcome can
fail to record, and marking *that* row `DLQ` would authorise a second ticket. So a delivery whose
bookkeeping failed is marked **`SENT`**, and the redelivery finds it, wins no claim, and is quietly
acknowledged (ADR-042).

---

## 5. Rendering

The ticket is a PDF. **Operator-supplied text must not reach a standard-14 font**: `showText` throws
on anything outside WinAnsi, deterministically, so a Hebrew event title would cost a paid buyer their
ticket with no retry that could help (ADR-042).

---

## 6. Known gaps

| Gap | Detail |
| :--- | :--- |
| **`notification.order-refunded.queue` has no consumer** | `order` writes `ORDER_REFUNDED` outbox rows and they queue here forever on a durable broker. The refund-notice template is unbuilt. Decide: build it, or stop writing the rows |
| **The renderer is not reachable outside the consumer** | ADR-050 moves it to `shared` so `order` can serve a ticket download. **Specified, not built** |
| **No DLQ depth alarm** | The DLQ is listable by an operator; nothing alarms on it |
| **Email is never verified** | The address is taken from the checkout body. A typo is currently unrecoverable |

---

## 7. What it must never do

- Import another `com.flashseats` domain package. It has no edges and must keep none.
- `SELECT`-then-send for idempotency.
- Retry a deterministic failure.
- Write `DLQ` once the mail server has accepted the message.
- Render or send inside a transaction.
