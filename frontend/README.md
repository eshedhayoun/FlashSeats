# FlashSeats web client

The React client for the buyer journey in [`FE_SPEC.md`](../FE_SPEC.md): event page, waiting room,
seat selection, checkout (stub gateway or Stripe), receipt and PDF ticket. React 18, MUI 6, Vite.

Under `--profile cluster` the nginx image builds it and serves it at `:8080` (ADR-068), and
`docker/scripts/professor-demo.sh` starts that whole stack in one command. For development it runs on
its own dev server, below. The backend's minimal demo page, `src/main/resources/static/index.html`, is
what `./mvnw spring-boot:run` serves at `:8080`.

## Run it

```bash
# backend first: docker/scripts/dev-up.sh && ./mvnw spring-boot:run   (from the repo root)
cd frontend
npm install
cp .env.example .env.local     # leave VITE_STRIPE_PUBLISHABLE_KEY blank to drive the stub gateway
npm run dev                    # http://localhost:5173, proxies /api to http://localhost:8080
```

With the key blank, the checkout page offers the stub's magic payment methods, so success, decline,
gateway outage and 3-D Secure are all walkable with no account. A `pk_test_...` key drives real
Stripe, and the backend must then run with `STRIPE_ENABLED=true` and the matching secret key.

## Layout

One folder per view of the FE_SPEC state machine — `landing`, `queue`, `selection`, `checkout`,
`orders`, `terminal` — plus `sale` (the `/sale/{id}/state` bootstrap and the router that picks the
view), `clock` (server-offset time; every timer derives from it), `api` (typed client, RFC 7807
errors) and `shared`.

## Tests

| Command | What it checks | Needs |
| :--- | :--- | :--- |
| `npm test` | Vitest unit tests of the pure logic: view routing, per-event storage keys, checkout error mapping, the checkout timer, duration formatting | nothing |
| `npm run build` | `tsc -b` type check, then the production bundle | nothing |
| `npm run test:e2e` | Playwright, Chromium, one worker. Starts `npm run dev` itself | the backend on `:8080` with at least one `OPEN` event |

**The Playwright suite is a scaffold, not the FE_SPEC §8 suite.** Most specs load a page and inspect
storage; several assert on values the test itself constructs rather than on the UI, and none yet
drives queue → hold → checkout through the browser. Treat a green run as "pages load and storage is
namespaced per event", nothing more. The real journey specs are tracked as open work in
[`docs/06-mvp-overview.md`](../docs/06-mvp-overview.md) §11.

Playwright writes `playwright-report/` and `test-results/`; both are ignored, and
`npm run clean:playwright` removes them before every run.
