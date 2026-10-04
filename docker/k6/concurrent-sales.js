// FlashSeats — five sales at once. The ADR-049 drill.
//
//   docker/seed/seed-concurrent.sh
//   docker/scripts/pool-pressure.sh 300 &
//   docker compose --profile loadtest run --rm -e VUS=2000 k6-concurrent
//
// The sibling of flash-sale.js, and deliberately the same journey. The ONLY
// difference is that each VU picks one of E sales and stays in it, which is what
// a real buyer does — nobody queues for five sales at once, but five sales can
// certainly be queued for at once.
//
// WHAT THIS MEASURES, and it is not what flash-sale.js measures.
//
// flash-sale.js asserts `no_oversell`, a CORRECTNESS property, and it passed at
// 2,000 VUs on one sale. This asserts the same thing per event — a regression
// there would matter enormously — but that is not why it exists.
//
// It exists because ADR-028 derives the promotion batch from the connection pool
// for ONE sale and never said so. PromotionWorker loops every open event and
// applies the cap PER EVENT; `queue:promote:{e}` is per event, so different
// replicas promote different sales in the same second:
//
//     R x E x batchSize = 3 x 5 x 45 = 675 admissions/sec into 90 connections
//
// And a checkout is EIGHT sequential transactions, not the ~1 that "capacity to
// serve" implicitly priced. So the expected failure is not an error — under
// virtual threads nothing errors — it is requests piling up on HikariCP while
// p99 collapses.
//
// k6 cannot see that. The number lives in `hikaricp_connections_pending`, per
// replica, and docker/scripts/pool-pressure.sh is what reads it. THIS SCRIPT IS
// HALF THE DRILL. Run it alone and you will get a green pass over the exact
// condition it was written to find.
//
// VUS IS BUYERS, NOT k6 VUs. Each k6 VU drives BUYERS_PER_VU buyers at once
// (default 10), each with its own cookie jar -- so its own fsid session -- and its
// own X-Forwarded-For, so the cluster sees exactly the traffic of VUS separate
// browsers. A k6 VU is a JavaScript runtime of its own, and ten thousand of them
// took 2.6 GiB before the run was two minutes old: the Docker VM's OOM killer
// ended the drill on a laptop before the cluster was the limit (ADR-079).
// BUYERS_PER_VU=1 is the old one-buyer-per-VU model.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { setTimeout } from 'k6/timers';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE      = __ENV.BASE_URL  || 'http://nginx:80';
// Unique per run. The stub ignores the key, but Stripe keeps one for 24 hours, and a key reused
// by the next run is answered with the previous run's response or an idempotency error.
const RUN_ID   = __ENV.RUN_ID || Date.now().toString(36);
const FIRST_ID  = parseInt(__ENV.FIRST_ID || '9001', 10);
const EVENTS    = parseInt(__ENV.EVENTS   || '5', 10);
const VUS       = parseInt(__ENV.VUS      || '10000', 10);   // buyers
const CAPACITY  = parseInt(__ENV.CAPACITY || '500', 10);
const BUYERS_PER_VU = Math.max(1, parseInt(__ENV.BUYERS_PER_VU || '10', 10));
// How often a waiting buyer asks for its place. The browser does not poll at all
// while its stream is up; it falls back to GET /queue/status every 5 s after
// three failed reconnects (FE_SPEC §4), so 5 s is the most any real client asks.
// This polled every 1-2 s: 6,667 requests a second at 10,000 buyers, more than a
// ten-core host can generate while also serving them (ADR-079). Lower it to
// stress the front door on a bigger host.
const POLL_SECONDS = parseFloat(__ENV.POLL_SECONDS || '5');
const K6_VUS    = Math.ceil(VUS / BUYERS_PER_VU);
// The ramp and the hold. A buyer starts no new journey after this; the ramp-down
// is for journeys already under way.
const LOAD_SECONDS = 10 + 180;

const ordersConfirmed = new Counter('orders_confirmed');
const ticketsSold     = new Counter('tickets_sold');
const soldOut         = new Counter('sold_out_responses');
const holdConflicts   = new Counter('hold_conflicts');
const inventoryFaults = new Counter('inventory_faults_503');   // must stay 0
const rateLimited     = new Counter('rate_limited_429');
const admitFailures   = new Counter('admit_failures');
const passTimeouts    = new Counter('pass_timeouts');
const joinSuccess     = new Rate('join_success_rate');
const queueWait       = new Trend('queue_wait_seconds');
const checkoutTime    = new Trend('checkout_duration_ms');

export const options = {
  scenarios: {
    concurrent_sales: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: K6_VUS },   // all five spikes at once
        { duration: '3m',  target: K6_VUS },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '60s',
    },
  },
  thresholds: {
    // Per event, because a global cap of E x CAPACITY would pass while one sale
    // oversold and another undersold by the same amount.
    ...Object.fromEntries(
      Array.from({ length: EVENTS }, (_, i) => [
        `tickets_sold{event:${FIRST_ID + i}}`,
        [{ threshold: `count <= ${CAPACITY}`, abortOnFail: true }],
      ]),
    ),
    'inventory_faults_503': ['count == 0'],
    'http_req_failed':      ['rate < 0.01'],
    // Deliberately NOT abortOnFail. p99 is the symptom this drill expects to
    // see move, and aborting on it would destroy the run that was meant to
    // measure how far it moves.
    'checkout_duration_ms': ['p(99) < 200'],
    'join_success_rate':    ['rate > 0.95'],
    // No limit: declared so the summary carries the status read on its own,
    // which is the path every waiting buyer polls (ADR-079).
    'http_req_duration{step:queue_status}': ['max >= 0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const pause = (seconds) => new Promise((resume) => setTimeout(resume, seconds * 1000));

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

/**
 * Which sale this buyer is in. Stable for the buyer's whole life.
 *
 * A buyer belongs to ONE sale. Bouncing between events every journey would
 * exercise a thing no human does and would quietly hide the bug rule 5 of
 * FE_SPEC is about, where per-event state collides.
 */
function saleFor(buyer) {
  const index = buyer % EVENTS;
  return { eventId: FIRST_ID + index, tierId: FIRST_ID + index };
}

/**
 * X-Forwarded-For IS NOT A HACK — see flash-sale.js. k6 is one container, so
 * without it every buyer shares one IP bucket (capacity 300, refill 150/s) and
 * the run measures the rate limiter instead of the sale (ADR-047).
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
 * One visit, start to finish, as a new session: a fresh cookie jar is what a new
 * k6 iteration used to give a buyer, and what a browser that lost its cookies has.
 */
async function journey(buyer, visit, end) {
  const { eventId, tierId } = saleFor(buyer);
  const tag = { event: String(eventId) };
  const jar = new http.CookieJar();
  const send = (method, path, body, step, extra) => http.asyncRequest(
    method,
    `${BASE}${path}`,
    body === null ? null : JSON.stringify(body),
    { headers: clientHeaders(buyer, extra), jar, tags: { step, ...tag } },
  );

  // --- 1. Landing ----------------------------------------------------------
  const landing = await send('GET', `/api/v1/events/${eventId}`, null, 'browse');
  if (landing.status === 429) { rateLimited.add(1, tag); await pause(2); return; }
  if (landing.status !== 200) { await pause(1); return; }
  if (tryJson(landing)?.windowStatus === 'CLOSED') { await pause(30); return; }

  // --- 2. Join -------------------------------------------------------------
  // 202 ACCEPTED. And no recaptchaToken: there is no challenge provider, and
  // without one the server falls back to rate limits (ADR-055).
  const join = await send('POST', '/api/v1/queue/join', { eventId }, 'join');
  joinSuccess.add(join.status === 202, tag);
  if (join.status === 429) { rateLimited.add(1, tag); await pause(2); return; }
  if (join.status >= 400) { await pause(1); return; }

  // --- 3. Wait for promotion ----------------------------------------------
  const waitStart = Date.now();
  const passToken = await awaitPass(eventId, tag, send, end);

  if (passToken === 'ENDED') return;   // the run is over, not the wait
  if (passToken === 'CLOSED') { await pause(30); return; }
  if (passToken === 'EXHAUSTED') return;   // waited out the deadline in a sold-out sale
  if (!passToken) { passTimeouts.add(1, tag); return; }
  queueWait.add((Date.now() - waitStart) / 1000, tag);

  // --- 4. Admit: spend the pass for an admission session -------------------
  const admit = await send('POST', '/api/v1/queue/admit', { eventId }, 'admit',
    { 'X-Queue-Pass-Token': passToken });
  if (admit.status !== 200) { admitFailures.add(1, tag); return; }

  const admissionToken = tryJson(admit)?.admissionToken;
  if (!admissionToken) { admitFailures.add(1, tag); return; }

  // --- 5. Hold -------------------------------------------------------------
  let quantity = 1 + Math.floor(Math.random() * 2);
  const holdFor = (seats) => send('POST', '/api/v1/holds', { eventId, tierId, quantity: seats }, 'hold',
    { 'X-Admission-Token': admissionToken });
  let hold = await holdFor(quantity);
  if (hold.status === 409 && quantity > 1) {
    // "There aren't 2 seats left in this tier. Try fewer seats" is what the
    // client tells this buyer (ADR-077), and a buyer takes the one. Walking away
    // instead left the last seat of a sale unsold while their admission ran out.
    quantity = 1;
    hold = await holdFor(quantity);
  }

  if (hold.status === 409) { holdConflicts.add(1, tag); return; }
  if (hold.status === 503) { inventoryFaults.add(1, tag); return; }   // a bug
  if (hold.status !== 201) return;

  const holdToken = tryJson(hold)?.holdToken;
  if (!holdToken) return;

  await pause(2 + Math.random() * 8);

  // --- 6. Checkout ---------------------------------------------------------
  // Eight sequential database transactions behind this one call. That figure is
  // the reason ADR-049 re-derives the admission budget rather than reusing
  // ADR-028's, and this is where it is paid.
  const t0 = Date.now();
  const checkout = await send('POST', '/api/v1/orders/checkout', {
    holdToken,
    userEmail: `buyer${buyer}@loadtest.local`,
    paymentMethodId: 'pm_card_visa',
    idempotencyKey: `k6c-${RUN_ID}-${buyer}-${visit}`,
  }, 'checkout');
  checkoutTime.add(Date.now() - t0, tag);

  const ok = check(checkout, {
    'checkout confirmed': (r) => r.status === 201 || r.status === 200,
  });

  if (ok) {
    ordersConfirmed.add(1, tag);
    ticketsSold.add(quantity, tag);
  }
}

/**
 * Polls /queue/status until a pass appears, the sale closes, or we give up.
 *
 * A sold-out sale does NOT end the wait. EXHAUSTED is derived: it clears the
 * moment a hold is released or expires, and the buyer keeps their place
 * (ADR-035), so the browser stays in line and so does this. Returning on it
 * started the next journey at once -- landing, join and status with no think
 * time, in a loop, for the rest of the run: 240,000 iterations at 300 VUs, and at
 * 10,000 a measurement of how fast the cluster can say "sold out" (ADR-079).
 */
async function awaitPass(eventId, tag, send, end) {
  const deadline = Date.now() + 180_000;
  let toldSoldOut = false;
  while (Date.now() < deadline) {
    // Inside the ramp-down, so the iteration ends rather than being cut off.
    if (Date.now() > end + 15_000) return 'ENDED';
    const res = await send('GET', `/api/v1/queue/status?eventId=${eventId}`, null, 'queue_status');

    if (res.status === 429) { rateLimited.add(1, tag); await pause(2); continue; }

    if (res.status === 200) {
      const body = tryJson(res);
      // The field is `phase`, carrying QueuePhase values. Checking the token
      // rather than the phase also covers a read that raced the promotion tick.
      if (body?.passToken) return body.passToken;
      if (body?.phase === 'CLOSED') return 'CLOSED';
      if (body?.phase === 'EXHAUSTED' && !toldSoldOut) {
        soldOut.add(1, tag);
        toldSoldOut = true;
      }
    }
    await pause(POLL_SECONDS * (0.8 + 0.4 * Math.random()));   // jittered: no lockstep
  }
  return toldSoldOut ? 'EXHAUSTED' : null;
}

function tryJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

export function handleSummary(data) {
  const sold      = data.metrics.tickets_sold?.values?.count ?? 0;
  const confirmed = data.metrics.orders_confirmed?.values?.count ?? 0;
  const faults    = data.metrics.inventory_faults_503?.values?.count ?? 0;
  const limited   = data.metrics.rate_limited_429?.values?.count ?? 0;
  const p99       = data.metrics.checkout_duration_ms?.values?.['p(99)'] ?? 0;
  const statusP99 = data.metrics['http_req_duration{step:queue_status}']?.values?.['p(99)'] ?? 0;
  const timeouts  = data.metrics.pass_timeouts?.values?.count ?? 0;
  const ceiling   = CAPACITY * EVENTS;


  const lines = [
    '',
    `  CONCURRENT SALES — ${EVENTS} events, ${FIRST_ID}..${FIRST_ID + EVENTS - 1}, ${VUS} buyers on ${K6_VUS} k6 VUs`,
    '  ' + '-'.repeat(58),
    `  tickets this CLIENT saw confirmed   ${sold} / ${ceiling}   <-- A FLOOR, NOT THE SALE`,
    `  orders this client saw confirmed    ${confirmed}`,
    `  inventory 503s                      ${faults}   (must be 0)`,
    `  rate limited                        ${limited}`,
    `  checkout p99                        ${p99.toFixed(0)} ms`,
    `  queue status p99                    ${statusP99.toFixed(0)} ms`,
    `  gave up waiting for a pass          ${timeouts}`,
    '',
    '  *** THE TWO NUMBERS ABOVE UNDER-REPORT. Read the ledger, not this: ***',
    '',
    '      docker/scripts/sold-count.sh',
    '',
    '  They count checkout responses that ARRIVED. k6 times out a request at 60 s',
    '  by default and the scenario ends with a graceful ramp-down, so every request',
    '  still in flight when either fires is scored as a failure and counted as',
    '  nothing -- while the server went on to commit the order. In the Pass 8 run',
    '  that gap was 8x: this summary said 21 tickets, the ledger held 162.',
    '',
    '  And k6 cannot see the thing this drill is for at all. Read pool-pressure.sh:',
    '  sustained hikaricp_connections_pending > 0 on any replica IS the ADR-049',
    '  finding, and it shows up as latency rather than as any failure here.',
    '',
  ];

  // The whole result too, for whoever needs more than these lines. Gitignored.
  return {
    stdout: lines.join('\n'),
    '/scripts/summary-concurrent.json': JSON.stringify(data, null, 2),
  };
}
