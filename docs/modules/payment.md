# Module: `payment`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.payment` · **Storage:** PostgreSQL + Redis · **Depends on:** `shared`

---

## 1. Scope

Authorisation, refund, the duplicate-charge guard, and the provider's webhook. **`payment` calls no
facade.** Adding one would make the graph cyclic, because `order → payment` already exists (ADR-005)
— which is exactly why the webhook reports settlement as an **event** rather than a call.

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `payment_transactions` | order number, hold token, amount, status, gateway reference, failure reason, attempt |
| `webhook_events` | the provider's event id as primary key, event type, intent id, hold token, received/processed timestamps |

| Redis key | TTL | Purpose |
| :--- | :--- | :--- |
| `payment:inflight:{holdToken}` | 90 s | duplicate-charge guard |

**The guard is anchored to the hold, not to a client-chosen idempotency key** (ADR-014). A client
that sends a fresh key per retry — or none — would otherwise bypass it entirely. The hold token is
the one value that identifies "this purchase" and that the client cannot mint.

**`webhook_events` is a claim, not a log** (ADR-053). The primary key *is* the replay guard: the
insert is `ON CONFLICT DO NOTHING` and the rowcount is the whole answer. `processed_at IS NULL` means
*in flight*, never *failed* — a settlement that throws deletes its row, so the provider's redelivery
finds a clean claim.

---

## 3. What it exposes

**One endpoint:** `POST /api/v1/payments/webhook`. Unauthenticated by necessity — the provider cannot
hold a session — so the signature over the **raw request bytes** is the only gate, and a failure is
`400 WEBHOOK_SIGNATURE_INVALID`. It is deliberately **not** exempt from rate limiting.

**Facade:** `authorize(AuthorizeCommand) → PaymentResult` · `refund(ref, amount, reason) → RefundResult`.
Both are called only by `order`.

**Event:** `PaymentSettledEvent` — the one cross-module event in the system (ADR-005). Published
synchronously so a failed settlement can become a non-2xx and earn a redelivery. **Only a definite
failure refunds**; anything unresolved propagates and is retried (ADR-056). The *type*
dependency runs `order → payment`, which already exists; the event is what keeps the runtime
direction from closing the loop.

`PaymentResult` carries a `clientSecret`, populated only alongside `requiresAction`. It is the one
value in this module the browser ever sees, it is scoped to a single intent, and it confers nothing
else.

---

## 4. What is real and what is not

| Layer | Status |
| :--- | :--- |
| The inflight guard, anchored to the hold | **real** |
| The transaction ledger and attempt counting | **real** |
| Attempt ceiling (3 per hold) | **real** |
| The gateway | **real** — Stripe, server-confirmed PaymentIntents (ADR-052) |
| An in-process stub, selected by default | **real, and load-bearing** — `dev`, `test`, the load harness and every drill run the full journey with no keys and no network |
| Circuit breaker around every gateway call | **real** — counts transport failures only, never declines |
| Webhook ingestion, signature verification, replay protection | **real** (ADR-053) |
| 3-D Secure | **real** — `402 PAYMENT_ACTION_REQUIRED` + `clientSecret`, resumed by re-POSTing checkout (ADR-054) |

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
| **A card cannot be swapped mid-challenge** | While a 3-D Secure intent is outstanding, every resubmission re-reads *that* intent, so a different card in the body is ignored until the hold expires. Accepted: the alternative is a second charge against a hold that already has one in flight (ADR-054) |
| **Checkout does not survive a Redis outage** | It opens with `SETNX payment:inflight:{holdToken}`, which fails closed. For a payment that is the right direction, but it is a written-down limitation rather than a resilience feature |
| **No decline-ratio metric** | `flashseats.payment.decline.ratio` is specified in `03` §7 and not built. `flashseats.payment.refund.failed` **is** built, and any non-zero value is money owed to a named buyer |
| **A failed refund still needs a human** | It is counted and written into `failure_reason` rather than silently reported as refunded, but nothing retries it — and nothing should, automatically |
| **A webhook that keeps failing keeps being redelivered** | Correct, and unbounded: the claim is released every time, so a permanently broken settlement is retried on the provider's schedule until it gives up. Visible in the logs, not in a metric |
| **Live-provider coverage is a script, not a test** | The suite runs the stub. `docker/scripts/stripe-check.sh` is the only thing that proves the real account, the real status mapping and the real webhook secret agree |

---

## 6. What it must never do

- Call any facade. Not one. The graph depends on it.
- Accept an amount from a client. `order` computes it from `CatalogFacade`.
- Key idempotency on anything the client chooses.
- Finalise an order whose seats are gone — the webhook path re-checks the hold and refunds (ADR-012).
- Parse a webhook body before verifying its signature, or bind it to anything but a raw string.
- Keep a webhook claim whose settlement failed (ADR-038).
- Start a second charge for a hold whose intent is awaiting authentication (ADR-054).
- Refund on anything but a **definite** failure to obtain the seats (ADR-056).
