#!/bin/sh

set -eu

CONFIG_FILE="/data/sentinel.conf"
MASTER_HOST="${REDIS_MASTER_HOST:-172.28.0.11}"
MASTER_PORT="${REDIS_MASTER_PORT:-6379}"
MASTER_NAME="${REDIS_MASTER_NAME:-flashseats}"

if [ ! -f "$CONFIG_FILE" ]; then
    cat > "$CONFIG_FILE" <<EOF
port 26379
bind 0.0.0.0
protected-mode no
sentinel resolve-hostnames no

dir /data

sentinel monitor ${MASTER_NAME} ${MASTER_HOST} ${MASTER_PORT} 2
sentinel down-after-milliseconds ${MASTER_NAME} 5000
sentinel failover-timeout ${MASTER_NAME} 60000
sentinel parallel-syncs ${MASTER_NAME} 1
EOF
fi

exec redis-server "$CONFIG_FILE" --sentinel