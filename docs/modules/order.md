# Module: `order`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.order` · **Storage:** PostgreSQL only
**Depends on:** `hold`, `catalog`, `payment`, `queue`, `shared`

---

## 1. Scope

Checkout orchestration, the order ledger, the transactional outbox, and — because it is the only
module that can legally read all three of the ledger's tables — **stock reconciliation and rebuild**.

This module owns **no Redis key at all.**

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `orders` | order number, **`UNIQUE(hold_token)`**, session, email, receipt token, status, amount, attempts, `version` |
| `order_items` | per-tier lines: tier, quantity, unit price |
| `outbox_events` | aggregate, type, JSON payload, status, `claimed_at` |
| `order_number_seq` | a database sequence, so three replicas cannot mint the same number |

`UNIQUE(hold_token)` is the single-use guard: **one hold can never become two orders** (ADR-002).

**The `orders` row decides how a settled charge ends** (ADR-064). Every status change is a
compare-and-set — on `version` for an entity write, or a conditional update that bumps it — so the
checkout and the webhook, settling the same charge at once, cannot both win. Confirming needs the
order `PENDING` or `FAILED`; claiming a refund needs the same; whichever commits first leaves the
other nothing to do.

---

## 3. What it exposes

| Method | Path | Auth |
| :--- | :--- | :--- |
| `POST` | `/api/v1/orders/checkout` | `fsid` + a live hold |
| `GET` | `/api/v1/orders/{orderNumber}` | `fsid` **or** `receiptToken` |
| `GET` | `/api/v1/orders/{orderNumber}/ticket.pdf` | `fsid` **or** `receiptToken` |
| `POST` | `/api/v1/admin/events/{eventId}/rebuild-stock` | `ROLE_ADMIN` |
| `GET` | `/api/v1/admin/orders/{orderNumber}` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/notifications/resend/{orderNumber}` | `ROLE_ADMIN` |

The admin order view returns a **distinct shape** from the buyer's receipt. `receiptToken` is a
90-day bearer capability; an operator view has no business minting a durable impersonation link into
terminal history and any log that records bodies (ADR-048).

The resend is served here, not by `notification`, because the payload lives in `outbox_events`. One
new outbox row drives the whole existing pipeline.

**The ticket download is the recovery path for an unverified email** (ADR-050). It renders through
`shared`'s `TicketPdfRenderer`, so a downloaded ticket is byte-identical to the emailed one by
construction. Two guards: authorisation is *identical* to the receipt read, and **only a
`CONFIRMED` order has a ticket** — anything else answers `TICKET_NOT_AVAILABLE`, because rendering
for a `REFUNDED` order would mint a document that still admits someone at a door for money that has
already gone back. An unauthorised caller gets `404`, never `403`.

**Facade:** `findLatestOrder` (rehydration for `saleflow`) — and nothing else. A `getOrderSummary`
existed with zero callers anywhere and was deleted in Pass 7.

**Inbound event:** this module listens for `payment`'s `PaymentSettledEvent` — **the only legitimate
inbound edge into `order`**, and the only cross-module event in the system (ADR-005). It finishes a
purchase whose buyer never saw the response, by the same `confirm` transaction the synchronous path
uses, and refuses to confirm an order whose seats are gone (ADR-012, ADR-053). It races the checkout
that made the charge; a lost hold goes to the refund claim, which a confirmed order refuses (ADR-064).
A synchronous edge from `payment` would close a cycle and fail the build; the listener is the shape
that does not.

---

## 4. Checkout, in order

The sequence *is* the design (ADR-001):

```
0. already bought?          → return the receipt          ← must be first
1. validate the hold        → live, and this session's
2. price server-side        → from the tier, never the request
3. sale window              → OPEN, or CLOSED within the 15-min grace
4. find-or-create the order → UNIQUE(hold_token)
5. grant the one grace      → and ABORT if it cannot be granted
6. CHARGE                   → outside every transaction
7. ONE TRANSACTION          → consume the hold, confirm, write items, write the outbox row
8. AFTER_COMMIT             → best-effort cleanup, safe to lose
9. lost the hold post-charge→ the order row decides: receipt if confirmed, else refund and say so
```

**Step 0 must come first.** A successful purchase consumes its hold, so validating the hold first
answers a resubmission with `410 HOLD_EXPIRED` when the buyer in fact already owns the seats.

**Step 6 is outside every transaction.** A transaction spanning an external provider holds a pooled
connection across a network round trip, and under virtual threads the pool — not the thread count —
is the real concurrency limit, so one slow gateway would throttle checkout for everyone (ADR-023).

**Step 7 is one transaction.** The hold claim joins it, so if anything below fails the hold returns
to `ACTIVE` and expires normally. The outbox row is written *here*, not after: an order that is
confirmed but whose ticket was never queued is not a state this system can reach.

**Step 9 runs only on a lost hold, never on an ambiguous failure** (ADR-056, ADR-064). A hold gone
after the charge is not proof the seats are gone: only this order can consume its hold, so a
`CONSUMED` hold means the webhook already confirmed this purchase. The refund is *claimed* on the
order row first, and a confirmed order refuses the claim — the buyer gets the receipt. Any other
failure of step 7 — a pool timeout, a dropped connection — moves no money: the order becomes `FAILED`,
and the retry finds the charge that settled and confirms it **without charging again**. Before
answering `404`/`410` at step 1 or 5, checkout also looks for a confirmed order for the hold.

**A refund is claimed, then made, then announced.** The claim moves the order to `REFUNDED` with the
reason `refund pending: …`; the provider call comes after it; only a refund that went through queues
the `ORDER_REFUNDED` notice, at most once. A refused one sends nothing and is counted in
`flashseats.payment.refund.failed`.

**`PENDING` is in-flight, never terminal** (ADR-034). The row is committed before the charge, so any
exit that recorded no outcome would otherwise strand the buyer holding live seats behind a `409`
about a charge they never made. Two rules make it recoverable: every thrown exit marks the order
`FAILED`, and a `PENDING` row older than `stale-pending-seconds` is resumable on the **same order
number** — three declined attempts should not produce three references to explain to support.

**A decline does not release the hold.** The UX promises the buyer they can try another card.

**A 3-D Secure challenge exits at step 6 and re-enters at step 0.** It is thrown, so the existing
catch-all marks the order `FAILED` — resumable on the same order number, **no attempt consumed** —
and the buyer completes the challenge and re-POSTs the same body. There is no resume endpoint and
there must not be one: a second retry path would need its own idempotency story (ADR-054).

---

## 5. The outbox

Three short transactions, never one:

1. **claim** a batch `FOR UPDATE SKIP LOCKED`, mark `PROCESSING`, **commit**
2. **publish** to RabbitMQ — no transaction open
3. **mark `PROCESSED`**, and only for what the broker actually confirmed

Publishing inside the claim transaction would hold row locks across a broker round trip.

**A publisher confirm is not proof of delivery.** A confirm means the *broker* has the message, not
that a *queue* does — an exchange with no matching binding acks and discards, and the whole
notification topology sits behind a property. `mandatory` + publisher-returns is what closes that,
and a return is treated exactly like a nack (ADR-048).

**The wait is per batch, not per message.** A batch of 100 against a sick broker would otherwise be
100 sequential timeouts on the relay thread. Send the batch, await it once.

A row claimed but never published — a crash between the two — returns to `PENDING` after
`stale-claim-seconds`. At-least-once, which the consumer's unique constraint absorbs.

**This is hand-rolled deliberately.** The Spring Modulith event-publication starters were removed;
only `-starter-core` and `-starter-test` remain, purely for `ApplicationModules.verify()` (ADR-009).

---

## 6. Reconciliation and rebuild

**Why here.** The invariant spans three modules' tables — `ticket_tiers`, `order_items`,
`ticket_holds` — and `order` is the only module that can legally reach all three. Putting it in
`catalog` would give `catalog` its first outbound dependency and make the graph cyclic; reading the
other modules' tables with one native query would hide the same violation somewhere
`ApplicationModules.verify()` cannot see it.

**The drift gauge** recomputes `|counter − ledger|` every 60 s over **managed** events. The two SQL
sums are taken at `REPEATABLE_READ` so they see one instant — under `READ COMMITTED` a checkout
committing between them moves a hold from `ACTIVE` to `CONFIRMED` without either query seeing it,
inventing drift that does not exist. Redis and PostgreSQL are still not one snapshot, so **alarm on
sustained non-zero, not on one sample.**

**The rebuild** takes two ledger snapshots a settling window apart and writes the **smaller**. A
reserve decrements Redis just before its hold row commits, so a lone snapshot can miss a hold that is
about to exist and write an oversell created by the repair itself. The lock is transaction-scoped and
retaken for each snapshot rather than held across the sleep — sleeping inside a transaction is
exactly what ADR-023 forbids.

---

## 7. Known gaps

| Gap | Detail |
| :--- | :--- |
| **Checkout costs eight sequential transactions** | Steps 0, 1, 2, 4, 5, **both** of the payment store's `REQUIRES_NEW` transactions bracketing the gateway call, and 7 are each their own connection acquisition (the closing receipt read was removed in Pass 10). ADR-049 budgets admission against this figure, so the count is load-bearing rather than trivia |
| **The stub has no webhook** | A checkout whose commit failed ambiguously after the charge is resolved by the buyer's retry, or, with Stripe, by the webhook. Under the stub gateway a charge the buyer never retries stays settled and unresolved — stub money only (ADR-064) |
| **A refund interrupted mid-way is found by query, not by alarm** | A process killed between the refund claim and the provider call leaves an order `REFUNDED` whose reason still reads `refund pending:` (ADR-064) |

---

## 8. What it must never do

- Own a Redis key.
- Charge before confirming the hold extension, or consume the hold before charging.
- Let a client value reach an amount.
- Treat `PENDING` as terminal.
- Read a lost hold as lost seats, or refund on an ambiguous failure — the order row decides (ADR-064).
- Move money before the order row records why.
- Release the hold on a decline.
- Publish to the broker inside the outbox transaction, or mark `PROCESSED` before the broker confirms
  **and routes**.
- Return `receiptToken` from an admin endpoint.
- Use `@Modifying(clearAutomatically = true)` on the settle claim — it detaches every other entity in
  the transaction and the order's status change is silently discarded.
