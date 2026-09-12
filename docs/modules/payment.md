# Module: `payment`

> **Describes what is built.** No class names — see [`../../CLAUDE.md`](../../CLAUDE.md), "Updating
> the docs is part of the change".

**Package:** `com.flashseats.payment` · **Storage:** PostgreSQL + Redis · **Depends on:** `shared`

---

## 1. Scope

Authorisation, refund, and the duplicate-charge guard. **`payment` calls no facade.** Adding one
would make the graph cyclic, because `order → payment` already exists (ADR-005).

---

## 2. What it owns

| PostgreSQL | Contents |
| :--- | :--- |
| `payment_transactions` | order number, hold token, amount, status, gateway reference, failure reason, attempt |

| Redis key | TTL | Purpose |
| :--- | :--- | :--- |
| `payment:inflight:{holdToken}` | 90 s | duplicate-charge guard |

**The guard is anchored to the hold, not to a client-chosen idempotency key** (ADR-014). A client
that sends a fresh key per retry — or none — would otherwise bypass it entirely. The hold token is
the one value that identifies "this purchase" and that the client cannot mint.

---

## 3. What it exposes

**No endpoints.** There is no webhook controller: the gateway is a stub, and the Stripe webhook path
that ADR-005 describes as the one cross-module event is **specified, not built**.

**Facade:** `authorize(AuthorizeCommand) → PaymentResult` · `refund(ref, amount, reason) → RefundResult`.

Both are called only by `order`.

---

## 4. What is real and what is not

| Layer | Status |
| :--- | :--- |
| The inflight guard, anchored to the hold | **real** |
| The transaction ledger and attempt counting | **real** |
| Attempt ceiling (3 per hold) | **real** |
| The gateway itself | **a stub** — deterministic, no network |
| Webhook ingestion, signature verification, replay protection | **not built** |

Every idempotency layer around the charge is real; the charge is not. That is the correct order to
have built them in — the layers are where the concurrency bugs live.

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
| **No real gateway** | Stage 2. Swapping the stub should touch one interface and nothing else — that is the property the seam exists to preserve |
| **No webhook** | With a real gateway, `PaymentSettledEvent` becomes the only legitimate inbound edge into `order`, and it must stay event-shaped: a facade call would make the graph cyclic (ADR-005, ADR-012) |
| **Checkout does not survive a Redis outage** | It opens with `SETNX payment:inflight:{holdToken}`, which fails closed. For a payment that is the right direction, but it is a written-down limitation rather than a resilience feature |
| **No decline-ratio metric** | `flashseats.payment.decline.ratio` is specified in `03` §7 and not built |

---

## 6. What it must never do

- Call any facade. Not one. The graph depends on it.
- Accept an amount from a client. `order` computes it from `CatalogFacade`.
- Key idempotency on anything the client chooses.
- Finalise an order whose seats are gone — the webhook path, when it exists, must re-check (ADR-012).
