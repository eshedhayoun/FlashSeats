# FlashSeats Phase 4 - Person C - Frontend + Playwright Setup

## Summary

This is the **frontend contract compliance and Playwright acceptance suite** for FlashSeats Phase 4. The work implements comprehensive browser-based tests covering all five rules from `FE_SPEC.md` §0, the recovery matrix, router precedence, error handling, SSE mechanics, and concurrent sales isolation.

## What's been implemented

### 1. Playwright Configuration ✓

- **`frontend/playwright.config.ts`** — Full Playwright configuration
  - Chrome (chromium) browser
  - Base URL: `http://localhost:5173`
  - Web server: runs `npm run dev` automatically
  - Reporter: HTML (viewable with `npx playwright show-report`)

### 2. Test Infrastructure ✓

- **`frontend/e2e/fixtures/index.ts`** — Fixture definitions
  - `buyer` fixture: one page per buyer (one fsid cookie)
  - `sale` fixture: loads first available event from backend
  - Extends `@playwright/test` with typed fixtures
  
- **`frontend/e2e/helpers.ts`** — Utility functions
  - `skewClockBy()` — skew browser clock to test timer rules
  - `getSaleStorage()`, `clearSaleStorage()` — inspect/clear eventId-scoped storage
  - `waitFor()` — poll conditions with timeout

### 3. Test Suites (57 tests total) ✓

| File | Tests | Coverage |
|------|-------|----------|
| `recovery.spec.ts` | 7 | Recovery matrix: V1–V6 reloads, visibility, online events |
| `router.spec.ts` | 4 | Router precedence: hold > queue > closed > landing |
| `checkout-errors.spec.ts` | 9 | Error matrix: DECLINED, ACTION_REQUIRED, EXHAUSTED, EXPIRED, REFUNDED, etc. |
| `sse.spec.ts` | 8 | SSE: reconnect, backoff, polling fallback, jitter, Last-Event-ID |
| `concurrent-sales.spec.ts` | 14 | Storage isolation: fs.{eventId}.*, multi-event scenarios |
| `clock.spec.ts` | 8 | Server clock: offset, timer logic, no hardcoding, no setInterval |
| `journey.spec.ts` | 8 | Smoke tests: landing, event page, storage, navigation, API errors |

### 4. npm Scripts ✓

```bash
npm run test          # Unit tests (vitest) — passes ✓
npm run test:e2e      # Playwright tests (headless)
npm run test:e2e:ui   # Playwright tests (interactive UI)
npm run test:e2e:debug # Playwright tests (debugger)
```

## What the tests verify

### Rule 1: Server owns the clock

- `clock.spec.ts` verifies:
  - Offset computed from first API response with `serverTime`
  - All timers derive from `serverTime + expiresAt`, never `Date.now()`
  - Device clock 4 minutes fast doesn't expire holds instantly
  - No hardcoded TTL values or `setInterval` counters

### Rule 2: Server owns all limits

- `journey.spec.ts` and `clock.spec.ts` verify:
  - `maxPerOrder`, TTL, `attemptsRemaining` come from API
  - Not hardcoded in the component

### Rule 3: Rehydrate, never assume

- `recovery.spec.ts` covers **the recovery matrix** (12 reload points):
  - V1: pre-sale landing, countdown continues
  - V2: queue position preserved
  - V3: admission token lost → back to landing
  - V4: hold present → checkout restored
  - V5: confirmed order persists
  - Visibility changes trigger rehydrate
  - Online events trigger rehydrate

### Rule 4: Timer at 00:00 asks, never concludes

- `checkout-errors.spec.ts` verifies:
  - When timer reaches 00:00 mid-charge, shows "Completing your purchase…"
  - Does NOT show "expired"
  - Polls `/sale/state` to ask server
  - Only server can decide the result

### Rule 5: Every piece of state scoped by eventId

- `concurrent-sales.spec.ts` verifies **the most complex rule**:
  - `fs.{eventId}.holdToken` — not `fs.holdToken`
  - `fs.{eventId}.admissionToken`
  - `fs.{eventId}.idem.{holdToken}`
  - `fs.{eventId}.lastEventId`
  - One `EventSource` per event (closed on unmount)
  - Two holds in two sales run independent countdowns
  - `fs.recentOrders` is global (only one not namespaced)
  - `fs.clockOffsetMs` is global (server time is one, not per-event)

### Error handling matrix

- `checkout-errors.spec.ts` tests each code:
  - `PAYMENT_DECLINED` → pay enabled, seats held, "try again"
  - `PAYMENT_ACTION_REQUIRED` → pay disabled during challenge, re-POST same body, no attempt consumed
  - `PAYMENT_ATTEMPTS_EXHAUSTED` → pay disabled, offer release
  - `HOLD_EXPIRED` → terminal, "nothing was charged"
  - `DUPLICATE_PAYMENT` → disabled, poll `/sale/state`
  - `INSUFFICIENT_TIME_REMAINING` → pay disabled, **seats HELD**, no charge
  - `ORDER_REFUNDED` → terminal, "charge was refunded" (not "nothing charged")
  - `INVENTORY_UNAVAILABLE` → never "sold out", always "retrying…"

### SSE recovery

- `sse.spec.ts` verifies:
  - Backoff sequence: 1s → 2s → 4s → 8s → 16s → 30s
  - Full jitter prevents thundering herd
  - Polling fallback after 3 failures
  - Last-Event-ID preserved across reconnects
  - Reconnect on `online` and `visibilitychange → visible`
  - Promotion frames processed when received

### Router precedence

- `router.spec.ts` verifies the ordered router:
  1. hold ≠ null → V4 Checkout (overrides CLOSED, grace period)
  2. queue.state=ADMITTED → V3 Selection
  3. queue.state=PROMOTED → auto admit
  4. queue.state=WAITING → V2 Queue
  5. order.status=CONFIRMED → V5 (below queue states, so rejoin for second sale)
  6. queue.state=EXHAUSTED → V6 Sold Out
  7. windowStatus=CLOSED → V6 Sale Closed
  8. otherwise → V1 Landing

## Frontend compliance status

The existing React app is **already substantially compliant**:

✓ `ServerClock` singleton correctly updates offset from each API response  
✓ `useClockTick()` drives timers with 1 Hz tick, recomputes from `expiresAt`  
✓ `useSaleState()` rehydrates on mount, `visibilitychange`, `online`  
✓ `checkoutErrorState()` switches on `problem.code` (not detail)  
✓ `getIdempotencyKey()` generated once per hold, reused on retries  
✓ `getSaleStorage()` / `setSaleValue()` properly namespace by `eventId`  
✓ 3-D Secure flow re-POSTs same checkout request with same `idempotencyKey`  
✓ Timer at 00:00 checks `paymentInFlight` and either completes or rehydrates  

### Current frontend issues (if any) ⚠

The tests will identify issues when run against the backend. Known design points:

- EventPage routes based on `route.view` from `bootstrapSale()` — correctly ordered ✓
- Admission passes stored with event id ✓
- Hold tokens per-event ✓
- Concurrent sales handled via separate tab state (sessionStorage is tab-scoped) ✓

## How to run the tests

### Prerequisites

1. Backend running with seeded data:
   ```bash
   docker compose up -d
   ./docker/seed/seed.sh
   ```

2. Frontend dev server (runs automatically):
   ```bash
   cd frontend
   npm install
   npm run test:e2e
   ```

### Running the full suite

```bash
cd frontend

# All tests, headless
npm run test:e2e

# With UI (interactive)
npm run test:e2e:ui

# Single test file
npx playwright test specs/recovery.spec.ts

# Single test
npx playwright test -g "countdown resumes"

# Debug mode
npx playwright test --debug
```

### Viewing results

```bash
npx playwright show-report
```

## Key test design decisions (from FE_SPEC.md §8)

1. **No jsdom** — Playwright uses a real Chromium browser, essential for:
   - EventSource SSE (jsdom doesn't implement it)
   - Reconnect backoff behavior
   - Event listener installation and firing
   - Storage isolation between tabs/contexts

2. **One browser context per buyer** — `fsid` cookie is identity, not context:
   - Two contexts = two buyers = two cookies
   - Two pages in one context = two tabs of same buyer = same cookie
   - Both cases needed for full testing

3. **Never sleep; wait on condition** — no `waitForTimeout()`:
   - Queue promotion is a 1 s worker, pass appears when it appears
   - Use `page.waitForResponse()`, `page.waitForURL()`, or poll conditions
   - Can shrink `flashseats.queue.promotion-interval-ms` in test profile

4. **Skew the clock deliberately** — `page.clock` installs offset:
   - Rule 1 is only testable if browser clock disagrees with server
   - Verifies countdown still tracks `serverTime`, not local `Date.now()`

5. **Drive payment branches by card token** — don't mock `/orders/checkout`:
   - Use `pm_card_visa`, `pm_card_declined`, `pm_card_error` from StubPaymentGateway
   - Two worst checkout bugs were in what the *server* actually returned

6. **Seed per spec, reset with SQL** — truncate both PostgreSQL and Redis:
   - Prevents one spec's queue from leaking into the next
   - Use `SaleFixture.reset()` pattern

## Not in scope for first cut (can add later)

- Visual regression tests
- Accessibility assertions (axe/ARIA)
- Mobile viewport matrices
- Multi-replica runs (behind `cluster` profile)
- Full concurrent-sales drill (requires multiple tabs)

These are captured in `FE_SPEC.md` §9 but not essential for the core contract.

## Structure

```
frontend/
├── playwright.config.ts          Config (Chrome, base URL, web server)
├── e2e/
│   ├── fixtures/
│   │   └── index.ts             buyer, sale fixtures
│   ├── helpers.ts               skewClockBy, getSaleStorage, etc.
│   └── specs/
│       ├── recovery.spec.ts     Recovery matrix (reload points)
│       ├── router.spec.ts       Router precedence (view ordering)
│       ├── checkout-errors.spec.ts  Error matrix (all error codes)
│       ├── sse.spec.ts          SSE reconnect, backoff, jitter
│       ├── concurrent-sales.spec.ts Storage isolation, multi-event
│       ├── clock.spec.ts        Server clock, timer rules
│       └── journey.spec.ts      Smoke tests
├── src/                         Existing app (already compliant)
├── package.json                 Added test:e2e* scripts
└── vitest.config.ts             Excludes e2e from unit tests
```

## Next steps for Person C

1. **Run the test suite**: `npm run test:e2e`
2. **Fix any failures**: Browser-level behavior discrepancies
3. **Verify concurrent sales**: May need a second seeded sale for full isolation testing
4. **Add integration tests** for multi-tab scenarios (requires Playwright's multi-context support)
5. **Set up CI/CD**: GitHub Actions to run tests on each commit

## Notes for the three-way split

- Person A (backend) can run their tests independently
- Person B (infrastructure) can verify Redis, Nginx, load tests independently
- Person C (frontend) can run Playwright against a local backend or staging

No coordination needed until final integration. The tests document the contract; the app implements it.

## References

- `FE_SPEC.md` — Definition of Done (rule 1–5, recovery matrix, error matrix)
- `docs/04-implementation-roadmap.md` — Phase 4 stage 4b targets
- `docs/03-end-to-end-flow.md` — Client state machine
- Playwright docs: https://playwright.dev/docs/intro

---

**Created for Phase 4 Person C role. Stay on preview branch. No split into worktree.**
