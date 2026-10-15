# FlashSeats — Front-End Design Specification

**Stack:** React 18 + TypeScript (Vite) · MUI 6 · native `EventSource` · Playwright for §8
**Backend contract:** [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) ·
[`docs/05-global-standards.md`](docs/05-global-standards.md) ·
[`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md)

> **Read this first if you are new.** The client this document specifies is **built**: the React app
> in `frontend/` (served at `:8080` by the cluster's nginx image, or at `:5173` with `npm run dev`).
> `src/main/resources/static/index.html` is a minimal API demo that makes the backend walkable with no
> build step; it says so on screen and is not the deliverable. Where this document describes something
> the server does not offer, it says **"specified, not built"**.
>
> Two facts shape every client decision here:
>
> 1. **The system targets 3–10 concurrent sales** ([`03`](docs/03-end-to-end-flow.md) §2), so every
>    piece of client state is **scoped by `eventId`** — rule 5.
> 2. **A ticket is retrievable, not only emailed** (ADR-050): V5 offers a download.

---

## 0. Five rules that override everything else

**1. The server owns the clock.** Compute an offset **once** per page load and derive every countdown
from it. Never `Date.now()` directly, never a decrementing local counter as the source of truth.

```ts
// on every API response carrying serverTime
clockOffsetMs = Date.parse(res.serverTime) - Date.now();
const now = () => Date.now() + clockOffsetMs;
const remainingMs = (expiresAt: string) => Date.parse(expiresAt) - now();
```

A user with a 4-minute-fast device clock would otherwise see a hold expire the instant it is created.

**2. The server owns all limits.** `maxPerOrder`, TTLs, `attemptsRemaining` — render what the API
returns. Never hardcode "max 4" or "5 minutes" in a component.

**3. Rehydrate, never assume.** On **every** mount and every reconnect, call
`GET /api/v1/sale/{eventId}/state` and render from the response. Local storage is a *hint* that
speeds the first paint; the server is the truth.

**4. A timer reaching zero is a prompt to ask, not a conclusion.** Never navigate away or show
"expired" because a local countdown hit `00:00`. Call the API and let it say so.

**5. Every piece of client state is scoped by `eventId`.** A buyer may be in several sales at once —
queued for one, holding seats in another, reading a receipt for a third — in separate tabs or the
same one. There is no "current event".

```ts
// Correct. One namespace per sale.
const k = (eventId: number, name: string) => `fs.${eventId}.${name}`;
sessionStorage.setItem(k(eventId, 'holdToken'), token);

// Wrong, and silently destructive.
sessionStorage.setItem('fs.holdToken', token);   // ← tab B overwrites tab A's hold
```

This mirrors the server, where every per-buyer Redis key carries the event id for exactly this
reason: one visitor in two concurrent sales had one promotion overwrite the other (ADR-036). A
global client key reintroduces that bug above the API.

The same rule governs live connections: **one `EventSource` per event**, closed when its view
unmounts, never a shared singleton reassigned to whichever sale was opened last.

---

## 1. View state machine

```
                        ┌──────────────────────────────────────────┐
                        │  every mount: GET /sale/{eventId}/state  │
                        └────────────────────┬─────────────────────┘
                                             ▼
   1. hold ≠ null ─────────────────► V4  Billing & Checkout
   2. queue.state=ADMITTED ────────► V3  Seat Selection
   3. queue.state=PROMOTED ────────► auto POST /queue/admit → V3
   4. queue.state=WAITING ─────────► V2  Virtual Waiting Room
   5. order.status=CONFIRMED ──────► V5  Order Confirmation
   6. queue.state=EXHAUSTED ───────► V6  Sold Out
   7. windowStatus=CLOSED ─────────► V6  Sale Closed
   8. otherwise ───────────────────► V1  Pre-Sale Landing / Join
```

This list **is** the router, and it is **ordered**. Evaluate top to bottom and take the first match.
Do not derive the view from navigation history — a buyer who reloads, hits Back, or opens a second
tab must land on the view the server says they are in.

**The address is `/events/:eventId`, and nothing after it chooses the view.** The per-view routes
below name the screens; the built client renders whichever view `routeFor` picks at
`/events/:eventId` and at any path beneath it, so a bookmarked or stale `…/checkout` still lands
where the server says the buyer is.

**It is evaluated per event, against that event's own state** (rule 5). `routeFor` is a function of
one `SaleState`, and a buyer in three sales has three independent answers — there is no single
"current view" for the session. Every route in this document is therefore `/events/:eventId/…`: the
event id is in the URL because it is what selects the state, not because it is a detail of the page.

Three positions in that order are load-bearing:

| Rule | Why it sits where it does |
| :--- | :--- |
| **hold above `CLOSED`** | The checkout grace (ADR-016) lets a buyer who reached the payment form finish for 15 minutes after the sale ends. Checking the window first would throw them off a purchase the server would have accepted. |
| **`CONFIRMED` below the queue states** | So a buyer who purchases and then rejoins for a second tier sees the queue rather than being pinned on their old receipt. `/sale/state` reports the *latest* order whatever its status (ADR-037), and precedence — not filtering — decides what that means. |
| **`EXHAUSTED` above `CLOSED`, both last** | Both are terminal screens, but "sold out" and "the sale ended" are different facts and the buyer deserves the accurate one. Note that `EXHAUSTED` is **reversible**: it is derived from live stock, so a released hold can put a buyer back in the queue (ADR-035). V6's action must re-route, never dead-end.

---

### V1 — Pre-Sale Landing

**Route:** `/events/:eventId`

**Renders:** hero, venue, date, tier cards (name, price, `availability` badge), countdown to
`saleStartTime`, primary CTA.

| `windowStatus` | CTA |
| :--- | :--- |
| `UPCOMING` | disabled, `HH:MM:SS` countdown |
| `OPEN` | **"Join Flash Sale"** enabled |
| `PAUSED` | **"Join Flash Sale"** enabled, with "Sales are paused for a moment — you can still join the line". **Never** the sale-ended treatment (ADR-066) |
| `CLOSED` | → V6 |

**Availability badges** — bucketed, never numeric (ADR-027):

| `availability` | Badge | Colour |
| :--- | :--- | :--- |
| `PLENTY` | "Available" | success |
| `LIMITED` | "Limited" | warning |
| `SOLD_OUT` | "Sold Out" | disabled |
| `UNKNOWN` | "Checking…" | neutral — **never** the sold-out treatment (ADR-040) |

> Exact counts are deliberately not exposed: they drive panic-buying and give scalpers a live feed.

**`UNKNOWN` is a fault, not a bucket.** It means the server could not read that tier's counter — the
same fact as `503 INVENTORY_UNAVAILABLE`, arriving on a `200`. Render it neutrally, keep the tier
**selectable**, and let `POST /holds` answer: it already distinguishes `409 INSUFFICIENT_STOCK` from
`503 INVENTORY_UNAVAILABLE`. Rendering it as sold out would tell every visitor a live sale had ended
because a row was missing, which is precisely what shipped (ADR-004, ADR-040).

**Any value your switch does not recognise must fall through to `UNKNOWN`, never to `SOLD_OUT`.**
The enum has grown once and may grow again; the safe default is "we do not know", never "it is gone".

**At `T-0`:** flip the CTA live client-side from the countdown. Do **not** auto-submit — a self-firing
join at exactly `t=0` from every open tab is indistinguishable from a bot, and a challenge will score
it accordingly.

**Poll** `GET /api/v1/events/{eventId}` every 30 s while `UPCOMING`; every 10 s in the final minute.

---

### V2 — Virtual Waiting Room

**Route:** `/events/:eventId/queue`

The emotional core of the product. A buyer may sit here for twenty minutes and every design choice
should reduce anxiety.

**Renders:** position, estimated wait, progress bar, connection indicator, live per-tier availability,
"leave queue" control.

```
┌────────────────────────────────────────────┐
│           You're in the queue               │
│                  #128                       │
│        ▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░  72%           │
│         About 4 minutes remaining           │
│                                             │
│  VIP  Sold Out    Floor  Limited    GA  ✓   │
│                                             │
│  ● Connected            [ Leave queue ]     │
└────────────────────────────────────────────┘
```

**Position display rules:**

- **Monotonic non-increasing.** `display = Math.min(display, incoming)`. A number that goes *up*
  reads as a broken queue; evictions ahead of you can make raw `ZRANK` jump backwards.
- Above 1,000, show `1,000+` and hide the exact figure — precision at that range only invites
  refresh-hammering.
- Animate transitions over ~400 ms. A number that snaps looks like a glitch; one that counts down
  feels like progress.
- Never render `#0`. Below 1, show "You're next".

**Estimated wait:** round generously (`< 1 min`, `about 2 minutes`, `about 15 minutes`). Never show
seconds — a precise estimate that slips is worse than a vague one that holds.

**Per-tier availability** updates live from the `tier-availability` frame, so a buyer waiting
specifically for VIP learns it is gone **while waiting** instead of after admission (ADR-027).

The frame is sent **only when a bucket changes**, and the diff is per replica — so a buyer who
connects between two changes sees no frame until the next one. Seed the tiers from
`GET /events/{eventId}` (or `/sale/{eventId}/state`) on mount and treat the frame as an update, never
as the only source.

**Connection indicator:**

| State | UI | Note |
| :--- | :--- | :--- |
| `open` | ● Connected | |
| `reconnecting` | ◐ Reconnecting… | **keep the last position visible** |
| `polling` | ◐ Reconnecting… | fallback active; user sees no difference |
| `error` | ○ Connection lost — [Retry] | only after backoff is exhausted |

> **Never** show "you have been disconnected" in a way that implies the place in line is lost. It is
> not: the position lives in Redis keyed on the `fsid` cookie, and **the queue never evicts on a
> missing heartbeat** (ADR-026). Say "Reconnecting — your place is saved."

**On `queue-promoted`:** immediately `POST /api/v1/queue/admit`, then route to V3. Show
"You're in! Taking you to seats…" for ≥ 600 ms — an instant flip feels like an error.

---

### V3 — Seat Selection

**Route:** `/events/:eventId/select`
**Guard:** `queue.state === 'ADMITTED'`, else back to V1/V2.

**Renders:** tier cards with price + availability, quantity stepper, running total, **admission
countdown**, "Reserve" CTA.

> **Note on the countdown here.** The brief called this a "3-minute pass token countdown". The pass
> is a 120 s single-use token that is spent immediately on arrival at this screen. What the buyer
> sees is the **admission session** — 600 s (10 min) — which is what gives them room to compare
> tiers (ADR-020). The pass never appears in the UI.

**Quantity stepper:** `1 … tier.maxPerOrder`, taken from the API response. The brief specified "max
4"; the server default is 6. **The UI must render the server's value** — if 4 is wanted, change
`ticket_tiers.max_per_order`, not the component.

**Admission countdown:** subdued until `T-60 s`, then amber, with "10 minutes to choose" copy. This
is a *low-anxiety* timer — expiring costs the buyer their place in line, not money, and the copy
should reflect that.

**"Reserve" flow:** disable → `POST /api/v1/holds` → on `201`, store `holdToken`, route to V4.

| Error | UI |
| :--- | :--- |
| `409 INSUFFICIENT_STOCK` | Inline on the tier: "Just sold out — pick another." Refresh availability. **Stay on V3** — the admission session is intact |
| `422 QUANTITY_EXCEEDS_LIMIT` | Clamp the stepper, inline message |
| `409 HOLD_LIMIT_EXCEEDED` | Rehydrate — this session already holds seats; route to V4 |
| `410 ADMISSION_EXPIRED` | "Your session ended." → V1 |
| `503 INVENTORY_UNAVAILABLE` | "Having trouble — retrying." Auto-retry 3× with backoff. **Never** render this as sold out |

---

### V4 — Billing & Checkout

**Route:** `/events/:eventId/checkout`
**Guard:** `hold !== null`.

The highest-stakes screen. Money is involved and a timer is running.

**Renders:** order summary, **sticky hold countdown**, email input, a payment element, pay CTA,
"release seats" secondary.

**The hold timer — the anxiety surface:**

| Remaining | Treatment |
| :--- | :--- |
| > 120 s | Neutral. `MM:SS`, secondary colour |
| 120–60 s | Amber. "Complete your purchase soon" |
| < 60 s | Red, gentle pulse (**≤ 1 Hz**, respects `prefers-reduced-motion`). "Less than a minute remaining" |
| < 10 s | Red, no pulse. Do **not** disable the form |
| `00:00` | **Do not navigate.** → see below |

Sticky-positioned so it never scrolls out of view. Never flash, never shake, never play sound.

**At `00:00` — the rule that matters most:**

```ts
if (remainingMs <= 0) {
  if (paymentInFlight) {
    show("Completing your purchase…");   // NEVER "expired"
    return;                              // the server will decide
  }
  const state = await fetchSaleState();  // ask; do not assume
  if (state.hold) { resync(state); return; }   // clock skew — carry on
  showExpired(state);
}
```

A charge already submitted **will complete**, and once a real gateway ships its webhook finalises the
order even if the browser is closed (ADR-012). Telling a buyer their reservation expired while their
card is being charged is the worst possible message, and it would be false.

**Payment submission:**

```ts
const key = (name: string) => `fs.${eventId}.${name}`;      // rule 5
const idempotencyKey = sessionStorage.getItem(key(`idem.${holdToken}`))
  ?? crypto.randomUUID();                   // generated ONCE per hold, reused on every retry
sessionStorage.setItem(key(`idem.${holdToken}`), idempotencyKey);
setPaymentInFlight(true);                   // disables CTA and freezes the expiry branch
```

The key is generated **once per hold** and reused across retries. Regenerating it per attempt defeats
the gateway-level guard (ADR-014) and opens a *second* PaymentIntent, so a buyer can authenticate one
payment and be billed for two — the failure ADR-054 exists to prevent. The server now enforces the
field's presence (`@NotBlank`), so omitting it is `400 VALIDATION_FAILED` rather than a silent
downgrade to no gateway-level guard at all.

**Outcome handling:**

| Response | Handling |
| :--- | :--- |
| `201` / `200` | → V5 |
| `402 PAYMENT_DECLINED` | Inline: "Card declined — try another." Form stays populated except the card. **Hold is retained.** Show `attemptsRemaining`. **No timer extension** (ADR-030) |
| `402 PAYMENT_ATTEMPTS_EXHAUSTED` | Terminal. Offer re-entry to the queue |
| `409 INSUFFICIENT_TIME_REMAINING` | "Not enough time left to complete this safely." Offer release + re-queue. Nothing was charged (ADR-030) |
| `410 HOLD_EXPIRED` | Expired panel. Nothing charged — **say so explicitly** |
| `503 PAYMENT_GATEWAY_UNAVAILABLE` | "Payment provider is having trouble. **Your seats are held.**" Retry after `retryAfterSeconds` |
| `503 SERVICE_BUSY` | "We're handling a lot of traffic — retrying." Re-POST the **same body** after `Retry-After` (1 s); at most a handful of times, then a manual "Try again". Seats unaffected (ADR-059) |
| `409 DUPLICATE_PAYMENT` | Ignore — a charge is in flight. Poll `/sale/state` every 2 s |
| `409 ORDER_REFUNDED` | Terminal. The charge succeeded and could not be completed, so it was **refunded**. Say that plainly and name the order number |
| `409 REFUND_FAILED` | Terminal. The charge succeeded, the purchase could not be completed, and the **automatic refund did not go through** — a person is returning the money. Never say "refunded" here (ADR-069) |
| `402 PAYMENT_ACTION_REQUIRED` | 3-D Secure. Keep `paymentInFlight` **true**, run `stripe.handleNextAction(problem.clientSecret)`, then **re-POST this same body**. Hold retained, **no attempt consumed** — see below |

**A retry is the same request.** Re-POST `/orders/checkout` with the same body and the same
`idempotencyKey`. Find-or-create on `UNIQUE(hold_token)` retries on the same order number, so three
declines produce one reference rather than three (ADR-002, ADR-034). Regenerating the key per attempt
defeats the gateway-level guard (ADR-014).

**3-D Secure — built (ADR-054).** The `402` carries a `clientSecret` on the problem document. Four
rules, and each of them is a way this goes wrong:

1. **`paymentInFlight` stays `true` for the whole challenge.** It is what freezes the expiry branch;
   letting it drop mid-challenge routes the buyer off the payment screen while their bank is still
   asking them a question.
2. **The retry is the same request.** Re-POST `/orders/checkout` with the same body and the **same
   `idempotencyKey`** — the server retrieves the existing PaymentIntent rather than charging again.
   There is no resume endpoint. A fresh key per attempt would open a second intent and bill twice
   for one authentication.
3. **No attempt was consumed and the seats are still held.** Do not decrement anything you show the
   buyer, and do not re-route.
4. **If the page is reloaded mid-challenge**, rehydrate through `/sale/{eventId}/state` like every
   other recovery — the order is `FAILED` and resumable, so V4 is still the right screen.

The +120 s grace is granted *before* the charge, so the challenge window is already covered
(ADR-006, ADR-030). No further extension is granted, and none is needed.

**Email:** validated client-side for shape only — shape validation catches `foo@@bar`, never
`jhon@gmial.com`. Show it back on V5 prominently. Today a typo means the tickets go nowhere with no
recovery path; ADR-050's download is what turns that from terminal into recoverable, and until it
ships this screen is the buyer's only chance to catch it.

---

### V5 — Order Confirmation

**Route:** `/orders/:orderNumber`

**Renders:** success state, `TK-98213` in large monospace with copy-to-clipboard, item summary,
total, buyer email, "check your inbox" notice, and a **download CTA**.

Email delivery is **asynchronous** — the PDF may take a few seconds. Do not promise it has arrived:

> "We're sending your tickets to **buyer@example.com**. They usually arrive within a minute."

**The download is not a convenience — it is the recovery path.** The address is collected once at
checkout and never verified, so a typo means the ticket goes to a stranger or bounces, and even an
operator resend replays to the same wrong address. `GET /orders/{orderNumber}/ticket.pdf` closes
that (ADR-050), authorised exactly like this page: session cookie **or** `receiptToken`.

**Send `Accept: application/pdf, application/problem+json`.** Asking only for the PDF makes every
failure on this endpoint unnegotiable, and the client gets a `406` it cannot read instead of the
`ProblemDetail` explaining why.

`409 TICKET_NOT_AVAILABLE` means the order is yours but has no ticket. Branch on `retryable`: true
means `PENDING` and still in flight, so poll; false is terminal and polling will never help.

V5 must **still show the email address it sent to, prominently enough to proofread**. The download
makes a typo survivable, not invisible — a buyer who never opens this page and never receives the
email is still stuck, and spotting it here is the cheapest fix available to them.

Clear `fs.{eventId}.holdToken` and `fs.{eventId}.idem`. **Keep** `orderNumber` and `receiptToken` —
they are how a buyer returns here — and append the order to `fs.recentOrders`, which is what lets
someone buying into several sales find all of their tickets. The URL carries `?receiptToken=…` so the
page survives a cookie clear and works from the confirmation email (ADR-010), subject to §3.1.

---

### V6 — Terminal states

| State | Message | Action |
| :--- | :--- | :--- |
| `SOLD_OUT` | "This event has sold out." | Browse other events |
| `SALE_CLOSED` | "Sales have ended." | Browse |
| `QUEUE_LEFT` | "You've left the queue." | Rejoin (goes to the back — say so) |
| `EXPIRED` | "Your reservation expired. **Nothing was charged.**" | Rejoin |

That "nothing was charged" line is not optional. It is the single most reassuring sentence in the
product.

**A paused sale is not a V6 state** (ADR-066). It is a banner over whatever view the buyer is in —
"Sales are paused for a moment — your place is kept" — and it lifts on `sale-resumed`, the next
`position-update`, or `windowStatus` returning to `OPEN`. Rendering it as "Sales have ended" tells
every buyer in the line to leave a sale that is about to continue.

---

## 2. API mapping

Base `/api/v1`. `fsid` is an `HttpOnly` cookie — **JavaScript never reads or sets it**; send
`credentials: 'include'` on every request.

| View | Method | Path | Headers | Body | Success | Error codes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| V1 | `GET` | `/events` | — | — | `200` | — |
| V1 | `GET` | `/events/{eventId}` | — | — | `200` | `EVENT_NOT_FOUND` |
| all | `GET` | `/sale/{eventId}/state` | — | — | `200` | `EVENT_NOT_FOUND` |
| V1→V2 | `POST` | `/queue/join` | — | `{eventId, recaptchaToken?}` — the token at most 4,096 characters, sent only with a site key (§5) | `202` | `SALE_NOT_OPEN`, `SALE_CLOSED`, `EVENT_NOT_FOUND`, `RATE_LIMITED`, `BOT_VERIFICATION_FAILED` (§5), `VALIDATION_FAILED`. A **paused** sale accepts the join — the line keeps its arrival order while nobody is let out (ADR-066) |
| V2 | `GET` | `/queue/stream?eventId=&lastEventId=` | `Accept: text/event-stream`, `Last-Event-ID` | — | SSE | — |
| V2 | `POST` | `/queue/leave` | — | `{eventId}` | `204` | — Idempotent. The session leaves the line and an unspent pass is dropped; joining again starts **at the back**, and the copy must say so |
| V2 | `GET` | `/queue/status?eventId=` | — | — | `200` | — (a session that never joined is `phase: NOT_JOINED`, not an error). `paused: true` while an operator has paused the sale; the phase stays truthful beside it |
| V2→V3 | `POST` | `/queue/admit` | `X-Queue-Pass-Token` | `{eventId}` | `200` | `QUEUE_PASS_INVALID`, `VALIDATION_FAILED` |
| V3 | `POST` | `/holds` | `X-Admission-Token` | `{eventId, tierId, quantity}` | `201` | `INSUFFICIENT_STOCK`, `QUANTITY_EXCEEDS_LIMIT`, `HOLD_LIMIT_EXCEEDED`, `ADMISSION_REQUIRED`, `ADMISSION_EXPIRED`, `INVENTORY_UNAVAILABLE`, `TIER_NOT_FOUND`, `SALE_CLOSED`, `SALE_PAUSED` (retryable: keep the buyer on V3 and let them try again once the sale resumes) |
| V4 | `GET` | `/holds/{holdToken}` | — | — | `200` | `HOLD_NOT_FOUND`, `HOLD_EXPIRED` |
| V4 | `DELETE` | `/holds/{holdToken}` | — | — | `204` | `HOLD_NOT_FOUND` |
| V4 | `POST` | `/orders/checkout` | — | `{holdToken, userEmail, paymentMethodId, idempotencyKey}` — at most 64, 255, 255 and 64 characters (`400 VALIDATION_FAILED` beyond) | `201`/`200` | `PAYMENT_DECLINED`, `PAYMENT_ATTEMPTS_EXHAUSTED`, `HOLD_EXPIRED`, `DUPLICATE_PAYMENT`, `PAYMENT_GATEWAY_UNAVAILABLE`, `CHECKOUT_WINDOW_CLOSED`, `INSUFFICIENT_TIME_REMAINING`, `ORDER_REFUNDED`, `REFUND_FAILED`, `SERVICE_BUSY` |
| V5 | `GET` | `/orders/{orderNumber}?receiptToken=` | — | — | `200` | `ORDER_NOT_FOUND` |
| V5 | `GET` | `/orders/{orderNumber}/ticket.pdf?receiptToken=` | `Accept: application/pdf, application/problem+json` | — | `200` | `ORDER_NOT_FOUND`, `TICKET_NOT_AVAILABLE` |
| — | `POST` | `/session/reset` | `Content-Type: application/json` (required) | `{}` | `204` | `VALIDATION_FAILED` (`415`, any other content type) |

**`POST /session/reset` is a demo affordance, not part of the buyer journey.** It expires the `fsid`
cookie so the bundled demo page can start over as a new visitor. A production client must never call
it: the session *is* the buyer's queue position and the only thing that authorises their hold, so
clearing it discards both. It is unauthenticated and the API has no CSRF token, so it accepts
**only `application/json`** (ADR-060): a cross-site form cannot send that content type, and a
cross-origin `fetch` that does needs a preflight nothing grants. Anything else answers `415` and
expires nothing.

> **`userSessionId` is never sent** — not in a body, not in a header, not in a query string. Identity
> comes from the signed cookie alone (ADR-010). A request that carries it will be rejected.

**There is no `/orders/checkout/resume`.** An earlier draft specified one; it does not exist and is
not needed. **Re-POST the same `/orders/checkout` body** — the endpoint is find-or-create on
`UNIQUE(hold_token)`, so a resubmission after a decline retries on the *same* order number, and a
resubmission after success replays the receipt with `200` instead of `201` (ADR-002, ADR-034). That
is the whole retry mechanism; do not build a second one.

**`PAYMENT_ACTION_REQUIRED` is now reachable** and a client must branch on it — see V4 above.
`WEBHOOK_SIGNATURE_INVALID` is reachable too, but only on the provider's own callback; no browser
will ever see it. **`BOT_VERIFICATION_FAILED` is reachable on join only, and only when a challenge
provider is configured**: the provider actively scored the browser below the threshold. Without a
provider, or when it is unreachable, the join goes ahead (§5, ADR-055).

### Error envelope — RFC 7807

Every error is `application/problem+json`. There is no `ApiResponse<T>` wrapper (ADR-021).

```jsonc
{
  "type": "https://flashseats.dev/problems/payment-declined",
  "title": "Payment declined",
  "status": 402,
  "detail": "Your card was declined. Try a different card.",
  "code": "PAYMENT_DECLINED",       // switch on THIS, never on `detail`
  "traceId": "0af7651916cd43dd",
  "retryable": true,
  "attemptsRemaining": 2,
  "expiresAt": "2026-08-30T10:07:55Z"
}
```

```ts
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1${path}`, { ...init, credentials: 'include' });
  if (res.ok) return res.json();
  const problem: Problem = await res.json();
  throw new ApiError(problem);      // consumers switch on problem.code
}
```

Show `detail` to the user. Show `traceId` in a support footer on `5xx`. Switch on `code` only —
`detail` is copy and will change.

---

## 3. State & persistence

**`sessionStorage`, not `localStorage`** — deliberately. Per-tab isolation means a second tab cannot
inherit a stale `holdToken` and desynchronise. Closing the tab discards checkout state, which is the
correct default for a payment flow on a shared machine.

**Every key is namespaced by `eventId`** (rule 5). The sale a key belongs to is part of its identity,
not context the tab happens to remember.

| Key | Contents | Lifetime |
| :--- | :--- | :--- |
| `fs.{eventId}.holdToken` | active hold | until settled |
| `fs.{eventId}.idem.{holdToken}` | idempotency key, **one per hold** | until settled |
| `fs.{eventId}.email.{holdToken}` | the email typed at checkout, so a reload does not empty the form | until settled |
| `fs.{eventId}.paying.{holdToken}` | set while a payment is on its way; still set after a reload means "a payment may be finishing", never "nothing happened" | until an answer arrives |
| `fs.{eventId}.lastEventId` | SSE `Last-Event-ID` | per tab |
| `fs.{eventId}.queueStart` | the position first seen in line, which the progress bar measures from | while waiting |
| `fs.{eventId}.view` | the last view, so "your reservation ended while you were away" survives a reload | per tab |
| `fs.clockOffsetMs` | server-clock delta | per tab |

`fs.clockOffsetMs` is the **one** deliberately global session key: there is a single server clock,
and every `serverTime` in every response refreshes the same offset.

**The one per-sale key in `localStorage`: `fs.{eventId}.admissionToken`.** An admission belongs to the
session — the `fsid` cookie every tab shares — and `/holds` accepts only a request that carries it.
Kept per tab, a second tab of the same buyer in the same sale would be admitted on the server and
refused at `/holds` with `ADMISSION_REQUIRED`, looping. It is still namespaced by event, and cleared
the moment rehydration says the admission is over.

There is still no `fs.pi`, and 3-D Secure does not need one. The `clientSecret` arrives on the
`402` and is used immediately by `handleNextAction`; the retry is a re-POST of the same body, so
nothing about the challenge has to survive a reload. The intent id lives on the server, keyed by the
hold (ADR-054). Persisting a client secret would be storing a bearer value for no reason.

**`localStorage`, otherwise:** `fs.theme` (light / dark — a preference about the person, not
a sale), and `fs.recentOrders` — a list of `{orderNumber, receiptToken, eventTitle}` so a
returning buyer can find their tickets across sales. It is keyed by nothing because it spans
everything, and it is the only client state that is *meant* to outlive a tab. Nothing
security-sensitive beyond the receipt tokens it exists to hold — treat it accordingly (§3.1).

**Never stored anywhere:** `fsid` (HttpOnly by design), card data, `queuePassToken` (lives ~2 s in
memory before being exchanged).

### 3.1 `receiptToken` is a bearer capability

It authorises reading an order — and, once ADR-050 ships, **downloading its ticket** — from any
browser for **90 days**, with no cookie. It is what makes the link in the confirmation email work.

Consequences a client must respect:

- Put it in the query string of a link the buyer chooses to open. **Never** in a URL the app
  navigates to automatically, where it lands in history and any `Referer`.
- Never log it, never send it to an analytics or error-reporting service.
- Never render it as visible text. Render the *link*.

The server enforces the same separation: the admin order view returns a shape **without** it, so an
operator cannot accidentally mint an impersonation link (ADR-048).

### Rehydration — the recovery protocol

```ts
async function bootstrap(eventId: number) {
  const state = await api<SaleState>(`/sale/${eventId}/state`);
  clockOffsetMs = Date.parse(state.serverTime) - Date.now();

  const key = (name: string) => `fs.${eventId}.${name}`;      // rule 5
  if (state.hold)  sessionStorage.setItem(key('holdToken'), state.hold.holdToken);
  else             sessionStorage.removeItem(key('holdToken'));

  if (state.partial?.length) {
    // A section the server could not read. Render the rest; do NOT treat a missing
    // section as an absent one — "no hold" and "could not read holds" differ.
    markDegraded(state.partial);
  }

  return routeFor(state);          // the §1 table
}
```

Runs on: initial mount, `visibilitychange` → visible, `online`, SSE reconnect, and after any `409`
or `410`. It is cheap (four in-process facade reads) and it is the difference between a resilient
SPA and a fragile one.

**`partial` is part of the contract and most clients forget it.** `/sale/{id}/state` fails soft per
section: if the queue read throws, `queue` comes back `null` and `"queue"` appears in `partial`. A
client that reads `queue === null` as "not in the queue" will route a waiting buyer to the landing
page and invite them to join a line they are already in. **Absent and unreadable are different
facts** — the same distinction the server maintains between `SOLD_OUT` and `UNKNOWN`.

**Recovery matrix — every reload point:**

| Reloaded at | Server state | Result |
| :--- | :--- | :--- |
| V1, pre-sale | `UPCOMING` | Countdown resumes |
| V2, position #120 | `WAITING` | **Same position.** SSE reopens with `Last-Event-ID` |
| V2, promoted during reload | `PROMOTED` + `passToken` | Auto-admit → V3. The pass was waiting in Redis |
| V3, no hold | `ADMITTED` | Seat picker, admission timer resumed |
| V4, hold live | `hold` + remaining TTL | Checkout, timer resumed from `expiresAt` |
| V4, mid-charge | `order: PENDING` | Resume panel; poll every 2 s. **Re-POST `/orders/checkout`**, do not invent a resume endpoint |
| V4, charge settled during reload | `order: CONFIRMED` | Straight to V5 — **the reload cost nothing** |
| V4, hold expired while away | `hold: null` | Expired panel, "nothing was charged" |
| **V5, reloaded after buying** | `hold: null`, `order: CONFIRMED` | **Receipt, not the landing page.** Rehydration returned only *pending* orders, so a completed purchase was invisible and the buyer was invited to queue for seats they already owned (ADR-037) |
| **V2, sale closed while waiting** | `queue.state: CLOSED` | V6. The window is resolved before ZSET rank, and the broadcaster sends `sale-closed` and completes the stream (ADR-036) |
| **V2, counter unreadable** | `queue.state` unchanged, promotion paused | **Stay in V2.** A missing counter is a fault, never a sold-out sale (ADR-004, ADR-035) |
| **Any view, sale paused by an operator** | `windowStatus: PAUSED`, every other section unchanged | **Stay where you are**, with "Sales are paused for a moment — your place is kept". The line, a pass, an admission, a hold: nothing is torn down, though each keeps its own clock. Holds cannot be created until it resumes; a hold that already exists can still be paid for. Show no wait estimate while paused — the line is not moving (ADR-066) |
| Second tab, **same** sale | same session | Both tabs converge on the same state |
| **Second tab, a different sale** | independent per-event state | **Both sales proceed independently.** Queued for A while holding seats in B is a legitimate, supported state. This is what rule 5 exists for, and the current demo client fails it |

### Checkout error handling — one rule per code

The two questions every branch answers: *may they press Pay again*, and *do they still have their
seats*. Getting either wrong leaves a buyer mashing a button that cannot succeed.

| `code` | Pay button | Seats | Copy must say |
| :--- | :--- | :--- | :--- |
| `PAYMENT_DECLINED` | **enabled**, "Try again" | held | "Your seats are still held — N attempt(s) left" |
| `PAYMENT_GATEWAY_UNAVAILABLE` | **enabled**, "Try again" | held | Our problem, not theirs, and **no attempt was used** (ADR-034) |
| `PAYMENT_ACTION_REQUIRED` | **disabled during the challenge**, then "Try again" | held | Their bank is verifying. **No attempt was used**, and re-POSTing the same body is what completes it (ADR-054) |
| `PAYMENT_ATTEMPTS_EXHAUSTED` | **disabled** | held | Offer *Release seats* — a further attempt cannot be accepted |
| `DUPLICATE_PAYMENT` | **disabled** | held | "Finishing a payment already in progress", then poll `/sale/state` |
| `INVENTORY_UNAVAILABLE` | **enabled**, "Try again" | untouched | "Having trouble reading availability." **Never "sold out"** (ADR-004) |
| `SERVICE_BUSY` | **enabled**, "Try again" | untouched | "We're handling a lot of traffic." Not a failure of theirs and not a decline — **no attempt was used**. Any endpoint can return it, not only checkout (ADR-059) |
| `HOLD_EXPIRED` | — | gone | "Nothing was charged", then re-route |
| `INSUFFICIENT_TIME_REMAINING` | **disabled** | **held** | Nothing charged, but the grace budget is spent. Offer *Release seats* — do **not** re-route |
| `ORDER_REFUNDED` | — | gone | A charge settled and **was refunded** — do not claim nothing was charged |
| `REFUND_FAILED` | — | gone | A charge settled and the refund **did not** go through; it is with a person. Do not claim it was refunded, and do not claim nothing was charged (ADR-069) |

Two of these were missing and fell to a default that re-enabled Pay: `DUPLICATE_PAYMENT`, which
looped forever, and `PAYMENT_ATTEMPTS_EXHAUSTED`, which offered an attempt the server would refuse.

A third was **wrong rather than missing**. `INSUFFICIENT_TIME_REMAINING` was listed as "seats gone",
and the client cleared the hold and re-routed. The server does the opposite: it refuses to *start* a
charge it cannot finish inside the window and deliberately **keeps the reservation** (ADR-030,
`order.md` §5). Re-routing therefore rehydrated onto the same live hold and dropped the buyer back
on the checkout screen, where the same timer guaranteed the same `409` — a loop with no exit, on the
one screen where money is involved. The grace budget is already spent, so the only way out is to
release and re-reserve, and the UI has to say so.

**`admit()` must not recurse.** A failed `/queue/admit` calls `route()`, and `route()` sends
`PROMOTED` straight back to `admit()`. A pass that is present but unacceptable — a rotated secret, or
one minted for another event — loops between the two. Fall back to V1 on a second consecutive
failure.

---

## 4. SSE mechanics

```ts
function connect(eventId: number) {
  const es = new EventSource(
    `/api/v1/queue/stream?eventId=${eventId}`, { withCredentials: true });

  es.addEventListener('position-update', e => {
    const d = JSON.parse(e.data);
    setPosition(p => Math.min(p ?? d.position, d.position));   // monotonic
    setEstWait(d.estWaitSeconds);
    sessionStorage.setItem(`fs.${eventId}.lastEventId`, (e as MessageEvent).lastEventId);
  });

  es.addEventListener('queue-promoted',    e => admit(JSON.parse(e.data).passToken));
  es.addEventListener('tier-availability', e => setTiers(JSON.parse(e.data).tiers));
  es.addEventListener('sale-exhausted',    () => { es.close(); goTo('V6:SOLD_OUT'); });
  es.addEventListener('sale-closed',       () => { es.close(); goTo('V6:SALE_CLOSED'); });
  es.addEventListener('sale-paused',       () => setPaused(true));   // NOT terminal: keep the stream
  es.addEventListener('sale-resumed',      () => setPaused(false));

  es.onopen  = () => { setConn('open'); attempt = 0; };
  es.onerror = () => { es.close(); scheduleReconnect(eventId); };
  return es;
}
```

**Reconnect replay.** `EventSource` re-sends the last id it saw as a `Last-Event-ID` header by
itself; a client that cannot set headers may pass `?lastEventId=` instead. The server replays the
**broadcast** frames minted after that sequence — `tier-availability`, `sale-exhausted`,
`sale-closed`.

**Only those frames carry an `id`.** Position updates, `queue-promoted`, `sale-paused` and `sale-resumed` are sent with none, which
the SSE specification defines as leaving the client's last-event-id unchanged — so storing
`e.lastEventId` on *every* frame, as the snippet above does, is correct and always records a sequence
the server can replay from. A promotion is never replayed: the server re-reads the live pass on
connect and re-sends `queue-promoted` if one is still valid, so a promoted buyer recovers even in a
fresh tab that has no `Last-Event-ID` at all (ADR-058).

**`sale-paused` and `sale-resumed` are neither retained nor replayed** (ADR-066). A pause is a
*current* condition, not an event in the sale's history: each replica sends `sale-paused` to its own
streams on every sweep while the sale is paused, and on connect, and `sale-resumed` once when it
resumes — so a reconnect after the resume is never handed a pause that has ended. A `position-update`
also means the sale is running.

**Backoff** — full jitter, capped, with a polling fallback:

```ts
const delay = Math.random() * Math.min(30_000, 1_000 * 2 ** attempt++);
```

`1s → 2s → 4s → 8s → 16s → 30s (cap)`. Jitter is not optional: 10,000 clients reconnecting in
lockstep after a blip is a self-inflicted DDoS.

After **3** failed attempts, start polling `GET /queue/status` every 5 s **while still retrying SSE**.
The polling path returns the pass if one was minted, so a promotion is never lost to a dead socket
(ADR-007). The UI shows "Reconnecting" throughout — the buyer should not have to care which transport
is live.

**Network-change handling** — the Wi-Fi → cellular case:

```ts
window.addEventListener('online',  () => { attempt = 0; reconnectNow(); bootstrap(eventId); });
window.addEventListener('offline', () => setConn('reconnecting'));
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') { bootstrap(eventId); reconnectIfClosed(); }
});
```

Reset `attempt` on `online` — the previous backoff was measuring a dead network, not a busy server.

> **The queue survives all of this.** Position is keyed on the `fsid` cookie, which is unaffected by
> an IP change, and **the backend never evicts on a missing heartbeat** (ADR-026). A handover of any
> duration is safe. Copy must reflect that: "Reconnecting — your place is saved."

Browsers cap ~6 connections per origin: **one `EventSource` per tab**, closed on unmount.

---

## 5. Abuse defence — what the client actually does

**reCAPTCHA v3 on join, failing open** (ADR-055). With `VITE_RECAPTCHA_SITE_KEY` set, the client loads
the provider's script **on the "Join the sale" press, never on page load** — tokens are short-lived,
so one minted early is stale for anyone who reads the page first — and sends `recaptchaToken` with
`/queue/join` only. With no key, a script that will not load, or a provider slower than 3 s, it sends
the join **without** a token, and the server falls back to rate limits. No sale may close because a
third-party script did not load. `BOT_VERIFICATION_FAILED` is the one refusal: the provider actively
scored the browser below the threshold.

Underneath, defence is **session-first rate limiting with an IP backstop** (ADR-011), enforced in a
servlet filter before any handler. The client's responsibility is to handle being limited well.

### Handling `429 RATE_LIMITED`

```ts
// Exponential backoff with full jitter, and a ceiling. Never a fixed retry interval:
// ten thousand clients retrying on the same 1 s tick is the thundering herd the
// waiting room exists to prevent, recreated above the API.
const delay = Math.random() * Math.min(30_000, 500 * 2 ** attempt);
```

- **Respect `Retry-After` when present**; it is the server's own estimate and beats any local guess.
- **Never retry automatically more than a handful of times.** Surface a manual "Try again" and stop.
- **Say what is true:** "We're handling a lot of traffic right now." Not "you look like a bot" — a
  shared corporate gateway or a carrier NAT can trip the IP bucket through no fault of the buyer,
  which is exactly why that bucket is deliberately loose.

The same copy rule applies to `BOT_VERIFICATION_FAILED`: never accuse a paying customer — "We couldn't
verify your browser. Reload the page and try again." False positives are real, and accusing one is
worse than admitting a few scripts.

The built client retries a `429` (and `503 SERVICE_BUSY`) at most twice on reads and joins and three
times on checkout, honouring `Retry-After`, then shows the copy above with a working "Try again".

---

## 6. Rendering & performance

**No layout shift from live values.** Position, timers and estimates update up to every 2 s; if their
containers resize, the whole page jitters.

```css
.position-display { font-variant-numeric: tabular-nums; min-width: 6ch; text-align: center; }
.countdown        { font-variant-numeric: tabular-nums; min-width: 5ch; }
.tier-badge       { min-width: 88px; }
```

`tabular-nums` alone fixes most of it — proportional digits make `#111` narrower than `#888`.

**Isolate high-frequency updates.** The countdown re-renders every second; it must not re-render the
payment element — remounting one mid-checkout loses whatever the buyer has typed.

```tsx
const HoldCountdown = memo(({ expiresAt }: { expiresAt: string }) => { … });
```

Drive it with a **single** app-wide 1 Hz `requestAnimationFrame` tick, not one `setInterval` per
component. `setInterval` also drifts and is throttled in background tabs — recompute from `expiresAt`
on every tick rather than decrementing.

**Optimistic vs. strict:**

| Action | Mode | Why |
| :--- | :--- | :--- |
| Quantity stepper | optimistic | Local, reversible, no server truth involved |
| Queue position | **strict** | Server-derived; never interpolate between frames |
| Reserve seats | **strict** | Spinner until `201`. Optimism here means showing seats the buyer may not have |
| Payment | **strict** | Never optimistic. Ever |
| Release hold | optimistic + rollback | Fast feedback; restore on failure |

**Debounce / throttle:** email validation 300 ms; tier selection 150 ms; **never** the pay button —
disable it on click instead. A debounced payment submit is a lost payment.

**Double-submit guard:**

```tsx
<Button disabled={paymentInFlight || remainingMs <= 0 && !paymentInFlight}
        onClick={submitPayment}>
  {paymentInFlight ? <CircularProgress size={20} /> : 'Pay now'}
</Button>
```

**Accessibility:** `aria-live="polite"` on position and the timer — but `aria-live="assertive"` only
at the 60-second threshold, and announce at most once per threshold crossing. A screen reader
announcing every second is unusable. Respect `prefers-reduced-motion` for the pulse and all
transitions. Every timer must have a text equivalent, never colour alone.

---

## 7. Copy guidelines

The waiting room and the expiry panel are where trust is won or lost.

| Never | Instead |
| :--- | :--- |
| "You have been disconnected" | "Reconnecting — your place is saved" |
| "Session expired" | "Your reservation ended. Nothing was charged." |
| "Error 409" | "Those seats just sold — pick another tier" |
| "You look like a bot" | "We couldn't verify your browser. Try again." |
| "Payment failed" | "Your card was declined. Try a different card — **your seats are still held.**" |
| "Sold out" (on a `503`) | "Having trouble loading availability. Retrying…" |

That last row is a correctness requirement, not a style preference: `503 INVENTORY_UNAVAILABLE` means
the stock counter is *missing*, not that the tier is gone (ADR-004). Rendering it as "sold out" would
tell thousands of buyers the sale ended when it had not.

---

## 8. End-to-end tests — Playwright

> **Status: built.** `frontend/e2e`, 27 specs across 10 files, green against the real backend. Run it
> with an open sale on `:8080` (`docker/scripts/dev-up.sh && ./mvnw spring-boot:run`), then
> `cd frontend && npm run test:e2e`. Playwright starts the Vite dev server itself.

### Why a real browser is required here

The backend suite (`./mvnw test`) already proves the things that live in SQL and Redis: no
overbooking, restore-exactly-once, one order per hold, the queue's terminal states. It drives the API
over real HTTP with a real cookie jar. What it cannot touch is **every one of the four rules in §0**,
because all four are browser behaviours:

| §0 rule | Why only a browser can check it |
| :--- | :--- |
| Countdowns derive from `serverTime` | Needs a page whose clock can be skewed away from the server's |
| Limits come from the API | Needs the rendered DOM, not the response body |
| Rehydrate on mount, `visibilitychange`, `online` | Needs real page lifecycle events and a real reload |
| A timer at zero **asks**, never concludes | Needs the timer to actually run for its duration |

Add `EventSource` — which jsdom does not implement, and whose reconnect behaviour is the whole point
of §4 — and a fake DOM stops being a shortcut and starts being a different system under test.

### Shape

```
frontend/
├── playwright.config.ts     one worker (one shared backend); desktop Chrome + a Pixel 7 project
└── e2e/
    ├── support/
    │   ├── backend.ts       the public API and the operator's pause/resume — nothing else (ADR-078)
    │   └── fixtures.ts      `sale` (an open sale with seats), `twoSales`, `newBuyer` (a context = an fsid)
    └── specs/
        ├── journey.spec.ts          landing → line → turn → hold → pay → receipt → PDF; My tickets
        ├── checkout-errors.spec.ts  decline ×3, outage + Retry-After, 3-D Secure, release
        ├── recovery.spec.ts         reload in line, choosing, at checkout, after buying; two tabs
        ├── router.spec.ts           confirmed below the queue states; sub-paths
        ├── sse.spec.ts              pause → resume on the stream; polling fallback; leave the line
        ├── clock.spec.ts            a device four minutes fast still sees the whole hold
        ├── concurrent.spec.ts       two sales: two tabs, and one tab moving between them
        ├── ratelimit.spec.ts        429 backoff, then stop without blaming the buyer
        ├── deeplinks.spec.ts        unknown page, malformed id never requested, missing event/order
        └── mobile.spec.ts           the journey on a phone, nothing scrolling sideways
```

**One browser context per buyer, never one page.** The `fsid` cookie *is* the identity (ADR-010), so
two contexts are two buyers and two pages in one context are two tabs of the same buyer. Both cases
are tested and conflating them tests neither.

### What it reaches through the API, and what it leaves to the other suites

The suite drives only what exists for buyers and operators: the public API, and the operator's pause
and resume (ADR-078). It never writes to a database or Redis, and the backend has no endpoint that
exists for it. That draws the line:

| Through the browser | Proven elsewhere, because no API creates the state |
| :--- | :--- |
| the journey, the stub's checkout failures, reloads, the stream, pause and resume, two tabs, two sales, the clock, back-off, dead links, a phone | a hold expiring or short of time, a sale ending mid-checkout, a lost counter, a sell-out — server side in `HoldExpiryTimerIT`, `QueueLifecycleIT`, `SalePauseIT`, `CheckoutServiceTest`; client side in `routeFor`, `noticeForTransition`, `checkoutErrorState` and the availability-chip unit tests |

**Must not** — anything already proven cheaper elsewhere. No overbooking races, no restore-once, no
settle-claim concurrency. A browser is the slowest, flakiest place to assert a database invariant,
and `StockReserveConcurrencyIT` already does it in 100 ms.

### The four hard parts, as decided and built

**1. Never sleep; wait on a condition.** Promotion is a real 1 s worker, so a buyer's turn arrives
when it arrives: every spec waits on the UI with a generous timeout. The two exceptions measure time
itself (a reload must not reset a clock).

**2. Skew the clock deliberately.** `clock.spec.ts` installs `page.clock` four minutes fast and checks
the hold still reads about five minutes on the server's clock.

**3. Drive the payment branches by card token, not by mocking.** `pm_card_visa`, `pm_card_declined`,
`pm_card_error` and `pm_card_authenticationRequired` select success, decline, outage and 3-D Secure in
`StubPaymentGateway`, and V4's demo-payments picker exposes all four. Nothing intercepts
`/orders/checkout`. The one intercept in the suite is `ratelimit.spec.ts`, because a real `429` needs
hundreds of requests from one address — a load test, not a browser test — and what is under test
there is the client's back-off.

**4. A fresh buyer per test.** Every spec runs in its own browser context, so it holds its own session,
place, admission and hold; the sale is shared, and the suite buys a few tickets from it per run.

### Not covered yet

Visual regression and automated accessibility assertions (axe). Multi-replica browser runs: the suite
can point at the cluster (`E2E_API=http://localhost:8080 E2E_BASE_URL=http://localhost:8080`, and the
operator's password in `E2E_ADMIN`), but promotion fan-out across replicas is proven by
`docker/scripts/fanout-check.sh`.

---

## 9. Definition of done

Ticked where `frontend/e2e` or the unit suite checks it; the rest is checked by hand in Step 11.

- [x] Every countdown derives from `serverTime` + `expiresAt`; no local decrementing counters (`clock.spec`)
- [x] `maxPerOrder` and all TTLs come from the API; no hardcoded limits
- [x] `GET /sale/{eventId}/state` on mount, `online`, `visibilitychange`, and after every stream frame that changes the view
- [x] The recovery matrix's reload points that the API can reach (`recovery.spec`); expiry while away by unit test (`notices`)
- [x] Queue position clamped monotonic (unit + `recovery.spec`)
- [x] SSE reconnect uses full-jitter backoff; polling fallback after 3 failures (`sse.spec`)
- [ ] Wi-Fi → cellular handover mid-queue keeps position and reconnects — by hand
- [x] Timer at `00:00` **asks the server**; never navigates on a local timer
- [x] "Completing your purchase…" shown when the timer expires mid-charge — never "expired"
- [x] Idempotency key generated once per hold, reused across retries (unit)
- [x] `userSessionId` appears in no request anywhere
- [x] All errors switch on `problem.code`, never on `detail` or status alone (unit: `problemCopy`, `checkoutErrorState`)
- [x] `503 INVENTORY_UNAVAILABLE` never renders as sold out (unit: `problemCopy`, `checkoutErrorState`, `AvailabilityChip`)
- [x] `tabular-nums` on every live-updating numeric
- [x] `aria-live` announces thresholds, not every tick
- [x] `prefers-reduced-motion` respected (every animation and transition off)
- [x] Pay button disabled on click, not debounced
- [x] Two tabs on the same session **and the same sale** converge on the same view (`recovery.spec`)

**Concurrent sales** (rule 5):

- [x] Every per-sale key is namespaced `fs.{eventId}.*`; the global ones are `fs.clockOffsetMs`, `fs.theme` and `fs.recentOrders` (`concurrent.spec`)
- [x] One `EventSource` per event, closed on unmount — never a singleton reassigned between sales
- [x] Holding seats in sale A **and** choosing in sale B, in two tabs, corrupts neither (`concurrent.spec`)
- [x] The same journey in **one** tab, navigating between two sales, corrupts neither (`concurrent.spec`)
- [ ] Two concurrent holds in two different sales each run their own countdown — by hand
- [x] `fs.recentOrders` lists tickets across sales and survives a tab close (`journey.spec`)
- [ ] A promotion in sale A while the tab is showing sale B is not lost — by hand

**Contract honesty:**

- [x] `partial` from `/sale/state` renders as degraded, never as absent (unit: `routeFor`)
- [x] Retries re-POST `/orders/checkout`; no client invents a resume endpoint
- [x] `recaptchaToken` only on join, only with a site key, and the join goes ahead without one; `RATE_LIMITED` backs off with full jitter and a ceiling (`ratelimit.spec`)
- [x] `receiptToken` never reaches a URL the app navigates to by itself, a log, or an analytics call (`journey.spec`; `<meta name="referrer" content="same-origin">`)
- [x] The buyer's email is legible on V5, and the ticket downloads (`journey.spec`)

---

## 10. Design System

The client is calm under a running clock: one indigo brand, generous space, and urgency said in
words as well as colour. Built with MUI 6 in `frontend/src/app/theme.ts`; the shared components are in
`frontend/src/ui`.

### Themes and palette

Light and dark, with a header toggle between the two remembered as `fs.theme`. The system setting
picks the mode only until the buyer first toggles. Every text/background pair is **WCAG AA** — body text at least 4.5:1 on every surface
it sits on, the availability and status chips at least 6:1.

| Token | Light | Dark |
| :--- | :--- | :--- |
| background / paper / subtle | `#F6F7FB` / `#FFFFFF` / `#EEF0F7` | `#0D0F1A` / `#161927` / `#1E2234` |
| text / secondary | `#151826` / `#4B5165` | `#ECEEF8` / `#A6ACC4` |
| primary | `#4338CA`, white text | `#A5B4FC`, `#111427` text |
| success / warning / error / info | `#15803D` / `#B45309` / `#B91C1C` / `#0369A1` | `#4ADE80` / `#FBBF24` / `#F87171` / `#38BDF8` |
| brand gradient (hero, logo) | `#4338CA → #6D28D9 → #BE185D`, white text ≥ 6:1 at every stop | same |

Chips are tinted (12–16 % of their colour) with darkened text in light mode.

### Type, shape, space, motion

- **Type:** the system font stack — nothing to download, and a cluster may have no internet. Headings
  700–800 with tight tracking; `h1` scales from 2.25 rem on a phone to 3 rem. Every live number is
  `tabular-nums`.
- **Shape and space:** an 8 px grid; radius 12 (cards 16); large buttons 48 px tall, sentence case.
- **Motion:** 150–250 ms ease-out; the queue position fades over 400 ms; the hold timer pulses gently
  (≤ 1 Hz) under a minute and stops pulsing in the last ten seconds. `prefers-reduced-motion` turns
  every animation and transition off.
- **Formatting:** money, dates and times through `Intl`, in the buyer's locale and time zone.

### Countdown timers — tone and words

| Timer | Neutral | Warning | Critical |
| :--- | :--- | :--- | :--- |
| Hold (V4) | > 2 min — "Take your time" | 1–2 min, amber — "Complete your purchase soon" | < 1 min, red, gentle pulse — "Less than a minute remaining" |
| Admission (V3) | — | < 1 min, amber | never: running out costs a place, not money |
| Pre-sale (V1) | always | — | — |

A screen reader hears the threshold crossings only — "Two minutes left…", then "Less than a minute
left…" (assertive) — never every second. The hold timer is sticky at the top of V4 and is its own
component, so it re-renders every second and the payment form never does.

### Components

`AppShell` (skip link, sticky header with the brand, "My tickets" and the theme toggle, `<main>`,
footer naming the payment mode) · `PageTitle` (the view's `h1`, focused when the view appears, so every
view change is announced) · `Notice` (the one banner; errors are `role="alert"`, everything else
`role="status"`) · `ErrorState` (problem → copy, Try again, Back to all events, and a support reference
on a `5xx`) · `EmptyState` · `LoadingState` (skeletons in the shape of what is coming, before the
first answer only) · `AvailabilityChip` / `WindowChip` · `Countdown` · `QueuePosition` (`#N`,
`1,000+`, "You're next") · `ConnectionIndicator` · `TierOption` (a radio card — arrow keys move between
tiers) and `TierRow` (display only) · `QuantityStepper` · `ConfirmDialog` (release seats, leave the
line) · `CopyButton`.

### Copy

One table, `frontend/src/copy/problemCopy.ts`, gives every buyer-reachable code in the `05` §2
registry a title and a message that say what happened, what it means for the seats and the money, and
what to do next. A unit test keeps it complete. §7's rules hold throughout: never "error", never a
status number, never "sold out" for a fault, never an accusation for a rate limit.

### Accessibility and layout

Keyboard reaches everything in DOM order; dialogs trap focus and return it. Colour never carries
meaning alone. Layouts are designed at 375 px first; checkout becomes two columns from `md`, with the
order summary beside the form. No view scrolls sideways on a phone (`mobile.spec`).
