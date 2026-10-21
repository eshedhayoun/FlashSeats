# FlashSeats web client

The React client for the buyer journey and the operator console: the event list, an event's page,
the waiting room, seat selection, checkout (stub gateway or Stripe), the receipt and the PDF ticket.
React 18, MUI 6, Vite, TypeScript. The API it calls is in [`docs/api.md`](../docs/api.md).

Under `--profile cluster` the nginx image builds it and serves it at `:8080`. The backend's
`src/main/resources/static/index.html` is a minimal API demo, not this client.

## Run it

```bash
# backend first, from the repo root:
docker/scripts/dev-up.sh && ./mvnw spring-boot:run
cd frontend
npm install
cp .env.example .env.local     # leave VITE_STRIPE_PUBLISHABLE_KEY blank to drive the stub gateway
npm run dev                    # http://localhost:5173, proxies /api to http://localhost:8080
```

With the key blank, checkout offers the stub's outcomes: success, decline, provider outage and 3-D
Secure. A `pk_test_...` key drives real Stripe; the backend then needs `STRIPE_ENABLED=true` and the
matching secret key. `VITE_RECAPTCHA_SITE_KEY` turns on reCAPTCHA for joining the line.

## Rules

1. **The server owns the clock.** Every countdown derives from `serverTime`, never `Date.now()` alone.
2. **The server owns the limits.** Max per order, TTLs and attempts come from the API.
3. **Rehydrate, never assume.** Every mount and reconnect calls `GET /sale/{eventId}/state`; local
   storage is only a hint for the first paint.
4. **A timer at zero is a prompt to ask**, not a conclusion: call the API and let it decide.
5. **All state is scoped by event.** A buyer can be in several sales at once, so every storage key is
   `fs.{eventId}.{name}` and each sale has its own `EventSource`.

## Views

`/events/:eventId` renders one view, chosen from the server's state for that sale by `routeFor`, in
this order (first match wins):

| # | Condition | View |
| :--- | :--- | :--- |
| 1 | A hold exists | **V4** Checkout |
| 2 | Queue `ADMITTED` | **V3** Seat selection |
| 3 | Queue `PROMOTED` | Admit automatically, then V3 |
| 4 | Queue `WAITING` | **V2** Waiting room |
| 5 | Latest order `CONFIRMED` | **V5** Confirmation |
| 6 | Queue `EXHAUSTED` | **V6** Sold out |
| 7 | Sale `CLOSED` | **V6** Sale ended |
| 8 | Otherwise | **V1** Event page |

A paused sale is never shown as ended. `UNKNOWN` availability is shown as "Checking…", never as sold
out. The receipt token is a bearer capability: it appears only inside a link, never as text.

## Layout

| Folder | Holds |
| :--- | :--- |
| `app` | Shell, theme (light and dark), routes, error boundary |
| `sale` | `/sale/{id}/state`, the view router, per-sale storage, notices |
| `events`, `landing`, `queue`, `selection`, `checkout`, `orders`, `terminal`, `notfound` | One folder per view |
| `admin` | The operator console |
| `api` | Typed client: RFC 7807 problems, `Retry-After`, bounded retries |
| `clock` | The server's clock and the one app-wide tick |
| `copy` | What the buyer is told for every error code |
| `format` | Money, dates, durations, queue positions |
| `ui` | Shared components |

## Tests

| Command | Checks | Needs |
| :--- | :--- | :--- |
| `npm test` | Vitest unit tests: routing, notices, the checkout error matrix, error copy, storage, formatting | — |
| `npm run build` | Type check and production bundle | — |
| `npm run test:e2e` | 27 Playwright specs against the real backend: the journey, checkout failures, reloads, the stream, two tabs, two sales, a skewed clock, a phone layout | Backend on `:8080` with an open sale (`docker/scripts/dev-up.sh`) |

The e2e suite uses only the API buyers and operators use: no SQL, no Redis, no test-only endpoints.
States no API can create (a hold expiring, a lost counter, a sell-out) are covered by the backend's
integration tests. `E2E_API`, `E2E_ADMIN` and `E2E_BASE_URL` point it elsewhere.
