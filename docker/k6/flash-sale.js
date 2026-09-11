// FlashSeats — flash-sale load harness
//
//   docker compose --profile cluster  up -d
//   docker compose --profile loadtest run --rm k6
//
// Drives the full journey:
//
//   browse -> join queue -> wait for a pass -> ADMIT -> hold -> checkout
//
// The pass is collected by polling GET /api/v1/queue/status rather than over
// SSE. That is not a shortcut around the design — the polling fallback is a
// specified part of it (ADR-007), precisely so a promotion is never lost to a
// dead socket. Exercising it here keeps that path honest. Fan-out over SSE is
// proven separately and deterministically by docker/scripts/fanout-check.sh.
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
const rateLimited     = new Counter('rate_limited_429');       // see clientHeaders()
const admitFailures   = new Counter('admit_failures');
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
    // The Phase 4 exit criterion is p99 < 200 ms (04-implementation-roadmap.md).
    // This used to read 2000, which would have passed a run an order of
    // magnitude outside the number the roadmap actually commits to.
    'checkout_duration_ms':  ['p(99) < 200'],
    'join_success_rate':     ['rate > 0.95'],
  },
  // Each VU is a distinct buyer with its own fsid cookie. That is k6's default
  // — every VU gets its own cookie jar — and there is no `cookies` option to
  // ask for it. Declaring one made k6 warn "unknown field" on every run, which
  // is the kind of noise that trains people to ignore the warning line.
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

/**
 * Headers every request carries.
 *
 * X-Forwarded-For IS NOT A HACK — without it this harness measures the rate
 * limiter instead of the sale (ADR-047). k6 runs as ONE container, so all ten
 * thousand VUs share one source address and therefore one IP bucket: capacity
 * 300, refill 150/s, against ~10,000 requests per second. The run would be
 * almost entirely 429s and would tell us nothing about overbooking.
 *
 * Ten thousand real buyers arrive from thousands of addresses, so giving each
 * VU its own is the faithful simulation, not a bypass. It works because nginx
 * sets $proxy_add_x_forwarded_for, which APPENDS its peer to whatever arrived,
 * and RateLimitFilter takes split(",")[0] — the entry we set here. The session
 * bucket (the primary control, ADR-011) is untouched and still applies per VU.
 *
 * Do not delete this to "make the test more realistic". It is what makes it so.
 */
function clientHeaders(extra) {
  const vu = __VU;
  return Object.assign(
    {
      'Content-Type': 'application/json',
      'X-Forwarded-For': `10.${(vu >> 16) & 255}.${(vu >> 8) & 255}.${vu & 255}`,
    },
    extra || {},
  );
}

export default function () {
  // --- 1. Landing page -----------------------------------------------------
  // Also the request that mints this VU's signed fsid cookie.
  const landing = http.get(`${BASE}/api/v1/events/${EVENT_ID}`, {
    headers: clientHeaders(),
    tags: { step: 'browse' },
  });
  if (landing.status === 429) { rateLimited.add(1); sleep(2); return; }
  if (landing.status !== 200) { sleep(1); return; }

  const windowStatus = tryJson(landing)?.windowStatus;
  if (windowStatus === 'CLOSED') return;

  // --- 2. Join the queue ---------------------------------------------------
  // 202 ACCEPTED, not 200/201 — the join is idempotent and returns the caller's
  // standing, not a created resource. Scoring it as 200||201 made
  // join_success_rate read 0 on a perfectly healthy run and failed its own
  // threshold unconditionally.
  const join = http.post(
    `${BASE}/api/v1/queue/join`,
    JSON.stringify({ eventId: Number(EVENT_ID), recaptchaToken: 'loadtest' }),
    { headers: clientHeaders(), tags: { step: 'join' } },
  );
  joinSuccess.add(join.status === 202);
  if (join.status === 429) { rateLimited.add(1); sleep(2); return; }
  if (join.status >= 400) { sleep(1); return; }

  // --- 3. Wait for promotion ----------------------------------------------
  const waitStart = Date.now();
  const passToken = awaitPass();
  queueWait.add((Date.now() - waitStart) / 1000);

  if (passToken === 'EXHAUSTED') return;   // sold out — a correct, fast outcome
  if (!passToken) { passTimeouts.add(1); return; }

  // --- 4. Admit: exchange the pass for an admission session ----------------
  // THE STEP THIS HARNESS USED TO SKIP. It sent the pass straight to /holds as
  // X-Queue-Pass-Token, and /holds reads X-Admission-Token — so every hold
  // failed and no run ever reached checkout.
  //
  // The exchange is not ceremony. It is where the pass is REVOKED, which is
  // what makes it single-use: before ADR-020 nothing ever spent it and one
  // promoted session could mint unlimited holds. The admission that comes back
  // outlives any single hold, so a buyer can release seats and pick another
  // tier without rejoining the queue.
  const admit = http.post(
    `${BASE}/api/v1/queue/admit`,
    JSON.stringify({ eventId: Number(EVENT_ID) }),
    {
      headers: clientHeaders({ 'X-Queue-Pass-Token': passToken }),
      tags: { step: 'admit' },
    },
  );
  if (admit.status !== 200) { admitFailures.add(1); return; }

  const admissionToken = tryJson(admit)?.admissionToken;
  if (!admissionToken) { admitFailures.add(1); return; }

  // --- 5. Hold -------------------------------------------------------------
  const quantity = 1 + Math.floor(Math.random() * 2);   // 1-2 tickets
  const hold = http.post(
    `${BASE}/api/v1/holds`,
    JSON.stringify({ eventId: Number(EVENT_ID), tierId: Number(TIER_ID), quantity }),
    {
      headers: clientHeaders({ 'X-Admission-Token': admissionToken }),
      tags: { step: 'hold' },
    },
  );

  if (hold.status === 409) { holdConflicts.add(1); return; }        // genuinely sold out
  if (hold.status === 503) { inventoryFaults.add(1); return; }      // counter missing — a bug
  if (hold.status !== 201) return;

  const holdToken = tryJson(hold)?.holdToken;
  if (!holdToken) return;

  // Buyers do not check out instantly.
  sleep(2 + Math.random() * 8);

  // --- 6. Checkout ---------------------------------------------------------
  const t0 = Date.now();
  const checkout = http.post(
    `${BASE}/api/v1/orders/checkout`,
    JSON.stringify({
      holdToken,
      userEmail: `vu${__VU}@loadtest.local`,
      paymentMethodId: 'pm_card_visa',
      idempotencyKey: `k6-${__VU}-${__ITER}`,
    }),
    { headers: clientHeaders(), tags: { step: 'checkout' } },
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

/**
 * Polls /queue/status until a pass appears, the sale ends, or we give up.
 *
 * The field is `phase`, carrying QueuePhase values — NOT_JOINED, WAITING,
 * PROMOTED, ADMITTED, EXHAUSTED, CLOSED. This read `body.state`, which is not a
 * field QueueStatusResponse has ever had, so the terminal phases were never
 * recognised: a VU in a sold-out sale sat here for the full 180 s and was
 * recorded as a pass timeout. That inflated queue_wait_seconds, buried the real
 * timeouts, and held ten thousand VUs open long past the point they were done.
 */
function awaitPass() {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE}/api/v1/queue/status?eventId=${EVENT_ID}`, {
      headers: clientHeaders(),
      tags: { step: 'queue_status' },
    });

    if (res.status === 429) {
      rateLimited.add(1);
      sleep(2);
      continue;
    }

    if (res.status === 200) {
      const body = tryJson(res);

      // PROMOTED carries the pass. Checking the token rather than the phase
      // also covers a status read that raced the promotion tick.
      if (body?.passToken) return body.passToken;

      if (body?.phase === 'EXHAUSTED' || body?.phase === 'CLOSED') {
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
  const count     = (name) => data.metrics[name]?.values?.count ?? 0;
  const sold      = count('tickets_sold');
  const orders    = count('orders_confirmed');
  const faults    = count('inventory_faults_503');
  const conflicts = count('hold_conflicts');
  const throttled = count('rate_limited_429');
  const admitFail = count('admit_failures');
  const timeouts  = count('pass_timeouts');
  const p99       = data.metrics.checkout_duration_ms?.values?.['p(99)'] ?? 0;
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
  Checkout p99      ${p99.toFixed(0)} ms   ${p99 < 200 ? '' : '<-- over the 200 ms exit criterion'}
----------------------------------------------------------
  Admit failures    ${admitFail}
  Pass timeouts     ${timeouts}
  Rate limited      ${throttled}${throttled > 0 ? '   <-- see clientHeaders(): is X-Forwarded-For reaching the app?' : ''}
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
