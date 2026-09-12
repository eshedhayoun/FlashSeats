#!/usr/bin/env bash
#
# FlashSeats — seed FIVE simultaneous sales, for the ADR-049 drill.
#
#   docker compose --profile cluster up -d --build
#   export FLASHSEATS_ADMIN_PLAINTEXT='...'          # what gen-env.sh printed
#   docker/seed/seed-concurrent.sh
#   docker/scripts/pool-pressure.sh 300 &            # the instrument
#   docker compose --profile loadtest run --rm -e VUS=2000 k6-concurrent
#
# The sibling of docker/seed/seed.sh, and it works the same way: seed as
# UPCOMING, pre-warm inside the window, wait for OPEN. The difference is E.
#
# WHY A SEPARATE DRILL. Every other instrument here runs one event, and so did
# every measurement the design rests on. ADR-028 caps the promotion batch at
# `hikariMax x 1.5`, which is right for ONE sale; PromotionWorker applies that
# cap per event and `queue:promote:{e}` is per event, so E concurrent sales
# admit R x E x batchSize per second into one shared pool. At E=5 that is 675
# admissions/sec against 90 connections.
#
# It will not show up as an error, a 500 or a drift alarm. Under virtual threads
# the requests simply queue on HikariCP while p99 collapses, so the thing to
# watch is `hikaricp_connections_pending` — PER REPLICA, because nginx routes
# only /actuator/health and a load balancer would hand you one at random. That
# is what docker/scripts/pool-pressure.sh does, and this drill is meaningless
# without it running alongside.

set -euo pipefail

cd "$(dirname "$0")/../.."

EVENTS="${EVENTS:-5}"
FIRST_ID="${FIRST_ID:-9001}"
LAST_ID=$((FIRST_ID + EVENTS - 1))
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"

if [[ ! -f .env ]]; then
    echo "error: .env not found. Run docker/secrets/gen-env.sh first." >&2
    exit 1
fi

ADMIN_USER="$(grep -E '^FLASHSEATS_ADMIN_USERNAME=' .env | head -1 | cut -d= -f2-)"
ADMIN_PASS="${FLASHSEATS_ADMIN_PLAINTEXT:-}"

if [[ -z "$ADMIN_PASS" ]]; then
    echo "error: FLASHSEATS_ADMIN_PLAINTEXT is not set." >&2
    echo "       .env holds the bcrypt digest, which cannot authenticate. Export the" >&2
    echo "       password gen-env.sh printed:" >&2
    echo "         export FLASHSEATS_ADMIN_PLAINTEXT='...'" >&2
    exit 1
fi

# --- 1. wait for a replica ---------------------------------------------------
echo "Waiting for the cluster to report healthy..."
for _ in $(seq 1 60); do
    curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1 && break
    sleep 2
done

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    echo "error: no healthy replica at ${BASE_URL} after 120s." >&2
    echo "       A replica that exits at boot is almost always SecretsGuard (ADR-039)." >&2
    exit 1
fi
echo "  cluster is up"

# --- 2. seed -----------------------------------------------------------------
echo "Seeding ${EVENTS} simultaneous sales (${FIRST_ID}..${LAST_ID})..."
POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | head -1 | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | head -1 | cut -d= -f2-)"

docker compose exec -T postgres \
    psql -q -v ON_ERROR_STOP=1 \
         -v events="${EVENTS}" -v first_id="${FIRST_ID}" \
         -U "${POSTGRES_USER_VALUE:-flashseats}" \
         -d "${POSTGRES_DB_VALUE:-flashseats}" \
    < docker/seed/seed-concurrent.sql

# --- 2b. clear each event's Redis keys ---------------------------------------
# Never FLUSHALL. The stock counter is the one thing in this system that cannot
# be recovered from a cache, and this Redis is shared with whatever else runs.
echo "Clearing Redis keys for events ${FIRST_ID}..${LAST_ID}..."
for id in $(seq "$FIRST_ID" "$LAST_ID"); do
    for pattern in "catalog:stock:${id}:*" "catalog:vouch:${id}" \
                   "queue:waiting:${id}" "queue:passes:${id}" \
                   "queue:admissions:${id}" "queue:exhausted:${id}" \
                   "queue:promote:${id}" \
                   "queue:pass:${id}:*" "queue:admit:${id}:*"; do
        # shellcheck disable=SC2016
        docker compose exec -T redis sh -c \
            "redis-cli --scan --pattern '${pattern}' | xargs -r redis-cli DEL" >/dev/null
    done
done

# --- 3. pre-warm every event -------------------------------------------------
# Must happen while each window is still UPCOMING; the SQL leaves a two-minute
# gap for exactly this. Pre-warm is also the only sanctioned way to write a
# counter and the only thing that vouches for one (ADR-046), so a failure here
# means that sale answers 503 INVENTORY_UNAVAILABLE for its whole life.
echo "Pre-warming counters..."
for id in $(seq "$FIRST_ID" "$LAST_ID"); do
    RESPONSE="$(curl -fsS -u "${ADMIN_USER}:${ADMIN_PASS}" \
        -X POST "${BASE_URL}/api/v1/admin/events/${id}/prewarm")" || {
            echo "error: pre-warm failed for event ${id}." >&2
            echo "       If the window already opened, pre-warm refuses it (ADR-004)." >&2
            echo "       Tear down and re-seed." >&2
            exit 1
        }
    echo "  ${id}: ${RESPONSE}"
done

# --- 4. wait for every window to open ----------------------------------------
echo "Waiting for all ${EVENTS} windows to open..."
for _ in $(seq 1 90); do
    OPEN=0
    for id in $(seq "$FIRST_ID" "$LAST_ID"); do
        STATUS="$(curl -fsS "${BASE_URL}/api/v1/events/${id}" \
            | tr ',' '\n' | grep '"windowStatus"' | cut -d'"' -f4 || true)"
        [[ "$STATUS" == "OPEN" ]] && OPEN=$((OPEN + 1))
    done

    if [[ "$OPEN" -eq "$EVENTS" ]]; then
        echo
        echo "All ${EVENTS} sales are OPEN (${FIRST_ID}..${LAST_ID}), 500 seats each."
        echo
        echo "Run the instrument and the load together — the drill is the PAIR:"
        echo "  docker/scripts/pool-pressure.sh 300 &"
        echo "  docker compose --profile loadtest run --rm -e VUS=2000 k6-concurrent"
        echo
        echo "What to look for:"
        echo "  hikaricp_connections_pending  sustained > 0 on any replica is the"
        echo "                                ADR-049 finding. It is a LATENCY failure:"
        echo "                                nothing errors, p99 just collapses."
        echo "  flashseats_stock_drift        must stay 0.0 on every replica. If this"
        echo "                                moves, the problem is correctness, not load."
        echo "  tickets_sold per event        must be <= 500 for each. k6 asserts it."
        exit 0
    fi
    sleep 2
done

echo "error: only ${OPEN:-0}/${EVENTS} windows opened within 180s." >&2
exit 1
