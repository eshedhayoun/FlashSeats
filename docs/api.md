# API

The HTTP contract. Base path `/api/v1`. The design behind it is in
[`architecture.md`](architecture.md).

## Conventions

- **Identity is the `fsid` cookie.** The server sets it (signed, `HttpOnly`) on the first request.
  Send `credentials: 'include'`; never send a session id in a body, header or query string.
- **Times** are ISO-8601 instants. Responses that drive a countdown carry `serverTime`; clients
  derive every timer from the server's clock, not the device's.
- **Limits come from the server** (`maxPerOrder`, TTLs, `attemptsRemaining`); clients render them.
- **Errors** are `application/problem+json` (RFC 7807). Switch on `code`, never on `detail`:

```json
{
  "type": "https://flashseats.dev/problems/payment-declined",
  "title": "Payment declined",
  "status": 402,
  "detail": "Your card was declined. Try a different card.",
  "code": "PAYMENT_DECLINED",
  "traceId": "0af7651916cd43dd",
  "retryable": true,
  "attemptsRemaining": 2
}
```

`code` and `traceId` are always present. `retryable`, `retryAfterSeconds` (with `429` and `503`),
`attemptsRemaining`, `expiresAt` and `violations` (with `400`) appear where they apply.

## Buyer endpoints

| Method | Path | Request | Success | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/events` | — | `200` list | Every published sale with its `windowStatus` |
| `GET` | `/events/{eventId}` | — | `200` | Event, tiers with availability (`PLENTY`, `LIMITED`, `SOLD_OUT`, `UNKNOWN`), `windowStatus`, `serverTime` |
| `GET` | `/sale/{eventId}/state` | — | `200` | The caller's position in the journey: `windowStatus`, `queue`, `hold`, `order`. Call it on every page load |
| `POST` | `/queue/join` | `{eventId, recaptchaToken?}` | `202` | Idempotent: a repeat join keeps the place. Allowed while `OPEN` or `PAUSED` |
| `POST` | `/queue/leave` | `{eventId}` | `204` | Idempotent. Rejoining starts at the back |
| `GET` | `/queue/stream?eventId=` | `Accept: text/event-stream` | SSE | Live updates, below |
| `GET` | `/queue/status?eventId=` | — | `200` | Polling fallback: `phase`, `position`, `aheadOfYou`, `estWaitSeconds`, `passToken`, `admissionExpiresAt` |
| `POST` | `/queue/admit` | header `X-Queue-Pass-Token`; `{eventId}` | `200` | Spends the 120 s pass for a 600 s `admissionToken` |
| `POST` | `/holds` | header `X-Admission-Token`; `{eventId, tierId, quantity}` | `201` | Reserves seats for 300 s: `{holdToken, expiresAt}` |
| `GET` | `/holds/{holdToken}` | — | `200` | |
| `DELETE` | `/holds/{holdToken}` | — | `204` | Releases the seats; the admission stays valid |
| `POST` | `/orders/checkout` | `{holdToken, userEmail, paymentMethodId, idempotencyKey}` | `201` / `200` | `201` on a new purchase, `200` replaying a completed one |
| `GET` | `/orders/{orderNumber}?receiptToken=` | — | `200` | Receipt. Needs the owning session or the receipt token |
| `GET` | `/orders/{orderNumber}/ticket.pdf?receiptToken=` | — | `200` PDF | Confirmed orders only |
| `POST` | `/session/reset` | `{}` (`application/json` only) | `204` | Starts over as a new visitor. Demo affordance |
| `POST` | `/payments/webhook` | Stripe event, `Stripe-Signature` | `200` | Provider callback only |

**Retrying checkout.** Re-POST the same body with the same `idempotencyKey` (one per hold). Checkout
is find-or-create on the hold, so a retry after a decline uses the same order number, a retry after
success returns the receipt, and a 3-D Secure challenge (`402 PAYMENT_ACTION_REQUIRED`, with a
`clientSecret`) is completed by re-POSTing after the browser finishes it. There is no separate resume
endpoint.

**Stub payment methods** (the default gateway): `pm_card_visa` succeeds, `pm_card_declined`
declines and keeps the seats, `pm_card_error` simulates a provider outage, and the client's 3-D
Secure option exercises the challenge.

### Server-sent events

`GET /queue/stream` sends these events. Broadcast events carry an `id` and are replayed on reconnect
(`Last-Event-ID`); a heartbeat comment arrives every 15 s.

| Event | When |
| :--- | :--- |
| `position-update` | Every 2 s while waiting; the position never increases |
| `queue-promoted` | The buyer's turn; carries the `passToken` |
| `tier-availability` | A tier's availability level changed |
| `sale-paused` · `sale-resumed` | An operator paused or resumed the sale; the buyer keeps their place |
| `sale-exhausted` | No stock left. Reverses if seats return |
| `sale-closed` | The sale window ended |

## Operator endpoints

HTTP Basic (`ROLE_ADMIN`) on every call: `401 ADMIN_AUTH_REQUIRED` without credentials,
`403 ADMIN_FORBIDDEN` with the wrong role.

| Method | Path | Success | Notes |
| :--- | :--- | :--- | :--- |
| `GET` | `/admin/events` | `200` | Every sale with its status, readiness and exact remaining stock per tier (`null` = no counter) |
| `POST` | `/admin/events` | `201` | `{title, description?, venueName, eventStartTime, saleStartTime, saleEndTime, tiers: [{tierName, priceCents, totalCapacity, maxPerOrder}]}`. 1–10 tiers; the sale opens at least 120 s from now. Created published and pre-warmed. Sales are never edited |
| `DELETE` | `/admin/events/{eventId}` | `204` | Only until 60 s before the sale opens |
| `POST` | `/admin/events/{eventId}/prewarm` | `200` | Creates missing counters while `UPCOMING` |
| `POST` | `/admin/events/{eventId}/pause` · `/resume` | `200` | Pause stops promotions and new holds; the line keeps forming and existing holds can pay |
| `POST` | `/admin/events/{eventId}/rebuild-stock` | `200` | Recounts stock from the ledger and re-vouches the counters |
| `GET` | `/admin/orders/{orderNumber}` | `200` | Support view of an order |
| `GET` | `/admin/notifications/dlq` | `200` | Dead-lettered notifications |
| `POST` | `/admin/notifications/resend/{orderNumber}` | `200` | Re-queues the order's ticket (`queued`, not yet delivered) |
| `GET` · `POST` | `/admin/bot/ip-rules` | `200` · `201` | `{ipAddress, action, reason?, expiresAt?}` |
| `DELETE` | `/admin/bot/ip-rules/{ipAddress}` | `204` | |
| `GET` | `/admin/bot/audit` | `200` | Recent refusals |

## Error codes

| Code | Status | Meaning |
| :--- | :--- | :--- |
| `VALIDATION_FAILED` | 400 | Invalid input; see `violations` |
| `NOT_FOUND` | 404 | No such endpoint or resource |
| `INTERNAL_ERROR` | 500 | Unexpected; quote `traceId` |
| `SERVICE_BUSY` | 503 | No database connection free in time. Retry after `Retry-After`; checkout retries are safe |
| `RATE_LIMITED` | 429 | Back off `retryAfterSeconds` |
| `BOT_VERIFICATION_FAILED` | 403 | The challenge scored too low; reload and retry |
| `IP_BLOCKED` | 403 | An operator blocked this address |
| `SESSION_INVALID` | 401 | Reload for a fresh session |
| `EVENT_NOT_FOUND` · `TIER_NOT_FOUND` | 404 | |
| `SALE_NOT_OPEN` | 409 | Not open yet; show the countdown |
| `SALE_CLOSED` | 409 | The sale has ended |
| `SALE_PAUSED` | 409 | Paused by an operator; retryable, the place in line is kept |
| `INVENTORY_UNAVAILABLE` | 503 | Stock counter missing or untrusted — a fault, **not** sold out |
| `QUEUE_PASS_INVALID` | 401 | Pass missing, spent or expired; rejoin |
| `ADMISSION_REQUIRED` | 401 | Not admitted to this sale |
| `ADMISSION_EXPIRED` | 410 | Admission ran out; rejoin |
| `INSUFFICIENT_STOCK` | 409 | Not enough seats in this tier; choose another |
| `QUANTITY_EXCEEDS_LIMIT` | 422 | Above the tier's `maxPerOrder` |
| `HOLD_LIMIT_EXCEEDED` | 409 | One active hold per sale; release it first |
| `HOLD_NOT_FOUND` | 404 | |
| `HOLD_EXPIRED` | 410 | Reservation gone; nothing was charged |
| `HOLD_ALREADY_SETTLED` | 409 | Internal; checkout answers with the order's outcome instead |
| `PAYMENT_DECLINED` | 402 | Retry with another card; see `attemptsRemaining` |
| `PAYMENT_ATTEMPTS_EXHAUSTED` | 402 | Three declines; terminal |
| `PAYMENT_ACTION_REQUIRED` | 402 | 3-D Secure; complete it and re-POST |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 503 | Provider down; seats kept, no attempt used |
| `DUPLICATE_PAYMENT` | 409 | A charge for this hold is in flight; poll `/sale/{eventId}/state` |
| `CHECKOUT_WINDOW_CLOSED` | 409 | More than 15 minutes after the sale ended |
| `INSUFFICIENT_TIME_REMAINING` | 409 | Too little hold time left to start a charge; nothing charged |
| `ORDER_REFUNDED` | 409 | Charged, but the seats were lost; the charge was refunded |
| `REFUND_FAILED` | 409 | Charged, seats lost, and the provider refused the refund; an operator is alerted |
| `ORDER_NOT_FOUND` | 404 | Also returned when the caller may not see the order |
| `TICKET_NOT_AVAILABLE` | 409 | The order has no ticket (not confirmed) |
| `WEBHOOK_SIGNATURE_INVALID` | 400 | Provider callback only |
| `ADMIN_AUTH_REQUIRED` · `ADMIN_FORBIDDEN` | 401 · 403 | Operator authentication |
| `PREWARM_WINDOW_CLOSED` | 409 | Pre-warm is only for upcoming sales |
| `STOCK_REBUILD_IN_PROGRESS` | 503 | Another rebuild holds the lock; retry |
| `EVENT_NOT_PAUSABLE` | 409 | Draft or cancelled event |
| `EVENT_NOT_DELETABLE` | 409 | The sale opens within 60 s or is open; pause instead |
| `NOTIFICATION_PAYLOAD_UNAVAILABLE` | 410 | The stored message aged out; cannot resend |
