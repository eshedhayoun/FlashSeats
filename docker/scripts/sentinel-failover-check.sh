#!/usr/bin/env bash
#
# Prove that the cluster can route around a Redis primary failure.
#
# This is deliberately a topology check, not an inventory-rebuild test. The
# application remains responsible for rejecting an unvouched event after a
# failover; this script only proves Sentinel promotion and replica recovery.
#
# Usage:
#   docker/scripts/sentinel-failover-check.sh

set -euo pipefail

cd "$(dirname "$0")/../.."

SENTINEL_SERVICE="${SENTINEL_SERVICE:-redis-sentinel-1}"
MASTER_NAME="${MASTER_NAME:-flashseats}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"

master_address() {
    docker compose exec -T "$SENTINEL_SERVICE" \
        redis-cli -p 26379 SENTINEL get-master-addr-by-name "$MASTER_NAME" \
        | sed -n '1p' | tr -d '\r'
}

master_service() {
    case "$1" in
        172.28.0.11) echo redis ;;
        172.28.0.12) echo redis-replica-1 ;;
        172.28.0.13) echo redis-replica-2 ;;
        *)
            echo "error: Sentinel returned unknown master address: $1" >&2
            return 1
            ;;
    esac
}

old_master=""
old_service=""
restored=0

cleanup() {
    if [[ -n "$old_service" && "$restored" -eq 0 ]]; then
        docker compose start "$old_service" >/dev/null
    fi
}
trap cleanup EXIT

old_master="$(master_address)"
old_service="$(master_service "$old_master")"

echo "Current Sentinel master: ${old_master} (${old_service})"
echo "Stopping the current primary..."
docker compose stop "$old_service" >/dev/null

new_master=""
for _ in $(seq 1 "$WAIT_SECONDS"); do
    new_master="$(master_address || true)"
    if [[ -n "$new_master" && "$new_master" != "$old_master" ]]; then
        break
    fi
    sleep 1
done

if [[ -z "$new_master" || "$new_master" == "$old_master" ]]; then
    echo "error: Sentinel did not promote a different master within ${WAIT_SECONDS}s." >&2
    exit 1
fi

echo "Promoted Sentinel master: ${new_master}"

echo "Restoring the previous primary..."
docker compose start "$old_service" >/dev/null
restored=1

for _ in $(seq 1 "$WAIT_SECONDS"); do
    role="$(docker compose exec -T "$old_service" redis-cli INFO replication \
        | awk -F: '$1 == "role" { gsub("\r", "", $2); print $2; exit }')"
    link="$(docker compose exec -T "$old_service" redis-cli INFO replication \
        | awk -F: '$1 == "master_link_status" { gsub("\r", "", $2); print $2; exit }')"
    if [[ "$role" == "slave" && "$link" == "up" ]]; then
        echo "Previous primary rejoined as a healthy replica."
        exit 0
    fi
    sleep 1
done

echo "error: previous primary did not rejoin as a healthy replica." >&2
exit 1
