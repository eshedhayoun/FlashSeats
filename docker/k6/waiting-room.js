// The waiting room under a simultaneous arrival: one journey per VU — browse, join,
// poll /queue/status until promoted, sold out or out of time — then stop.
//
// WHAT IT MEASURES: whether the front door holds. Join success, status latency and
// every 4xx/5xx/transport failure, at VU counts the full journey cannot reach on
// one host (the sale drills spend their CPU on checkout).
//
// WHAT IT DOES NOT: sale throughput. Nobody redeems a pass, so promotion stops
// once unredeemed passes fill the oversubscription allowance — remaining seats x
// flashseats.queue.oversubscribe-factor — and resumes only as passes expire
// (120 s). `waiting_promoted` is bounded by that, not by how fast the queue moves.
// Holds and checkout are what flash-sale.js and concurrent-sales.js are for.
//
//   docker/seed/seed.sh
//   docker compose --profile loadtest run --rm -e VUS=2000 k6-waiting-room
//
// Like every drill on a shared host, k6 competes with the replicas for CPU, and the
// replicas with Redis and its sentinels. A run that shows Redis failing over, or
// nginx timing out connecting upstream, measured the host — not the waiting room.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx:80';
const EVENT_ID = parseInt(__ENV.EVENT_ID || '9001', 10);
const VUS = parseInt(__ENV.VUS || '10000', 10);
const WAIT_SECONDS = parseInt(__ENV.WAIT_SECONDS || '180', 10);
const POLL_SECONDS = parseFloat(__ENV.POLL_SECONDS || '2');

const joinSuccess = new Rate('waiting_join_success');
const joinRateLimited = new Counter('waiting_join_rate_limited');
const statusRateLimited = new Counter('waiting_status_rate_limited');
const statusFailures = new Counter('waiting_status_failures');
const browse4xx = new Counter('waiting_browse_4xx');
const browse5xx = new Counter('waiting_browse_5xx');
const browseTransport = new Counter('waiting_browse_transport_errors');
const join4xx = new Counter('waiting_join_4xx');
const join5xx = new Counter('waiting_join_5xx');
const joinTransport = new Counter('waiting_join_transport_errors');
const status4xx = new Counter('waiting_status_4xx');
const status5xx = new Counter('waiting_status_5xx');
const statusTransport = new Counter('waiting_status_transport_errors');
const promoted = new Counter('waiting_promoted');
const exhausted = new Counter('waiting_exhausted');
const timedOut = new Counter('waiting_timeouts');
const queueWait = new Trend('waiting_queue_seconds');
const statusLatency = new Trend('waiting_status_duration_ms');

export const options = {
  scenarios: {
    waiting_room: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS },
        { duration: `${WAIT_SECONDS}s`, target: VUS },
        { duration: '10s', target: 0 },
      ],
      // A VU's observation deadline starts when its journey begins. Keep the
      // load phase alive for the whole observation window, then let late-ramped
      // VUs finish theirs instead of interrupting them.
      gracefulRampDown: `${WAIT_SECONDS}s`,
    },
  },
  thresholds: {
    waiting_join_success: ['rate > 0.95'],
    http_req_failed: ['rate < 0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

function clientHeaders() {
  const vu = __VU;
  return {
    'Content-Type': 'application/json',
    // Each simulated buyer needs a distinct coarse IP bucket.
    'X-Forwarded-For': `10.${(vu >> 16) & 255}.${(vu >> 8) & 255}.${vu & 255}`,
  };
}

export default function () {
  // ramping-vus starts another iteration as soon as a VU returns. Park the VU
  // after its one real journey, so this stays one arrival per VU rather than
  // turning into an unbounded request loop that measures nothing.
  if (__ITER > 0) {
    sleep(3600);
    return;
  }

  const tag = { event: String(EVENT_ID) };
  const headers = clientHeaders();
  const deadline = Date.now() + WAIT_SECONDS * 1000;

  try {
    const landing = http.get(`${BASE}/api/v1/events/${EVENT_ID}`, {
      headers,
      tags: { step: 'waiting_browse', ...tag },
    });
    classify(landing.status, browse4xx, browse5xx, browseTransport, tag);
    if (!check(landing, { 'event browse succeeded': (response) => response.status === 200 })) {
      return;
    }
    if (tryJson(landing)?.windowStatus === 'CLOSED') {
      exhausted.add(1, tag);
      return;
    }

    const joinedAt = Date.now();
    const join = http.post(
      `${BASE}/api/v1/queue/join`,
      JSON.stringify({ eventId: EVENT_ID }),
      { headers, tags: { step: 'waiting_join', ...tag } },
    );
    classify(join.status, join4xx, join5xx, joinTransport, tag);
    joinSuccess.add(join.status === 202, tag);
    if (join.status === 429) {
      joinRateLimited.add(1, tag);
      return;
    }
    if (join.status !== 202) {
      return;
    }

    while (Date.now() < deadline) {
      const statusStarted = Date.now();
      const response = http.get(
        `${BASE}/api/v1/queue/status?eventId=${EVENT_ID}`,
        { headers, tags: { step: 'waiting_status', ...tag } },
      );
      statusLatency.add(Date.now() - statusStarted, tag);
      classify(response.status, status4xx, status5xx, statusTransport, tag);

      if (response.status === 429) {
        statusRateLimited.add(1, tag);
      } else if (response.status === 200) {
        const body = tryJson(response);
        if (body?.passToken) {
          promoted.add(1, tag);
          queueWait.add((Date.now() - joinedAt) / 1000, tag);
          return;
        }
        if (body?.phase === 'EXHAUSTED' || body?.phase === 'CLOSED') {
          exhausted.add(1, tag);
          queueWait.add((Date.now() - joinedAt) / 1000, tag);
          return;
        }
      } else {
        statusFailures.add(1, tag);
      }
      sleep(POLL_SECONDS);
    }

    timedOut.add(1, tag);
    queueWait.add((Date.now() - joinedAt) / 1000, tag);
  } finally {
    // Keep the VU occupied until the observation window ends. Without this,
    // ramping-vus immediately starts a second iteration on the same VU.
    const remaining = (deadline - Date.now()) / 1000;
    if (remaining > 0) {
      sleep(remaining);
    }
  }
}

function tryJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

function classify(status, clientErrors, serverErrors, transportErrors, tag) {
  if (status === 0) {
    transportErrors.add(1, tag);
  } else if (status >= 400 && status < 500) {
    clientErrors.add(1, tag);
  } else if (status >= 500) {
    serverErrors.add(1, tag);
  }
}
