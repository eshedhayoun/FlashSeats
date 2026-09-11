#!/usr/bin/env bash
#
# FlashSeats — seed the cluster's sale and pre-warm its counters.
#
#   docker compose --profile cluster up -d --build
#   docker/seed/seed.sh
#
# Runs on the HOST. Needs `curl` and a running compose stack; no local psql —
# the SQL goes in through `docker compose exec -T postgres`.
#
# Three steps, in this order for a reason:
#
#   1. Wait for a replica to report healthy. Flyway runs at app startup, so the
#      tables do not exist until then and seeding earlier fails on nothing.
#   2. Apply seed.sql — the event is PUBLISHED with an UPCOMING window.
#   3. Pre-warm, which writes catalog:stock:{e}:{t} AND catalog:vouch:{e}. Only
#      pre-warm and rebuild may vouch for a counter (ADR-046), so this is not an
#      optimisation that could be skipped: without it the sale opens with no
#      counters and every hold answers 503 INVENTORY_UNAVAILABLE.
#
# Then it blocks until windowStatus is OPEN, so whatever runs next — the fan-out
# check, or k6 — starts against a live sale rather than racing the window.

set -euo pipefail

cd "$(dirname "$0")/../.."

# A reserved id, so the load-test sale never collides with — or silently
# defers to — the dev seeder's events 1 and 2 sitting in a persisted volume.
# Must match K6_EVENT_ID / K6_TIER_ID in .env.
EVENT_ID="${EVENT_ID:-9001}"
TIER_ID="${TIER_ID:-9001}"
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"

if [[ ! -f .env ]]; then
    echo "error: .env not found. Run docker/secrets/gen-env.sh first." >&2
    exit 1
fi

# Without `source`: .env holds base64 secrets and a bcrypt digest, both full of
# characters a shell would happily interpret.
ADMIN_USER="$(grep -E '^FLASHSEATS_ADMIN_USERNAME=' .env | head -1 | cut -d= -f2-)"

# The PLAINTEXT, which by design is not in .env — that file holds the bcrypt
# digest the application verifies against, and a digest cannot be used to log in.
# gen-env.sh prints the password once; export it for the session.
ADMIN_PASS="${FLASHSEATS_ADMIN_PLAINTEXT:-}"

if [[ -z "$ADMIN_PASS" ]]; then
    STORED="$(grep -E '^FLASHSEATS_ADMIN_PASSWORD=' .env | head -1 | cut -d= -f2-)"
    if [[ "$STORED" == '{noop}admin' || "$STORED" == 'admin' ]]; then
        echo "error: FLASHSEATS_ADMIN_PASSWORD is still the default." >&2
        echo "       SecretsGuard will have refused to start the replicas (ADR-039)." >&2
        echo "       Run docker/secrets/gen-env.sh." >&2
    else
        echo "error: FLASHSEATS_ADMIN_PLAINTEXT is not set." >&2
        echo "       .env holds the bcrypt digest, which cannot authenticate. Export the" >&2
        echo "       password gen-env.sh printed:" >&2
        echo "         export FLASHSEATS_ADMIN_PLAINTEXT='...'" >&2
    fi
    exit 1
fi

# --- 1. wait for a replica ---------------------------------------------------
echo "Waiting for the cluster to report healthy..."
for _ in $(seq 1 60); do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    echo "error: no healthy replica at ${BASE_URL} after 120s." >&2
    echo "       Check: docker compose --profile cluster logs app-1" >&2
    echo "       A replica that exits at boot is almost always SecretsGuard." >&2
    exit 1
fi
echo "  cluster is up"

# --- 2. seed -----------------------------------------------------------------
echo "Seeding the sale..."
POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | head -1 | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | head -1 | cut -d= -f2-)"

docker compose exec -T postgres \
    psql -q -v ON_ERROR_STOP=1 \
         -U "${POSTGRES_USER_VALUE:-flashseats}" \
         -d "${POSTGRES_DB_VALUE:-flashseats}" \
    < docker/seed/seed.sql

# --- 2b. clear this event's Redis keys --------------------------------------
# The SQL reset alone is not enough: a previous run leaves a drained stock
# counter, a vouch key, and a waiting room full of dead sessions. Pre-warm would
# happily overwrite the counter, but the queue keys would survive and the first
# promotion tick would admit sessions that no longer exist.
#
# Scoped to this event's keys by name. Never `FLUSHALL` — the load-test event
# shares Redis with whatever else is running, and a counter is the one thing in
# this system that cannot be recovered from a cache.
echo "Clearing Redis keys for event ${EVENT_ID}..."
for pattern in "catalog:stock:${EVENT_ID}:*" "catalog:vouch:${EVENT_ID}" \
               "queue:waiting:${EVENT_ID}" "queue:passes:${EVENT_ID}" \
               "queue:admissions:${EVENT_ID}" "queue:exhausted:${EVENT_ID}" \
               "queue:pass:${EVENT_ID}:*" "queue:admit:${EVENT_ID}:*"; do
    # shellcheck disable=SC2016
    docker compose exec -T redis sh -c \
        "redis-cli --scan --pattern '${pattern}' | xargs -r redis-cli DEL" >/dev/null
done

# --- 3. pre-warm -------------------------------------------------------------
# Must happen while the window is still UPCOMING; seed.sql leaves a two-minute
# gap for exactly this call.
echo "Pre-warming counters for event ${EVENT_ID}..."
PREWARM_RESPONSE="$(curl -fsS -u "${ADMIN_USER}:${ADMIN_PASS}" \
    -X POST "${BASE_URL}/api/v1/admin/events/${EVENT_ID}/prewarm")" || {
        echo "error: pre-warm failed." >&2
        echo "       If the window already opened, the sale started before this ran —" >&2
        echo "       tear down and re-seed; pre-warm refuses an open sale (ADR-004)." >&2
        exit 1
    }
echo "  ${PREWARM_RESPONSE}"

# --- 4. wait for the window to open -----------------------------------------
echo "Waiting for the sale window to open..."
for _ in $(seq 1 90); do
    STATUS="$(curl -fsS "${BASE_URL}/api/v1/events/${EVENT_ID}" \
        | tr ',' '\n' | grep '"windowStatus"' | cut -d'"' -f4 || true)"
    if [[ "$STATUS" == "OPEN" ]]; then
        echo
        echo "Event ${EVENT_ID} is OPEN with 500 seats (tier ${TIER_ID}). Next:"
        echo "  docker/scripts/fanout-check.sh                 # prove ADR-007 fan-out"
        echo "  docker compose --profile loadtest run --rm k6  # the load run"
        echo "  docker/scripts/sse-cadence.sh 60               # run DURING the load run"
        echo
        echo "K6_VUS defaults to 10000, which needs roughly 32 GB on the host."
        echo "Scale it to what this machine has:"
        echo "  docker compose --profile loadtest run --rm -e VUS=2000 k6"
        echo
        echo "Re-run this script before every load run — a second run against a"
        echo "drained counter sells nothing and proves nothing."
        exit 0
    fi
    sleep 2
done

echo "error: window did not open within 180s (last status: ${STATUS:-unknown})." >&2
exit 1
