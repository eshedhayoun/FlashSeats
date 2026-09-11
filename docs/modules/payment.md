# Module: `payment`

> **Status:** aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md) and
> [`../05-global-standards.md`](../05-global-standards.md). Structural rewrite to the §10 template
> is pending.

**Package:** `com.flashseats.payment` · **Phase:** 1 (stub) → 3 (Stripe) · **Storage:** PostgreSQL + Redis

---

## 1. Scope

Isolates the payment gateway. Creates and confirms charges, enforces idempotency, verifies webhook
signatures, issues refunds, and keeps a durable transaction ledger.

**Forbidden:** reading stock, managing queue positions, writing orders, generating tickets — **and
calling `HoldFacade`.**

That last one is new and deliberate. In v1 `payment` extended and released holds, which combined
with the corrected webhook path would have made the facade graph cyclic. Now: grace extension is
requested by `order`, a decline deliberately *retains* the hold, and abandonment is handled by the
TTL. `payment` has no outbound facade dependencies at all (ADR-005).

---

## 2. Package layout

```
com.flashseats.payment
├── controller   PaymentWebhookController      ← the only HTTP surface
├── service      StripeGatewayService, IdempotencyService, WebhookVerifier
├── facade       PaymentFacade + impl
├── repository   PaymentTransactionRepository, WebhookEventRepository
├── model        PaymentTransaction, PaymentStatus, WebhookEvent
├── dto          AuthorizeRequestDTO, PaymentResultDTO, RefundResultDTO
└── event        PaymentSettledEvent           ← webhook path only
```

**`POST /api/v1/payments/intent` and `/confirm` are removed.** Checkout has exactly one entry point,
`POST /api/v1/orders/checkout` (ADR-001). A public payment endpoint that accepted an amount was also
a price-tampering hole (ADR-013). The webhook receiver is the only endpoint this module exposes.

---

## 3. Schema

```sql
CREATE TABLE payment_transactions (
    id                       BIGSERIAL PRIMARY KEY,
    transaction_reference    VARCHAR(64)  NOT NULL UNIQUE,
    order_number             VARCHAR(64)  NOT NULL,
    hold_token               VARCHAR(64)  NOT NULL,
    user_session_id          VARCHAR(255) NOT NULL,
    stripe_payment_intent_id VARCHAR(255) UNIQUE,
    client_idempotency_key   VARCHAR(64),
    amount_cents             BIGINT       NOT NULL CHECK (amount_cents >= 0),
    currency                 CHAR(3)      NOT NULL DEFAULT 'USD',
    status                   VARCHAR(32)  NOT NULL,
    failure_code             VARCHAR(64),
    failure_reason           VARCHAR(255),
    attempt_number           INT          NOT NULL DEFAULT 1,
    refunded_amount_cents    BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_pay_order ON payment_transactions(order_number);
CREATE INDEX idx_pay_hold  ON payment_transactions(hold_token);

-- Webhook replay protection
CREATE TABLE webhook_events (
    stripe_event_id VARCHAR(255) PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
```

`webhook_events` is new. Stripe redelivers on any non-2xx, so the handler must be idempotent on
`stripe_event_id` — the primary key is that guarantee. `currency`, `attempt_number`,
`failure_code` and `refunded_amount_cents` were also missing.

> **Naming:** v1 used `payment_transactions` in this document and `payments` in the end-to-end doc.
> The table is **`payment_transactions`**.

### Status

```
 INITIATED ──► PROCESSING ──► SUCCEEDED ──► REFUNDED
                    └───────► FAILED
```

---

## 4. Idempotency — three layers (ADR-014)

| Layer | Mechanism | Catches |
| :--- | :--- | :--- |
| 1 | `UNIQUE(hold_token)` on `orders` | the durable guarantee |
| 2 | `SETNX payment:inflight:{holdToken}` EX **90** | duplicate clicks, in memory |
| 3 | Client `idempotencyKey` → Stripe `Idempotency-Key` header | network-level retries at Stripe |

Note what anchors this: **the hold**, not a client-chosen string.

v1 keyed the whole guard on a client-generated `idempotencyKey`, so a client that regenerated the
key on retry bypassed it completely. Its 24-hour `IN_PROGRESS` TTL also meant an app crash mid-charge
locked that key for a day, and "returns the stored result from the initial attempt" is impossible
while the first attempt is still in flight. The 90-second TTL now bounds the lock to roughly the
gateway timeout.

At most **3** charge attempts per hold, tracked in `orders.payment_attempts`.

---

## 5. Interfaces

```java
public interface PaymentFacade {
    /** Charges an order. The amount is computed by `order` from CatalogFacade —
     *  never supplied by a client (ADR-013). */
    PaymentResultDTO authorize(String orderNumber, String holdToken,
                               long amountCents, String currency,
                               String paymentMethodId, String clientIdempotencyKey);

    /** Full or partial refund. Used for compensation when a charge succeeds
     *  but the seats cannot be delivered. */
    RefundResultDTO refund(String transactionReference, long amountCents, String reason);

    PaymentStatus getStatus(String orderNumber);
}

public record PaymentResultDTO(
    String  transactionReference,
    PaymentStatus status,
    String  stripePaymentIntentId,
    String  failureCode,
    String  failureReason,
    boolean retryable,          // true for declines; false for hard failures
    boolean requiresAction) {}  // 3-D Secure
```

`retryable` is what lets `order` distinguish "try another card" (hold retained) from "stop"
(hold released). v1 collapsed both into a single `PaymentFailedEvent` whose documented purpose was
to release the hold — directly contradicting the same document's statement that a decline keeps the
hold `ACTIVE` so the user can retry.

### Webhook

`POST /api/v1/payments/webhook` — signature-verified, gateway only.

```
1. verify Stripe-Signature (constant-time); invalid → 400, no processing
2. INSERT webhook_events (stripe_event_id) ; PK violation ⇒ already handled ⇒ 200
3. payment_intent.succeeded → SUCCEEDED  → publish PaymentSettledEvent
   payment_intent.payment_failed → FAILED → publish PaymentSettledEvent
   charge.refunded → REFUNDED
4. 200 quickly; all real work is async
```

```java
public record PaymentSettledEvent(
    String orderNumber, String transactionReference,
    String stripePaymentIntentId, PaymentStatus status,
    long amountCents, String currency, Instant settledAt) {}
```

`order` listens and finalises only if the order is still `PENDING`. **If the hold is gone, `order`
refunds automatically and notifies the buyer.** v1 had the webhook unconditionally "complete the
order in the background", which could confirm an order for seats another buyer already owned
(ADR-012).

---

## 6. Resilience

Resilience4j around every Stripe call: circuit breaker (50 % failure rate over 20 calls, 30 s open),
retry (3 attempts, exponential backoff, **only** on network/5xx — never on a decline), 10 s timeout.

When the circuit is open, `order` returns `503` with `retryAfterSeconds` and **holds are retained** —
a gateway outage must not cost buyers their seats.

**Circuit breakers belong only here**, at the external boundary. Wrapping an in-process facade call
in one adds latency and a failure mode while protecting nothing (`05-global-standards.md` §6).

**Retry classification.** A card decline is a *correct answer*, not a fault: retrying it triples the
fraud signal against the customer's card and changes nothing. Retry connect/read timeouts and `5xx`
only — never `4xx`, never declines.

**Fail closed.** Unlike reCAPTCHA, payment failures never degrade into an implicit success.

**No `@Transactional` around a Stripe call**, ever (ADR-023). Persist `INITIATED`, commit, call the
gateway, then persist the outcome in a second short transaction.

---

## 7. Edge cases

| Case | Handling |
| :--- | :--- |
| Card declined | `FAILED`, `retryable = true`; hold retained |
| Double submit | `payment:inflight` → `UNIQUE(hold_token)` → Stripe `Idempotency-Key` |
| Timeout after Stripe charged | Order stays `PENDING`; webhook settles it |
| Webhook redelivered | `webhook_events` PK violation → 200, no reprocessing |
| Webhook arrives before the API response | Both idempotent; whichever is first wins |
| Webhook signature invalid | `400`, logged, not processed |
| Hold gone at webhook time | Auto-refund + `REFUNDED` + buyer notified |
| 3-D Secure required | `requiresAction`; `order` already extended the hold by 120 s |
| Stripe unreachable | Circuit opens → `503`; holds retained |
| Refund fails | Logged, alarmed, escalated to admin. Manual reconciliation |

**Exceptions:** `PaymentDeclined` 402 · `DuplicatePayment` 409 · `PaymentGatewayUnavailable` 503 ·
`InvalidWebhookSignature` 400 · `RefundFailed` 500.

---

## 8. Changes from v1

1. `payment → hold` facade edge **removed**; the graph is now acyclic (ADR-005).
2. Public `/payments/intent` and `/confirm` removed; one checkout entry point (ADR-001).
3. Amount computed server-side by `order` (ADR-013).
4. Idempotency anchored to `hold_token`; `IN_PROGRESS` TTL 24 h → 90 s (ADR-014).
5. `PaymentSucceededEvent` / `PaymentFailedEvent` replaced by a single `PaymentSettledEvent` on the
   webhook path only; the decline-vs-abandon contradiction resolved via `retryable`.
6. `webhook_events` table added for replay protection.
7. `currency`, `attempt_number`, `failure_code`, `refunded_amount_cents` columns added.
8. Webhook may no longer confirm an order whose hold is gone (ADR-012).
9. Retry policy narrowed: network/5xx only, never declines.

### Added in the 2nd pass

10. **3-D Secure second leg specified**: `requiresAction` → `402 PAYMENT_ACTION_REQUIRED` with a
    `resumeUrl`; the client runs `stripe.handleNextAction()` then calls
    `POST /api/v1/orders/checkout/resume`. v1 returned `requiresAction` and defined nothing after it.
11. Gateway calls explicitly excluded from every transaction boundary (ADR-023).
12. Circuit-breaker placement restricted to external boundaries; fail-closed stated (std §6).
13. Error codes aligned to the canonical registry (std §2).
