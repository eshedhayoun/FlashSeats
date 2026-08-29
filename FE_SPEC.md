# FlashSeats — Front-End Design Specification

**Stack:** React 18 + TypeScript (Vite) · MUI v5 · native `EventSource` · Stripe.js
**Backend contract:** [`docs/03-end-to-end-flow.md`](docs/03-end-to-end-flow.md) ·
[`docs/05-global-standards.md`](docs/05-global-standards.md) ·
[`docs/00-architecture-decisions.md`](docs/00-architecture-decisions.md)

---

## 0. Four rules that override everything else

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

---

## 1. View state machine

```
                        ┌──────────────────────────────────────────┐
                        │  every mount: GET /sale/{eventId}/state  │
                        └────────────────────┬─────────────────────┘
                                             ▼
   windowStatus=UPCOMING ──────────► V1  Pre-Sale Landing
   windowStatus=CLOSED   ──────────► V6  Sale Closed
   queue.state=NOT_JOINED ─────────► V1  (Join enabled)
   queue.state=WAITING ────────────► V2  Virtual Waiting Room
   queue.state=PROMOTED ───────────► V2 → auto POST /queue/admit → V3
   queue.state=ADMITTED, hold=null ► V3  Seat Selection
   queue.state=EXHAUSTED ──────────► V6  Sold Out
   hold≠null, order=null|FAILED ───► V4  Billing & Checkout
   order.status=PENDING ───────────► V4  (payment in flight — poll)
   order.status=CONFIRMED ─────────► V5  Order Confirmation
```

This table **is** the router. Do not derive the view from navigation history — a buyer who reloads,
hits Back, or opens a second tab must land on the view the server says they are in.

---

### V1 — Pre-Sale Landing

**Route:** `/events/:eventId`

**Renders:** hero, venue, date, tier cards (name, price, `availability` badge), countdown to
`saleStartTime`, primary CTA.

| `windowStatus` | CTA |
| :--- | :--- |
| `UPCOMING` | disabled, `HH:MM:SS` countdown |
| `OPEN` | **"Join Flash Sale"** enabled |
| `CLOSED` | → V6 |

**Availability badges** — bucketed, never numeric (ADR-027):

| `availability` | Badge | Colour |
| :--- | :--- | :--- |
| `PLENTY` | "Available" | success |
| `LIMITED` | "Limited" | warning |
| `SOLD_OUT` | "Sold Out" | disabled |

> Exact counts are deliberately not exposed: they drive panic-buying and give scalpers a live feed.

**At `T-0`:** flip the CTA live client-side from the countdown. Do **not** auto-submit — a self-firing
join at exactly `t=0` from every open tab is indistinguishable from a bot, and reCAPTCHA will score
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

**Renders:** order summary, **sticky hold countdown**, email input, Stripe Payment Element, pay CTA,
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

A charge already submitted to Stripe **will complete**. The webhook finalises the order even if the
browser is closed (ADR-012). Telling a buyer their reservation expired while their card is being
charged is the worst possible message and it would be false.

**Payment submission:**

```ts
const idempotencyKey = sessionStorage.getItem('fs.idem')
  ?? crypto.randomUUID();                   // generated ONCE per hold, reused on every retry
sessionStorage.setItem('fs.idem', idempotencyKey);
setPaymentInFlight(true);                   // disables CTA and freezes the expiry branch
```

The key is generated **once per hold** and reused across retries. Regenerating it per attempt defeats
the gateway-level guard (ADR-014).

**Outcome handling:**

| Response | Handling |
| :--- | :--- |
| `201` / `200` | → V5 |
| `402 PAYMENT_DECLINED` | Inline: "Card declined — try another." Form stays populated except the card. **Hold is retained.** Show `attemptsRemaining`. **No timer extension** (ADR-030) |
| `402 PAYMENT_ACTION_REQUIRED` | 3-D Secure → see below |
| `402 PAYMENT_ATTEMPTS_EXHAUSTED` | Terminal. Offer re-entry to the queue |
| `409` + `expiresAt` < 45 s | "Not enough time left to complete this safely." Offer release + re-queue |
| `410 HOLD_EXPIRED` | Expired panel. Nothing charged — **say so explicitly** |
| `503 PAYMENT_GATEWAY_UNAVAILABLE` | "Payment provider is having trouble. **Your seats are held.**" Retry after `retryAfterSeconds` |
| `409 DUPLICATE_PAYMENT` | Ignore — a charge is in flight. Poll `/sale/state` every 2 s |

**3-D Secure:**

```ts
if (code === 'PAYMENT_ACTION_REQUIRED') {
  sessionStorage.setItem('fs.pi', stripePaymentIntentId);   // survives the redirect
  const { error } = await stripe.handleNextAction({ clientSecret });
  if (!error) await post('/api/v1/orders/checkout/resume', { holdToken });
}
```

`paymentInFlight` stays `true` for the entire challenge. Some methods redirect away entirely — on
return, `fs.pi` in sessionStorage plus `/sale/state` restores the flow. The +120 s grace was granted
before the charge, so the challenge window is already covered (ADR-006, ADR-030).

**Email:** validated client-side for shape only. Show it back on V5 prominently — a typo means the
tickets go nowhere and there is no recovery path.

---

### V5 — Order Confirmation

**Route:** `/orders/:orderNumber`

**Renders:** success state, `TK-98213` in large monospace with copy-to-clipboard, item summary,
total, buyer email, "check your inbox" notice, download CTA.

Email delivery is **asynchronous** — the PDF may take a few seconds. Do not promise it has arrived:

> "We're sending your tickets to **buyer@example.com**. They usually arrive within a minute."

Clear `fs.holdToken`, `fs.idem`, `fs.pi`. **Keep** `orderNumber` and `receiptToken` — they are how a
buyer returns to this page. The URL carries `?receiptToken=…` so the page survives a cookie clear
and works from the confirmation email (ADR-010).

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

---

## 2. API mapping

Base `/api/v1`. `fsid` is an `HttpOnly` cookie — **JavaScript never reads or sets it**; send
`credentials: 'include'` on every request.

| View | Method | Path | Headers | Body | Success | Error codes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| V1 | `GET` | `/events` | — | — | `200` | — |
| V1 | `GET` | `/events/{eventId}` | — | — | `200` | `EVENT_NOT_FOUND` |
| all | `GET` | `/sale/{eventId}/state` | — | — | `200` | `EVENT_NOT_FOUND` |
| V1→V2 | `POST` | `/queue/join` | — | `{eventId, recaptchaToken}` | `202` | `SALE_NOT_OPEN`, `RATE_LIMITED`, `BOT_VERIFICATION_FAILED` |
| V2 | `GET` | `/queue/stream?eventId=` | `Accept: text/event-stream` | — | SSE | — |
| V2 | `GET` | `/queue/status?eventId=` | — | — | `200` | `NOT_IN_QUEUE` |
| V2→V3 | `POST` | `/queue/admit` | `X-Queue-Pass-Token` | `{eventId}` | `200` | `QUEUE_PASS_INVALID`, `QUEUE_PASS_EXPIRED` |
| V3 | `POST` | `/holds` | `X-Admission-Token` | `{eventId, tierId, quantity}` | `201` | `INSUFFICIENT_STOCK`, `QUANTITY_EXCEEDS_LIMIT`, `HOLD_LIMIT_EXCEEDED`, `ADMISSION_EXPIRED`, `INVENTORY_UNAVAILABLE` |
| V4 | `GET` | `/holds/{holdToken}` | — | — | `200` | `HOLD_NOT_FOUND`, `HOLD_EXPIRED` |
| V4 | `DELETE` | `/holds/{holdToken}` | — | — | `200` | `HOLD_NOT_FOUND` |
| V4 | `POST` | `/orders/checkout` | — | `{holdToken, userEmail, paymentMethodId, idempotencyKey}` | `201`/`200` | `PAYMENT_DECLINED`, `PAYMENT_ACTION_REQUIRED`, `PAYMENT_ATTEMPTS_EXHAUSTED`, `HOLD_EXPIRED`, `DUPLICATE_PAYMENT`, `PAYMENT_GATEWAY_UNAVAILABLE`, `CHECKOUT_WINDOW_CLOSED` |
| V4 | `POST` | `/orders/checkout/resume` | — | `{holdToken}` | `201`/`200` | as above |
| V5 | `GET` | `/orders/{orderNumber}?receiptToken=` | — | — | `200` | `ORDER_NOT_FOUND` |

> **`userSessionId` is never sent** — not in a body, not in a header, not in a query string. Identity
> comes from the signed cookie alone (ADR-010). A request that carries it will be rejected.

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

| Key | Contents | Lifetime |
| :--- | :--- | :--- |
| `fs.admissionToken` | admission token (V3–V4) | until admission expires |
| `fs.holdToken` | active hold | until settled |
| `fs.idem` | idempotency key, **one per hold** | until settled |
| `fs.pi` | Stripe PaymentIntent id | across the 3-DS redirect |
| `fs.clockOffsetMs` | server-clock delta | per tab |
| `fs.lastEventId` | SSE `Last-Event-ID` | per tab |

**`localStorage`:** only `fs.recentOrders` — a list of `{orderNumber, receiptToken, eventTitle}` so a
returning buyer can find their tickets. Nothing security-sensitive; nothing the server needs.

**Never stored anywhere:** `fsid` (HttpOnly by design), card data, `queuePassToken` (lives ~2 s in
memory before being exchanged).

### Rehydration — the recovery protocol

```ts
async function bootstrap(eventId: number) {
  const state = await api<SaleState>(`/sale/${eventId}/state`);
  clockOffsetMs = Date.parse(state.serverTime) - Date.now();

  if (state.hold)  sessionStorage.setItem('fs.holdToken', state.hold.holdToken);
  else             sessionStorage.removeItem('fs.holdToken');

  return routeFor(state);          // the §1 table
}
```

Runs on: initial mount, `visibilitychange` → visible, `online`, SSE reconnect, and after any `409`
or `410`. It is cheap (four in-process facade reads) and it is the difference between a resilient
SPA and a fragile one.

**Recovery matrix — every reload point:**

| Reloaded at | Server state | Result |
| :--- | :--- | :--- |
| V1, pre-sale | `UPCOMING` | Countdown resumes |
| V2, position #120 | `WAITING` | **Same position.** SSE reopens with `Last-Event-ID` |
| V2, promoted during reload | `PROMOTED` + `passToken` | Auto-admit → V3. The pass was waiting in Redis |
| V3, no hold | `ADMITTED` | Seat picker, admission timer resumed |
| V4, hold live | `hold` + remaining TTL | Checkout, timer resumed from `expiresAt` |
| V4, mid-3-DS | `order: PENDING` | Resume panel; poll every 2 s |
| V4, charge settled during reload | `order: CONFIRMED` | Straight to V5 — **the reload cost nothing** |
| V4, hold expired while away | `hold: null` | Expired panel, "nothing was charged" |
| Second tab opened | same session | Both tabs converge on the same state |

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
    sessionStorage.setItem('fs.lastEventId', (e as MessageEvent).lastEventId);
  });

  es.addEventListener('queue-promoted',    e => admit(JSON.parse(e.data).passToken));
  es.addEventListener('tier-availability', e => setTiers(JSON.parse(e.data).tiers));
  es.addEventListener('sale-exhausted',    () => { es.close(); goTo('V6:SOLD_OUT'); });
  es.addEventListener('sale-closed',       () => { es.close(); goTo('V6:SALE_CLOSED'); });

  es.onopen  = () => { setConn('open'); attempt = 0; };
  es.onerror = () => { es.close(); scheduleReconnect(eventId); };
  return es;
}
```

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

## 5. reCAPTCHA v3

Loaded once on V1. Executed **only** on the "Join Flash Sale" press — tokens expire in 120 s, so
executing on page load yields a stale token for anyone who reads the page first.

```ts
async function joinSale(eventId: number) {
  const token = await grecaptcha.execute(SITE_KEY, { action: 'join_sale' });
  return api('/queue/join', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ eventId, recaptchaToken: token }),
  });
}
```

`action` must be exactly `join_sale` — the server validates it. This is the only endpoint that takes
a token; the verdict is cached server-side for 30 min (ADR-011).

**On `403 BOT_VERIFICATION_FAILED`:** never say "you look like a bot." Say "We couldn't verify your
browser. Please try again," and allow one retry with a fresh token. False positives are real, and
accusing a paying customer is worse than admitting a few scripts.

**If reCAPTCHA fails to load:** submit without a token. The server fails open and relies on rate
limits (ADR-011). Do not block the sale on a third-party script.

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
Stripe Element.

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

## 8. Definition of done

- [ ] Every countdown derives from `serverTime` + `expiresAt`; no local decrementing counters
- [ ] `maxPerOrder` and all TTLs come from the API; no hardcoded limits
- [ ] `GET /sale/{eventId}/state` on mount, `online`, `visibilitychange`, and SSE reconnect
- [ ] All twelve rows of the recovery matrix (§3) verified by hand
- [ ] Queue position clamped monotonic
- [ ] SSE reconnect uses full-jitter backoff; polling fallback after 3 failures
- [ ] Wi-Fi → cellular handover mid-queue keeps position and reconnects
- [ ] Timer at `00:00` **asks the server**; never navigates on a local timer
- [ ] "Completing your purchase…" shown when the timer expires mid-charge — never "expired"
- [ ] Idempotency key generated once per hold, reused across retries
- [ ] `userSessionId` appears in no request anywhere
- [ ] All errors switch on `problem.code`, never on `detail` or status alone
- [ ] `503 INVENTORY_UNAVAILABLE` never renders as sold out
- [ ] `tabular-nums` on every live-updating numeric; zero layout shift
- [ ] `aria-live` announces thresholds, not every tick
- [ ] `prefers-reduced-motion` respected
- [ ] Pay button disabled on click, not debounced
- [ ] Two tabs on the same session converge on the same view
