#!/usr/bin/env bash
#
# Seed the evaluator-facing Aurora Fest and Midnight Sessions catalog.
# Unlike the concurrent-sale seed, this is for the human browser journey.

set -euo pipefail

cd "$(dirname "$0")/../.."
REDIS_CLI="./docker/scripts/redis-master-cli.sh"
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"

if [[ ! -f .env ]]; then
    echo "error: .env not found. Run docker/scripts/professor-demo.sh first." >&2
    exit 1
fi

ADMIN_USER="$(grep -E '^FLASHSEATS_ADMIN_USERNAME=' .env | head -1 | cut -d= -f2-)"
ADMIN_PASS="${FLASHSEATS_ADMIN_PLAINTEXT:-}"
if [[ -z "$ADMIN_PASS" ]]; then
    echo "error: FLASHSEATS_ADMIN_PLAINTEXT is not set in this shell." >&2
    exit 1
fi

for _ in $(seq 1 60); do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | head -1 | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | head -1 | cut -d= -f2-)"
docker compose exec -T postgres \
    psql -q -v ON_ERROR_STOP=1 \
         -U "${POSTGRES_USER_VALUE:-flashseats}" \
         -d "${POSTGRES_DB_VALUE:-flashseats}" \
    < docker/seed/seed-demo.sql

for event_id in 9101 9102; do
    for pattern in "catalog:stock:${event_id}:*" "catalog:vouch:${event_id}" \
                   "queue:waiting:${event_id}" "queue:passes:${event_id}" \
                   "queue:admissions:${event_id}" "queue:exhausted:${event_id}" \
                   "queue:pass:${event_id}:*" "queue:admit:${event_id}:*"; do
        "$REDIS_CLI" --scan --pattern "$pattern" |
            while IFS= read -r key; do
                [[ -n "$key" ]] && "$REDIS_CLI" DEL "$key" >/dev/null
            done
    done
done

for event_id in 9101 9102; do
    curl -fsS -u "${ADMIN_USER}:${ADMIN_PASS}" \
        -X POST "${BASE_URL}/api/v1/admin/events/${event_id}/prewarm" >/dev/null
done

for _ in $(seq 1 90); do
    aurora="$(curl -fsS "${BASE_URL}/api/v1/events/9101" | tr ',' '\n' |
        grep '"windowStatus"' | cut -d'"' -f4 || true)"
    midnight="$(curl -fsS "${BASE_URL}/api/v1/events/9102" | tr ',' '\n' |
        grep '"windowStatus"' | cut -d'"' -f4 || true)"
    if [[ "$aurora" == "OPEN" && "$midnight" == "OPEN" ]]; then
        echo "Aurora Fest 2026 and Midnight Sessions are OPEN."
        exit 0
    fi
    sleep 2
done

echo "error: demo sales did not open within 180 seconds." >&2
exit 1
