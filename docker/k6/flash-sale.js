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
//
// VUS IS BUYERS, NOT k6 VUs: each k6 VU drives BUYERS_PER_VU of them (default
// 10), each with its own cookie jar and X-Forwarded-For. Ten thousand k6 VUs took
// 2.6 GiB and the Docker VM's OOM killer ended the run (ADR-079). The same model
// as concurrent-sales.js, which is this journey across several sales.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { setTimeout } from 'k6/timers';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE     = __ENV.BASE_URL  || 'http://nginx:80';
// Unique per run. The stub ignores the key, but Stripe keeps one for 24 hours, and a key reused
// by the next run is answered with the previous run's response or an idempotency error.
const RUN_ID   = __ENV.RUN_ID || Date.now().toString(36);
const EVENT_ID = __ENV.EVENT_ID  || '1';
const TIER_ID  = __ENV.TIER_ID   || '1';
const VUS      = parseInt(__ENV.VUS      || '10000', 10);   // buyers
const CAPACITY = parseInt(__ENV.CAPACITY || '500', 10);
const BUYERS_PER_VU = Math.max(1, parseInt(__ENV.BUYERS_PER_VU || '10', 10));
const K6_VUS   = Math.ceil(VUS / BUYERS_PER_VU);
// The client's own fallback cadence (FE_SPEC §4); see concurrent-sales.js.
const POLL_SECONDS = parseFloat(__ENV.POLL_SECONDS || '5');
// The ramp and the hold: no buyer starts a new journey after this.
const LOAD_SECONDS = 10 + 180;

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
        { duration: '10s', target: K6_VUS },   // the spike: everyone at once
        { duration: '3m',  target: K6_VUS },   // sustained drain
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
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const pause = (seconds) => new Promise((resume) => setTimeout(resume, seconds * 1000));

/**
 * Headers every request carries.
 *
 * X-Forwarded-For IS NOT A HACK — without it this harness measures the rate
 * limiter instead of the sale (ADR-047). k6 runs as ONE container, so every
 * buyer would share one source address and therefore one IP bucket: capacity
 * 300, refill 150/s. Ten thousand real buyers arrive from thousands of
 * addresses, so giving each its own is the faithful simulation, not a bypass.
 *
 * It reaches the rate limiter because nginx APPENDS the address it saw, and
 * Tomcat's RemoteIpValve (`native` forward headers, ADR-071) reads right to left
 * past private-network hops — k6's container is one — to the entry set here. A
 * client on the internet cannot do the same: its own public address is the
 * first hop the valve stops at.
 *
 * Do not delete this to "make the test more realistic". It is what makes it so.
 */
function clientHeaders(buyer, extra) {
  const n = buyer + 1;
  return Object.assign(
    {
      'Content-Type': 'application/json',
      'X-Forwarded-For': `10.${(n >> 16) & 255}.${(n >> 8) & 255}.${n & 255}`,
    },
    extra || {},
  );
}

/**
 * One iteration per VU: its buyers go round their journeys until the load phase
 * ends. `sleep()` would stop every buyer on the VU, so the waits are timers.
 */
export default async function () {
  if (exec.vu.iterationInScenario > 0) {
    sleep(3600);   // parked until the ramp-down: this VU's buyers are done
    return;
  }
  const first = (__VU - 1) * BUYERS_PER_VU;
  const count = Math.min(BUYERS_PER_VU, VUS - first);
  const end = exec.scenario.startTime + LOAD_SECONDS * 1000;
  const buyers = Array.from({ length: Math.max(count, 0) }, (_, i) => first + i);
  await Promise.all(buyers.map(async (buyer) => {
    for (let visit = 0; Date.now() < end; visit++) {
      await journey(buyer, visit, end);
    }
  }));
}

/** One visit as a new session: a fresh cookie jar is a fresh fsid cookie. */
async function journey(buyer, visit, end) {
  const jar = new http.CookieJar();
  const send = (method, path, body, step, extra) => http.asyncRequest(
    method,
    `${BASE}${path}`,
    body === null ? null : JSON.stringify(body),
    { headers: clientHeaders(buyer, extra), jar, tags: { step } },
  );

  // --- 1. Landing page -----------------------------------------------------
  // Also the request that mints this buyer's signed fsid cookie.
  const landing = await send('GET', `/api/v1/events/${EVENT_ID}`, null, 'browse');
  if (landing.status === 429) { rateLimited.add(1); await pause(2); return; }
  if (landing.status !== 200) { await pause(1); return; }

  const windowStatus = tryJson(landing)?.windowStatus;
  if (windowStatus === 'CLOSED') { await pause(30); return; }

  // --- 2. Join the queue ---------------------------------------------------
  // 202 ACCEPTED, not 200/201 — the join is idempotent and returns the caller's
  // standing, not a created resource. Scoring it as 200||201 made
  // join_success_rate read 0 on a perfectly healthy run and failed its own
  // threshold unconditionally.
  const join = await send('POST', '/api/v1/queue/join', { eventId: Number(EVENT_ID) }, 'join');
  joinSuccess.add(join.status === 202);
  if (join.status === 429) { rateLimited.add(1); await pause(2); return; }
  if (join.status >= 400) { await pause(1); return; }

  // --- 3. Wait for promotion ----------------------------------------------
  const waitStart = Date.now();
  const passToken = await awaitPass(send, end);

  if (passToken === 'ENDED') return;   // the run is over, not the wait
  if (passToken === 'CLOSED') { await pause(30); return; }
  if (passToken === 'EXHAUSTED') return;   // waited out the deadline in a sold-out sale
  if (!passToken) { passTimeouts.add(1); return; }
  queueWait.add((Date.now() - waitStart) / 1000);

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
  const admit = await send('POST', '/api/v1/queue/admit', { eventId: Number(EVENT_ID) }, 'admit',
    { 'X-Queue-Pass-Token': passToken });
  if (admit.status !== 200) { admitFailures.add(1); return; }

  const admissionToken = tryJson(admit)?.admissionToken;
  if (!admissionToken) { admitFailures.add(1); return; }

  // --- 5. Hold -------------------------------------------------------------
  let quantity = 1 + Math.floor(Math.random() * 2);   // 1-2 tickets
  const holdFor = (seats) => send('POST', '/api/v1/holds',
    { eventId: Number(EVENT_ID), tierId: Number(TIER_ID), quantity: seats }, 'hold',
    { 'X-Admission-Token': admissionToken });
  let hold = await holdFor(quantity);
  if (hold.status === 409 && quantity > 1) {
    // "Try fewer seats" is what the client tells this buyer (ADR-077).
    quantity = 1;
    hold = await holdFor(quantity);
  }

  if (hold.status === 409) { holdConflicts.add(1); return; }        // genuinely sold out
  if (hold.status === 503) { inventoryFaults.add(1); return; }      // counter missing — a bug
  if (hold.status !== 201) return;

  const holdToken = tryJson(hold)?.holdToken;
  if (!holdToken) return;

  // Buyers do not check out instantly.
  await pause(2 + Math.random() * 8);

  // --- 6. Checkout ---------------------------------------------------------
  const t0 = Date.now();
  const checkout = await send('POST', '/api/v1/orders/checkout', {
    holdToken,
    userEmail: `buyer${buyer}@loadtest.local`,
    paymentMethodId: 'pm_card_visa',
    idempotencyKey: `k6-${RUN_ID}-${buyer}-${visit}`,
  }, 'checkout');
  checkoutTime.add(Date.now() - t0);

  const ok = check(checkout, {
    'checkout confirmed': (r) => r.status === 201 || r.status === 200,
  });

  if (ok) {
    ordersConfirmed.add(1);
    ticketsSold.add(quantity);
  }
  // A 410 or 409 here is a hold that expired or was already used: legitimate
  // under contention, and not counted as sold.
}

/**
 * Polls /queue/status until a pass appears, the sale closes, or we give up.
 *
 * The field is `phase`, carrying QueuePhase values — NOT_JOINED, WAITING,
 * PROMOTED, ADMITTED, EXHAUSTED, CLOSED. This once read `body.state`, which is
 * not a field QueueStatusResponse has ever had, so no terminal phase was ever
 * recognised.
 *
 * A sold-out sale does NOT end the wait. EXHAUSTED is derived: it clears the
 * moment a hold is released or expires, and the buyer keeps their place
 * (ADR-035), so the browser stays in line and so does this. Returning on it
 * started the next journey at once -- landing, join and status with no think
 * time, in a loop, for the rest of the run (ADR-079).
 */
async function awaitPass(send, end) {
  const deadline = Date.now() + 180_000;
  let toldSoldOut = false;
  while (Date.now() < deadline) {
    // Inside the ramp-down, so the iteration ends rather than being cut off.
    if (Date.now() > end + 15_000) return 'ENDED';
    const res = await send('GET', `/api/v1/queue/status?eventId=${EVENT_ID}`, null, 'queue_status');

    if (res.status === 429) {
      rateLimited.add(1);
      await pause(2);
      continue;
    }

    if (res.status === 200) {
      const body = tryJson(res);

      // PROMOTED carries the pass. Checking the token rather than the phase
      // also covers a status read that raced the promotion tick.
      if (body?.passToken) return body.passToken;

      if (body?.phase === 'CLOSED') return 'CLOSED';
      if (body?.phase === 'EXHAUSTED' && !toldSoldOut) {
        soldOut.add(1);
        toldSoldOut = true;
      }
    }
    await pause(POLL_SECONDS * (0.8 + 0.4 * Math.random()));   // jittered: no lockstep
  }
  return toldSoldOut ? 'EXHAUSTED' : null;
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
