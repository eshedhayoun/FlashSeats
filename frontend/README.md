# FlashSeats web client

The React client for the buyer journey in [`FE_SPEC.md`](../FE_SPEC.md): the event index, an event's
page, the waiting room, seat selection, checkout (the stub gateway or Stripe), the receipt and the PDF
ticket. React 18, MUI 6, Vite, TypeScript.

Under `--profile cluster` the nginx image builds it and serves it at `:8080` (ADR-068), and
`docker/scripts/professor-demo.sh` starts that whole stack in one command. For development it runs on
its own dev server, below. The backend's `src/main/resources/static/index.html` is a minimal API demo,
not this client.

## Run it

```bash
# backend first, from the repo root:
docker/scripts/dev-up.sh && ./mvnw spring-boot:run
cd frontend
npm install
cp .env.example .env.local     # leave VITE_STRIPE_PUBLISHABLE_KEY blank to drive the stub gateway
npm run dev                    # http://localhost:5173, proxies /api to http://localhost:8080
```

With the key blank, checkout offers the stub gateway's outcomes — success, decline, provider outage
and 3-D Secure — so every branch is walkable with no account. A `pk_test_...` key drives real Stripe,
and the backend must then run with `STRIPE_ENABLED=true` and the matching secret key. An optional
`VITE_RECAPTCHA_SITE_KEY` turns on reCAPTCHA v3 for joining the line; without it the join is sent with
no token and the server falls back to rate limits (ADR-055).

## Layout

| Folder | Holds |
| :--- | :--- |
| `app` | the shell, theme (light and dark), routes, error boundary |
| `sale` | `/sale/{id}/state`, the router that picks the view (`routeFor`), per-sale storage, the notices that say what just happened |
| `events`, `landing`, `queue`, `selection`, `checkout`, `orders`, `terminal`, `notfound` | one folder per view of the FE_SPEC state machine |
| `api` | the typed client: RFC 7807 problems, `Retry-After`, bounded retries on back-pressure |
| `clock` | the server's clock and the one app-wide tick every countdown runs on |
| `copy` | what the buyer is told for every error code |
| `format` | money, dates, durations, queue positions |
| `ui` | the shared components — FE_SPEC §10 |

## Tests

| Command | What it checks | Needs |
| :--- | :--- | :--- |
| `npm test` | Vitest unit tests: view routing, notices, the checkout error matrix, copy for every error code, storage, formatting, polling cadence | nothing |
| `npm run build` | `tsc -b` type check, then the production bundle | nothing |
| `npm run test:e2e` | **The FE_SPEC §8 suite**: 27 Playwright specs driving the real backend through the journey, the checkout failures the stub gateway can produce, reloads, the stream, two tabs, two sales, a skewed clock, back-off and a phone layout. Starts `npm run dev` itself | the backend on `:8080` with an **open sale** (`docker/scripts/dev-up.sh`) |
| `npm run typecheck:e2e` | type-checks the suite | nothing |

The suite uses only the API buyers and operators use — never SQL, never Redis, no test-only endpoints
(ADR-078). It finds an open sale through `GET /events`, makes a fresh buyer per test with a new browser
context, and pauses and resumes through the operator endpoints (admin / admin on the `dev` profile).
States no API can create — a sale ending mid-test, a hold expiring, a lost counter, a sell-out — are
covered by the backend's integration tests and this folder's unit tests. Each run buys a few tickets.
`E2E_API`, `E2E_ADMIN` and `E2E_BASE_URL` point it elsewhere.

Playwright writes `playwright-report/` and `test-results/`; both are ignored, and
`npm run clean:playwright` removes them before every run.
