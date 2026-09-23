#!/usr/bin/env bash

set -euo pipefail

sentinel_service="${SENTINEL_SERVICE:-redis-sentinel-1}"
master_name="${REDIS_MASTER_NAME:-flashseats}"

if master_ip="$(
    docker compose exec -T "$sentinel_service" \
        redis-cli -p 26379 SENTINEL get-master-addr-by-name "$master_name" \
        2>/dev/null | sed -n '1p' | tr -d '\r'
)"; then
    case "$master_ip" in
        172.28.0.11) redis_service=redis ;;
        172.28.0.12) redis_service=redis-replica-1 ;;
        172.28.0.13) redis_service=redis-replica-2 ;;
        *) redis_service="" ;;
    esac
else
    redis_service=""
fi

if [[ -z "$redis_service" ]]; then
    redis_service=redis
fi

exec docker compose exec -T "$redis_service" redis-cli "$@"
