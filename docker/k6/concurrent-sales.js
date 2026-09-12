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

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE      = __ENV.BASE_URL  || 'http://nginx:80';
const FIRST_ID  = parseInt(__ENV.FIRST_ID || '9001', 10);
const EVENTS    = parseInt(__ENV.EVENTS   || '5', 10);
const VUS       = parseInt(__ENV.VUS      || '10000', 10);
const CAPACITY  = parseInt(__ENV.CAPACITY || '500', 10);

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
        { duration: '10s', target: VUS },   // all five spikes at once
        { duration: '3m',  target: VUS },
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
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

/**
 * Which sale this VU is in. Stable for the VU's whole life.
 *
 * A buyer belongs to ONE sale. Bouncing a VU between events every iteration
 * would exercise a thing no human does and would quietly hide the bug rule 5 of
 * FE_SPEC is about, where per-event state collides.
 */
function saleFor() {
  const index = (__VU - 1) % EVENTS;
  return { eventId: FIRST_ID + index, tierId: FIRST_ID + index };
}

/**
 * X-Forwarded-For IS NOT A HACK — see flash-sale.js. k6 is one container, so
 * without it every VU shares one IP bucket (capacity 300, refill 150/s) and the
 * run measures the rate limiter instead of the sale (ADR-047).
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
  const { eventId, tierId } = saleFor();
  const tag = { event: String(eventId) };

  // --- 1. Landing ----------------------------------------------------------
  const landing = http.get(`${BASE}/api/v1/events/${eventId}`, {
    headers: clientHeaders(),
    tags: { step: 'browse', ...tag },
  });
  if (landing.status === 429) { rateLimited.add(1, tag); sleep(2); return; }
  if (landing.status !== 200) { sleep(1); return; }
  if (tryJson(landing)?.windowStatus === 'CLOSED') return;

  // --- 2. Join -------------------------------------------------------------
  // 202 ACCEPTED. And no recaptchaToken: there is no challenge provider, the
  // field is not on JoinQueueRequest, and sending one trains a reader to think
  // the server takes it.
  const join = http.post(
    `${BASE}/api/v1/queue/join`,
    JSON.stringify({ eventId }),
    { headers: clientHeaders(), tags: { step: 'join', ...tag } },
  );
  joinSuccess.add(join.status === 202, tag);
  if (join.status === 429) { rateLimited.add(1, tag); sleep(2); return; }
  if (join.status >= 400) { sleep(1); return; }

  // --- 3. Wait for promotion ----------------------------------------------
  const waitStart = Date.now();
  const passToken = awaitPass(eventId, tag);
  queueWait.add((Date.now() - waitStart) / 1000, tag);

  if (passToken === 'EXHAUSTED') return;
  if (!passToken) { passTimeouts.add(1, tag); return; }

  // --- 4. Admit: spend the pass for an admission session -------------------
  const admit = http.post(
    `${BASE}/api/v1/queue/admit`,
    JSON.stringify({ eventId }),
    {
      headers: clientHeaders({ 'X-Queue-Pass-Token': passToken }),
      tags: { step: 'admit', ...tag },
    },
  );
  if (admit.status !== 200) { admitFailures.add(1, tag); return; }

  const admissionToken = tryJson(admit)?.admissionToken;
  if (!admissionToken) { admitFailures.add(1, tag); return; }

  // --- 5. Hold -------------------------------------------------------------
  const quantity = 1 + Math.floor(Math.random() * 2);
  const hold = http.post(
    `${BASE}/api/v1/holds`,
    JSON.stringify({ eventId, tierId, quantity }),
    {
      headers: clientHeaders({ 'X-Admission-Token': admissionToken }),
      tags: { step: 'hold', ...tag },
    },
  );

  if (hold.status === 409) { holdConflicts.add(1, tag); return; }
  if (hold.status === 503) { inventoryFaults.add(1, tag); return; }   // a bug
  if (hold.status !== 201) return;

  const holdToken = tryJson(hold)?.holdToken;
  if (!holdToken) return;

  sleep(2 + Math.random() * 8);

  // --- 6. Checkout ---------------------------------------------------------
  // Eight sequential database transactions behind this one call. That figure is
  // the reason ADR-049 re-derives the admission budget rather than reusing
  // ADR-028's, and this is where it is paid.
  const t0 = Date.now();
  const checkout = http.post(
    `${BASE}/api/v1/orders/checkout`,
    JSON.stringify({
      holdToken,
      userEmail: `vu${__VU}@loadtest.local`,
      paymentMethodId: 'pm_card_visa',
      idempotencyKey: `k6c-${__VU}-${__ITER}`,
    }),
    { headers: clientHeaders(), tags: { step: 'checkout', ...tag } },
  );
  checkoutTime.add(Date.now() - t0, tag);

  const ok = check(checkout, {
    'checkout confirmed': (r) => r.status === 201 || r.status === 200,
  });

  if (ok) {
    ordersConfirmed.add(1, tag);
    ticketsSold.add(quantity, tag);
  }
}

/** Polls /queue/status until a pass appears, the sale ends, or we give up. */
function awaitPass(eventId, tag) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE}/api/v1/queue/status?eventId=${eventId}`, {
      headers: clientHeaders(),
      tags: { step: 'queue_status', ...tag },
    });

    if (res.status === 429) { rateLimited.add(1, tag); sleep(2); continue; }

    if (res.status === 200) {
      const body = tryJson(res);
      // The field is `phase`, carrying QueuePhase values. Checking the token
      // rather than the phase also covers a read that raced the promotion tick.
      if (body?.passToken) return body.passToken;
      if (body?.phase === 'EXHAUSTED' || body?.phase === 'CLOSED') {
        soldOut.add(1, tag);
        return 'EXHAUSTED';
      }
    }
    sleep(1 + Math.random());
  }
  return null;
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
  const ceiling   = CAPACITY * EVENTS;

  const lines = [
    '',
    `  CONCURRENT SALES — ${EVENTS} events, ${FIRST_ID}..${FIRST_ID + EVENTS - 1}`,
    '  ' + '-'.repeat(58),
    `  tickets sold           ${sold} / ${ceiling} across all sales`,
    `  orders confirmed       ${confirmed}`,
    `  inventory 503s         ${faults}   (must be 0)`,
    `  rate limited           ${limited}`,
    `  checkout p99           ${p99.toFixed(0)} ms`,
    '',
    '  k6 cannot see the thing this drill is for. Read pool-pressure.sh output:',
    '  sustained hikaricp_connections_pending > 0 on any replica IS the ADR-049',
    '  finding, and it shows up as latency rather than as any failure here.',
    '',
  ];

  return { stdout: lines.join('\n') };
}
