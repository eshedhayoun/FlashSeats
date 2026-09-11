#!/usr/bin/env bash
#
# FlashSeats — measure how fast position updates actually arrive.
#
#   docker/scripts/sse-cadence.sh [seconds]
#
# Run it DURING a load run. It answers one question with a number:
#
#   Is QueueBroadcaster's sweep finishing inside its own interval?
#
# WHY THIS IS THE MEASUREMENT THAT MATTERS. The sweep calls getQueueState once
# per connection per tick, and that is four sequential Redis round trips — the
# admission GET, the pass GET, the exhausted EXISTS and the waiting ZRANK. The
# cost is therefore linear in CONNECTIONS, not in events, and the interval it
# has to fit inside is fixed at flashseats.queue.sse-position-interval-ms
# (2000 ms). Past some connection count the sweep simply cannot finish in time,
# and the symptom is not an error anywhere — it is buyers watching a counter
# that updates more and more slowly.
#
# So the instrument is a client-side one: hold a few streams open and time the
# gaps between position-update frames. A median at or near the configured
# interval means the sweep is keeping up. A median well above it is the
# overrun, measured rather than assumed, and it is what justifies replacing the
# per-connection reads with one pipelined round trip per event per tick.
#
# Deliberately opens only a handful of connections. It is measuring the load the
# run is already applying, and must not add meaningfully to it.

set -euo pipefail

cd "$(dirname "$0")/../.."

EVENT_ID="${EVENT_ID:-9001}"
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"
OBSERVERS="${OBSERVERS:-5}"
DURATION="${1:-60}"
INTERVAL_MS="${INTERVAL_MS:-2000}"   # flashseats.queue.sse-position-interval-ms

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Measuring SSE position-update cadence for ${DURATION}s (${OBSERVERS} observers)"
echo "  configured interval: ${INTERVAL_MS} ms"
echo

for i in $(seq 1 "$OBSERVERS"); do
    JAR="${WORK}/jar-${i}"
    curl -fsS -c "$JAR" "${BASE_URL}/api/v1/events/${EVENT_ID}" >/dev/null
    curl -fsS -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
        -d "{\"eventId\":${EVENT_ID},\"recaptchaToken\":\"cadence\"}" \
        "${BASE_URL}/api/v1/queue/join" >/dev/null

    # Stamp each position-update with the millisecond it arrived.
    #
    # perl, not awk: macOS ships BSD/BWK awk, which has no systime() — and the
    # failure is a runtime error per line rather than a parse error, so it looks
    # like "no frames arrived" rather than "this tool is broken". $| = 1 keeps
    # the stamp an arrival time rather than a flush time.
    curl -s -N --max-time "$DURATION" -b "$JAR" \
        "${BASE_URL}/api/v1/queue/stream?eventId=${EVENT_ID}" \
        | perl -MTime::HiRes=time -ne '$| = 1; print int(time * 1000), "\n" if /^event:\s*position-update/' \
        > "${WORK}/stamps-${i}" &
done

wait

# Gaps between consecutive frames on each stream, pooled.
for i in $(seq 1 "$OBSERVERS"); do
    awk 'NR > 1 { print $1 - prev } { prev = $1 }' "${WORK}/stamps-${i}" 2>/dev/null
done | sort -n > "${WORK}/gaps"

SAMPLES="$(wc -l < "${WORK}/gaps" | tr -d ' ')"

if [[ "$SAMPLES" -lt 3 ]]; then
    echo "Not enough frames to measure (${SAMPLES} gaps)."
    echo "Is the sale open and are these sessions still WAITING? A promoted or"
    echo "admitted session stops receiving position updates, which is correct."
    exit 2
fi

MEDIAN="$(awk -v n="$SAMPLES" 'NR == int((n + 1) / 2) { print $1 }' "${WORK}/gaps")"
P95="$(awk -v n="$SAMPLES" 'NR == int(n * 0.95) || NR == n { v = $1 } END { print v }' "${WORK}/gaps")"
MAX="$(tail -1 "${WORK}/gaps")"

echo "=========================================================="
echo "  SSE position-update cadence"
echo "=========================================================="
echo "  Samples        ${SAMPLES}"
echo "  Configured     ${INTERVAL_MS} ms"
echo "  Median gap     ${MEDIAN} ms"
echo "  p95 gap        ${P95} ms"
echo "  Max gap        ${MAX} ms"
echo "----------------------------------------------------------"

# Two ticks of slack: one sweep's worth of jitter is normal, two means the
# sweep is starting late because the previous one had not finished.
if [[ "$MEDIAN" -le $((INTERVAL_MS * 2)) ]]; then
    echo "  OK    the sweep is keeping up with its interval"
else
    echo "  SLOW  the sweep is overrunning its ${INTERVAL_MS} ms interval."
    echo "        QueueBroadcaster.sweep does 4 sequential Redis round trips"
    echo "        per connection per tick. Batch them into one pipelined call"
    echo "        per event per tick."
fi
echo "=========================================================="
