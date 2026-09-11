#!/usr/bin/env bash
#
# FlashSeats — prove promotion fan-out across replicas (ADR-007).
#
#   docker compose --profile cluster up -d --build
#   docker/seed/seed.sh
#   docker/scripts/fanout-check.sh
#
# THE HIGHEST-VALUE UNVERIFIED CLAIM IN THE SYSTEM. PromotionWorker runs on
# whichever replica wins the queue:promote:{e} tick lock; a buyer's SseEmitter
# lives in whichever replica's heap nginx happened to put them. Nothing connects
# those two facts except the Redis Pub/Sub hop on queue:events:{e}. Remove it and
# the system works perfectly on one instance and silently drops roughly
# two-thirds of promotions on three — which is why this cannot be a unit test and
# why a single-instance run proves nothing.
#
# WHY NOT JUST READ THE k6 SUMMARY. Under ten thousand virtual users a dropped
# promotion is indistinguishable from a timeout, a rate limit or a slow sweep. A
# small deterministic check that finishes in seconds is a far better instrument:
# thirty sessions all promote in a single tick, so anything short of 30/30 is a
# real defect rather than contention.
#
# PASS requires BOTH:
#   * every session received its queue-promoted frame, and
#   * the streams were spread over at least two replicas.
# The second condition is what makes it a fan-out test rather than a delivery
# test. X-Upstream comes from nginx (docker/nginx/nginx.conf).

set -euo pipefail

cd "$(dirname "$0")/../.."

EVENT_ID="${EVENT_ID:-9001}"   # the reserved load-test sale; see docker/seed/seed.sql
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"
SESSIONS="${SESSIONS:-30}"
# promotion-interval-ms is 1000 and promotion-batch-size is 45, so 30 sessions
# are promoted in one tick. Waiting several gives the tick lock, the publish and
# the fan-out room without making a failure look like impatience.
WAIT_SECONDS="${WAIT_SECONDS:-12}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Fan-out check — ${SESSIONS} sessions against ${BASE_URL}, event ${EVENT_ID}"
echo

# --- 0. the sale must be open ------------------------------------------------
# PromotionWorker iterates catalog.findOpenEventIds(); against an UPCOMING or
# CLOSED window it never runs and every session would fail for the wrong reason.
WINDOW="$(curl -fsS "${BASE_URL}/api/v1/events/${EVENT_ID}" \
    | tr ',' '\n' | grep '"windowStatus"' | cut -d'"' -f4 || true)"
if [[ "$WINDOW" != "OPEN" ]]; then
    echo "error: event ${EVENT_ID} is ${WINDOW:-unreachable}, not OPEN." >&2
    echo "       Run docker/seed/seed.sh first." >&2
    exit 1
fi

# --- 1. mint a session, join the queue, open a stream ------------------------
for i in $(seq 1 "$SESSIONS"); do
    JAR="${WORK}/cookies-${i}"

    # The landing page is what mints the signed fsid cookie. Identity comes from
    # that cookie and nowhere else (ADR-010), so every subsequent call for this
    # session has to carry the same jar.
    curl -fsS -c "$JAR" "${BASE_URL}/api/v1/events/${EVENT_ID}" >/dev/null

    curl -fsS -b "$JAR" -c "$JAR" \
        -H 'Content-Type: application/json' \
        -d "{\"eventId\":${EVENT_ID},\"recaptchaToken\":\"fanout-check\"}" \
        "${BASE_URL}/api/v1/queue/join" >/dev/null

    # -N defeats curl's own buffering, so a frame lands in the file the moment
    # it arrives rather than when the stream closes. --max-time ends the stream
    # on its own: killing background jobs instead made bash print thirty
    # "Terminated" lines over the verdict. -s (not -sS) because a deliberate
    # timeout is not an error worth reporting.
    curl -s -N --max-time "$WAIT_SECONDS" -b "$JAR" \
        -D "${WORK}/headers-${i}" \
        -o "${WORK}/stream-${i}" \
        "${BASE_URL}/api/v1/queue/stream?eventId=${EVENT_ID}" &
done

echo "  ${SESSIONS} sessions joined and streaming; waiting ${WAIT_SECONDS}s for a promotion tick..."
wait

# --- 2. verdict --------------------------------------------------------------
PROMOTED=0
MISSING=""
: > "${WORK}/upstreams"

for i in $(seq 1 "$SESSIONS"); do
    UPSTREAM="$(grep -i '^x-upstream:' "${WORK}/headers-${i}" 2>/dev/null \
        | tail -1 | tr -d '\r' | awk '{print $2}')"
    echo "${UPSTREAM:-unknown}" >> "${WORK}/upstreams"

    if grep -q 'queue-promoted' "${WORK}/stream-${i}" 2>/dev/null; then
        PROMOTED=$((PROMOTED + 1))
    else
        MISSING="${MISSING} ${i}(${UPSTREAM:-unknown})"
    fi
done

REPLICAS="$(sort -u "${WORK}/upstreams" | grep -cv '^$' || true)"

echo
echo "=========================================================="
echo "  Promotion fan-out (ADR-007)"
echo "=========================================================="
echo "  Sessions            ${SESSIONS}"
echo "  Promoted            ${PROMOTED}"
echo "  Distinct replicas   ${REPLICAS}"
echo "----------------------------------------------------------"
echo "  Streams per replica:"
sort "${WORK}/upstreams" | uniq -c | sed 's/^/   /'
echo "----------------------------------------------------------"

if [[ "$PROMOTED" -eq "$SESSIONS" && "$REPLICAS" -ge 2 ]]; then
    echo "  PASS  every session promoted, across ${REPLICAS} replicas"
    echo "=========================================================="
    exit 0
fi

if [[ "$REPLICAS" -lt 2 ]]; then
    # Not a fan-out failure — the test never got to ask the question.
    echo "  INCONCLUSIVE  all streams landed on one replica."
    echo "                Are all three app containers healthy?"
    echo "                  docker compose --profile cluster ps"
    echo "=========================================================="
    exit 2
fi

echo "  FAIL  ${MISSING# } did not receive a promotion"
echo
echo "  A partial result spread across replicas is the signature this"
echo "  check exists to catch: the promoter published to queue:events:${EVENT_ID}"
echo "  and only the replica it ran on delivered. Look at"
echo "  QueuePubSubConfig's listener container and the PUBLISH in"
echo "  PromotionWorker.issuePass."
echo "=========================================================="
exit 1
