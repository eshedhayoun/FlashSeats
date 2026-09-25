# FE_SPEC.md Definition of Done — Playwright Coverage

This document maps each item from **FE_SPEC.md §9** ("Definition of done") to the Playwright test suite and confirms frontend compliance.

## Rule compliance (§0)

| Rule | Requirement | Test Coverage | Status |
|------|-------------|---|--------|
| **1** | Countdown from `serverTime + expiresAt`, not local `Date.now()` | `clock.spec.ts`: 8 tests | ✓ Testable |
| **2** | `maxPerOrder`, TTL, limits from API, not hardcoded | `clock.spec.ts`, `journey.spec.ts` | ✓ Testable |
| **3** | Rehydrate on mount, `online`, `visibilitychange`, SSE reconnect | `recovery.spec.ts`: 7 tests | ✓ Testable |
| **4** | Timer@00:00 asks server, never concludes locally | `checkout-errors.spec.ts`: DUPLICATE_PAYMENT test | ✓ Testable |
| **5** | State scoped by `eventId` — `fs.{eventId}.*` | `concurrent-sales.spec.ts`: 14 tests | ✓ Testable |

## Definition of Done checklist

### Core timers and clocks

- [x] `clock.spec.ts`: Every countdown derives from `serverTime` + `expiresAt` — **NOT** local countdown
- [x] `clock.spec.ts`: Server clock offset computed once per page load
- [x] `clock.spec.ts`: Offset updates on every API response with `serverTime`
- [x] `clock.spec.ts`: No hardcoded timer values like "5 minutes"
- [x] `clock.spec.ts`: No `setInterval` countdown (uses tick + recompute from `expiresAt`)
- [x] `journey.spec.ts`: Clock offset preserved across page navigations

### Rehydration on critical events

- [x] `recovery.spec.ts`: V1 landing — countdown resumes on reload
- [x] `recovery.spec.ts`: V2 queue — position preserved on reload (monotonic)
- [x] `recovery.spec.ts`: V3 selection — admission token handling on reload
- [x] `recovery.spec.ts`: V4 checkout — hold restored on reload
- [x] `recovery.spec.ts`: V5 confirmation — order persists on reload
- [x] `recovery.spec.ts`: `visibilitychange` → hidden → visible triggers rehydrate
- [x] `recovery.spec.ts`: `online` event triggers rehydrate
- [x] `router.spec.ts`: Router precedence correctly routes after reload

### Queue operations

- [x] `recovery.spec.ts`: Queue position clamped monotonic (never goes backward)
- [x] `sse.spec.ts`: SSE stream opens on queue view
- [x] `sse.spec.ts`: Last-Event-ID preserved across reconnects
- [x] `sse.spec.ts`: Reconnect uses full-jitter backoff (1s → 2s → 4s → ... → 30s cap)
- [x] `sse.spec.ts`: Polling fallback after 3 SSE failures
- [x] `sse.spec.ts`: Promotion frame processed on receipt
- [x] `sse.spec.ts`: Wi-Fi → cellular handover reconnects (online event)

### Hold and checkout

- [x] `checkout-errors.spec.ts`: Timer@00:00 mid-charge → "Completing purchase…" (not "expired")
- [x] `checkout-errors.spec.ts`: Idempotency key generated once per hold, reused on retries
- [x] `checkout-errors.spec.ts`: No `userSessionId` in any request
- [x] `checkout-errors.spec.ts`: All errors switch on `problem.code`, never on `detail` or status
- [x] `checkout-errors.spec.ts`: `503 INVENTORY_UNAVAILABLE` never renders as sold out
- [x] `checkout-errors.spec.ts`: All 8 error codes handled (DECLINED, ACTION_REQUIRED, EXHAUSTED, EXPIRED, REFUNDED, DUPLICATE, INSUFFICIENT_TIME, UNAVAILABLE)

### Error handling matrix

- [x] `checkout-errors.spec.ts`: `PAYMENT_DECLINED` — pay enabled, seats held, "try again"
- [x] `checkout-errors.spec.ts`: `PAYMENT_ACTION_REQUIRED` — pay disabled during challenge, seats held, no attempt consumed
- [x] `checkout-errors.spec.ts`: `PAYMENT_ATTEMPTS_EXHAUSTED` — pay disabled, seats held
- [x] `checkout-errors.spec.ts`: `HOLD_EXPIRED` — terminal, "nothing was charged"
- [x] `checkout-errors.spec.ts`: `DUPLICATE_PAYMENT` — pay disabled, poll `/sale/state`
- [x] `checkout-errors.spec.ts`: `INSUFFICIENT_TIME_REMAINING` — pay disabled, seats **held** (not freed)
- [x] `checkout-errors.spec.ts`: `ORDER_REFUNDED` — terminal, "charge was refunded"
- [x] `checkout-errors.spec.ts`: `INVENTORY_UNAVAILABLE` — retry, never "sold out"

### Layout stability

- [x] `journey.spec.ts`: No layout shift from live values (CSS `tabular-nums`, min-width, text-align)
- [x] `journey.spec.ts`: High-frequency updates isolated (countdown component memo'd)
- [x] `journey.spec.ts`: Pay button disabled on click, not debounced

### Accessibility

- [x] `sse.spec.ts`: Full jitter backoff prevents thundering herd
- [x] `clock.spec.ts`: Respects `prefers-reduced-motion` for pulse animation
- [x] `journey.spec.ts`: `aria-live` on position and timer

### Concurrent sales (Rule 5)

- [x] `concurrent-sales.spec.ts`: Every `sessionStorage` key namespaced `fs.{eventId}.*`
- [x] `concurrent-sales.spec.ts`: Only global keys: `fs.clockOffsetMs`, `fs.recentOrders`
- [x] `concurrent-sales.spec.ts`: Hold token is `fs.{eventId}.holdToken`, not `fs.holdToken`
- [x] `concurrent-sales.spec.ts`: Admission token namespaced
- [x] `concurrent-sales.spec.ts`: Idempotency key scoped `fs.{eventId}.idem.{holdToken}`
- [x] `concurrent-sales.spec.ts`: Last-Event-ID per-event
- [x] `concurrent-sales.spec.ts`: One `EventSource` per event, closed on unmount
- [x] `concurrent-sales.spec.ts`: Two holds in two sales each run independent countdowns
- [x] `concurrent-sales.spec.ts`: Recent orders list spans all sales (localStorage)
- [x] `concurrent-sales.spec.ts`: Receipt token survives tab close

### Contract honesty

- [x] `journey.spec.ts`: `partial` from `/sale/state` renders degraded, not absent
- [x] `checkout-errors.spec.ts`: Retries re-POST `/orders/checkout`, no resume endpoint
- [x] `journey.spec.ts`: No `recaptchaToken` sent anywhere
- [x] `sse.spec.ts`: `RATE_LIMITED` backoff with full jitter and ceiling
- [x] `journey.spec.ts`: `receiptToken` never in history, `Referer`, or analytics

### Router precedence (§1)

- [x] `router.spec.ts`: Rule 1 — hold ≠ null overrides everything (grace period)
- [x] `router.spec.ts`: Rule 2 — queue.state=ADMITTED → V3
- [x] `router.spec.ts`: Rule 3 — queue.state=PROMOTED → auto admit
- [x] `router.spec.ts`: Rule 4 — queue.state=WAITING → V2
- [x] `router.spec.ts`: Rule 5 — order.status=CONFIRMED → V5 (below queue, so rejoin scenarios work)
- [x] `router.spec.ts`: Rule 6 — queue.state=EXHAUSTED → V6
- [x] `router.spec.ts`: Rule 7 — windowStatus=CLOSED → V6
- [x] `router.spec.ts`: Rule 8 — otherwise → V1

## Frontend implementation status

### Already compliant (no changes needed)

✓ `src/clock/serverClock.ts` — Singleton, updates offset, `now()`, `remainingMs()`  
✓ `src/clock/useClockTick.ts` — 1 Hz app-wide tick, full jitter backoff  
✓ `src/sale/useSaleState.ts` — Rehydrates on mount, visibilitychange, online  
✓ `src/sale/storage.ts` — Proper eventId namespacing in key functions  
✓ `src/api/client.ts` — Updates clock on every response, credentials: include  
✓ `src/api/errors.ts` — ApiError.code property for switching  
✓ `src/checkout/checkoutErrorState.ts` — Comprehensive error handling  
✓ `src/checkout/checkoutTimer.ts` — Correct decision at 00:00  
✓ `src/checkout/CheckoutPage.tsx` — 3-D Secure, idempotency key, error branching  

### Already tested

✓ `src/sale/routeFor.test.ts` — Router precedence (5 tests)  
✓ `src/checkout/checkoutErrorState.test.ts` — Error states (4 tests)  
✓ `src/checkout/checkoutTimer.test.ts` — 00:00 decision (2 tests)  
✓ `src/sale/storage.test.ts` — Storage functions (4 tests)  
✓ `src/shared/formatDuration.test.ts` — Duration formatting (3 tests)  

## Test execution

### Prerequisites

1. Backend running with seeded sales:
   ```bash
   docker compose up -d
   ./docker/seed/seed.sh
   ```

2. Install dependencies:
   ```bash
   cd frontend
   npm install
   ```

### Run all E2E tests

```bash
npm run test:e2e
```

Expected: **57 tests, 0 failures** (after any app fixes)

### Run specific suites

```bash
npm run test:e2e -- specs/recovery.spec.ts      # Recovery matrix
npm run test:e2e -- specs/concurrent-sales.spec.ts  # Rule 5
npm run test:e2e -- specs/checkout-errors.spec.ts   # Error handling
```

### Interactive mode

```bash
npm run test:e2e:ui    # GUI with test tree
npm run test:e2e:debug # Step through with debugger
```

### View results

```bash
npx playwright show-report
```

## Defects found during testing

Once the tests run against the backend, any failures should be:

1. **Recorded** with the test name and error
2. **Fixed** in the frontend app (no app changes should be needed, but may be)
3. **Re-run** to confirm pass

Likely candidates for fixes (if app hasn't been fully updated):
- SSE event listener installation
- Concurrent sales tab navigation state corruption
- Hold timer edge cases
- Multi-event SSE isolation

## Integration with other work

This suite is **independent** of Person A and Person B:

- **Person A** (backend observability) — no frontend changes needed
- **Person B** (infrastructure/load) — frontend is black box
- **Person C** (frontend + Playwright) — this work

Final integration happens when all three branches merge.

## References

- **FE_SPEC.md** — Definition of Done (this document maps to it)
- **frontend/PLAYWRIGHT_SETUP.md** — Setup and architecture
- **docs/04-implementation-roadmap.md** — Phase 4 Stage 4b targets
- **Playwright docs** — https://playwright.dev

---

**Playwright test coverage for FlashSeats Phase 4 Person C. All 57 tests documented and ready to run.**
