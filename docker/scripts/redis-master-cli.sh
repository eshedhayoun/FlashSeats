#!/usr/bin/env bash

set -euo pipefail

sentinel_service="${SENTINEL_SERVICE:-redis-sentinel-1}"
master_name="${REDIS_MASTER_NAME:-flashseats}"

sentinel_container="$(docker compose ps -q "$sentinel_service" 2>/dev/null || true)"
if [[ -n "$sentinel_container" ]]; then
    master_ip="$(
        docker compose exec -T "$sentinel_service" \
            redis-cli -p 26379 SENTINEL get-master-addr-by-name "$master_name" \
            2>/dev/null | sed -n '1p' | tr -d '\r'
    )" || {
        echo "error: Sentinel service is running but did not return master ${master_name}." >&2
        exit 1
    }

    case "$master_ip" in
        172.28.0.11) redis_service=redis ;;
        172.28.0.12) redis_service=redis-replica-1 ;;
        172.28.0.13) redis_service=redis-replica-2 ;;
        *) echo "error: Sentinel returned unknown master address: ${master_ip}" >&2; exit 1 ;;
    esac
else
    # Local/dev uses the standalone service and does not start Sentinel.
    redis_service=redis
fi

exec docker compose exec -T "$redis_service" redis-cli "$@"
