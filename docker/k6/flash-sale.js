// FlashSeats — flash-sale load harness
//
//   docker compose --profile cluster  up -d
//   docker compose --profile loadtest run --rm k6
//
// Drives the full journey: join queue -> wait for a pass -> hold -> checkout.
//
// The pass is collected by polling GET /api/v1/queue/status rather than over
// SSE. That is not a shortcut around the design — the polling fallback is a
// specified part of it (ADR-007), precisely so a promotion is never lost to a
// dead socket. Exercising it here keeps that path honest.
//
// THE POINT OF THIS TEST is the `no_oversell` threshold. Everything else is
// diagnostics.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE     = __ENV.BASE_URL  || 'http://nginx:80';
const EVENT_ID = __ENV.EVENT_ID  || '1';
const TIER_ID  = __ENV.TIER_ID   || '1';
const VUS      = parseInt(__ENV.VUS      || '10000', 10);
const CAPACITY = parseInt(__ENV.CAPACITY || '500', 10);

const ordersConfirmed = new Counter('orders_confirmed');
const ticketsSold     = new Counter('tickets_sold');
const soldOut         = new Counter('sold_out_responses');
const holdConflicts   = new Counter('hold_conflicts');
const inventoryFaults = new Counter('inventory_faults_503');   // must stay 0
const passTimeouts    = new Counter('pass_timeouts');
const joinSuccess     = new Rate('join_success_rate');
const queueWait       = new Trend('queue_wait_seconds');
const checkoutTime    = new Trend('checkout_duration_ms');

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS },   // the spike: everyone at once
        { duration: '3m',  target: VUS },   // sustained drain
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '60s',
    },
  },
  thresholds: {
    // The only assertion that actually matters.
    'tickets_sold':          [{ threshold: `count <= ${CAPACITY}`, abortOnFail: true }],
    // A 503 from the reserve path means a stock counter went missing (ADR-004).
    'inventory_faults_503':  ['count == 0'],
    'http_req_failed':       ['rate < 0.01'],
    'checkout_duration_ms':  ['p(99) < 2000'],
    'join_success_rate':     ['rate > 0.95'],
  },
  // Each VU is a distinct buyer with its own fsid cookie.
  cookies: { enabled: true },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export default function () {
  // --- 1. Landing page -----------------------------------------------------
  // Also the request that mints this VU's signed fsid cookie.
  const landing = http.get(`${BASE}/api/v1/events/${EVENT_ID}`, { tags: { step: 'browse' } });
  if (landing.status !== 200) { sleep(1); return; }

  const windowStatus = tryJson(landing)?.windowStatus;
  if (windowStatus === 'CLOSED') return;

  // --- 2. Join the queue ---------------------------------------------------
  const join = http.post(
    `${BASE}/api/v1/queue/join`,
    JSON.stringify({ eventId: Number(EVENT_ID), recaptchaToken: 'loadtest' }),
    { headers: JSON_HEADERS, tags: { step: 'join' } },
  );
  joinSuccess.add(join.status === 200 || join.status === 201);
  if (join.status >= 400) { sleep(1); return; }

  // --- 3. Wait for promotion ----------------------------------------------
  const waitStart = Date.now();
  const passToken = awaitPass();
  queueWait.add((Date.now() - waitStart) / 1000);

  if (passToken === 'EXHAUSTED') return;   // sold out — a correct, fast outcome
  if (!passToken) { passTimeouts.add(1); return; }

  // --- 4. Hold -------------------------------------------------------------
  const quantity = 1 + Math.floor(Math.random() * 2);   // 1-2 tickets
  const hold = http.post(
    `${BASE}/api/v1/holds`,
    JSON.stringify({ eventId: Number(EVENT_ID), tierId: Number(TIER_ID), quantity }),
    { headers: { ...JSON_HEADERS, 'X-Queue-Pass-Token': passToken }, tags: { step: 'hold' } },
  );

  if (hold.status === 409) { holdConflicts.add(1); return; }        // genuinely sold out
  if (hold.status === 503) { inventoryFaults.add(1); return; }      // counter missing — a bug
  if (hold.status !== 201) return;

  const holdToken = tryJson(hold)?.holdToken;
  if (!holdToken) return;

  // Buyers do not check out instantly.
  sleep(2 + Math.random() * 8);

  // --- 5. Checkout ---------------------------------------------------------
  const t0 = Date.now();
  const checkout = http.post(
    `${BASE}/api/v1/orders/checkout`,
    JSON.stringify({
      holdToken,
      userEmail: `vu${__VU}@loadtest.local`,
      paymentMethodId: 'pm_card_visa',
      idempotencyKey: `k6-${__VU}-${__ITER}`,
    }),
    { headers: JSON_HEADERS, tags: { step: 'checkout' } },
  );
  checkoutTime.add(Date.now() - t0);

  const ok = check(checkout, {
    'checkout confirmed': (r) => r.status === 201 || r.status === 200,
  });

  if (ok) {
    ordersConfirmed.add(1);
    ticketsSold.add(quantity);
  } else if (checkout.status === 410 || checkout.status === 409) {
    // Hold expired or already used. Legitimate under contention.
  }
}

// Polls /queue/status until a pass appears, the sale is exhausted, or we give up.
function awaitPass() {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE}/api/v1/queue/status?eventId=${EVENT_ID}`, {
      tags: { step: 'queue_status' },
    });

    if (res.status === 200) {
      const body = tryJson(res);
      if (body?.passToken) return body.passToken;
      if (body?.state === 'EXHAUSTED' || body?.state === 'CLOSED') {
        soldOut.add(1);
        return 'EXHAUSTED';
      }
    }
    sleep(1 + Math.random());   // jitter, so 10k VUs do not poll in lockstep
  }
  return null;
}

function tryJson(res) {
  try { return res.json(); } catch { return null; }
}

export function handleSummary(data) {
  const sold      = data.metrics.tickets_sold?.values?.count       ?? 0;
  const orders    = data.metrics.orders_confirmed?.values?.count   ?? 0;
  const faults    = data.metrics.inventory_faults_503?.values?.count ?? 0;
  const conflicts = data.metrics.hold_conflicts?.values?.count     ?? 0;
  const oversold  = sold > CAPACITY;

  const report = `
==========================================================
  FlashSeats load test
==========================================================
  Capacity          ${CAPACITY}
  Tickets sold      ${sold}
  Orders confirmed  ${orders}
  Sold-out (409)    ${conflicts}
  Inventory 503s    ${faults}   ${faults === 0 ? '' : '<-- ADR-004 violation'}
----------------------------------------------------------
  ${oversold
      ? `FAIL  OVERSOLD by ${sold - CAPACITY}`
      : sold === CAPACITY
        ? 'PASS  exactly at capacity, zero overbooking'
        : `PASS  no overbooking (${CAPACITY - sold} unsold)`}
==========================================================
`;

  return {
    stdout: report,
    '/scripts/summary.json': JSON.stringify(data, null, 2),
  };
}
