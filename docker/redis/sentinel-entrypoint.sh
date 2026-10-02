#!/bin/sh

set -eu

CONFIG_FILE="/data/sentinel.conf"
MASTER_HOST="${REDIS_MASTER_HOST:-172.28.0.11}"
MASTER_PORT="${REDIS_MASTER_PORT:-6379}"
MASTER_NAME="${REDIS_MASTER_NAME:-flashseats}"
DATA_NODES="${REDIS_DATA_NODES:-172.28.0.11 172.28.0.12 172.28.0.13}"

# Monitor the node that IS the primary now, and rewrite the config on every start (ADR-073).
#
# This used to be written once and then left to Sentinel, which records failovers in it. The data
# nodes do not keep theirs: on every start they take their role from compose (`redis` is primary,
# the replicas `--replicaof redis`). So after a failover and a `down`/`up`, the Sentinels named a
# replica as primary while the nodes had reverted, every app replica connected to a node that was
# about to be demoted, and every write answered READONLY — a bare 500 on every request.
#
# Asking the nodes keeps the two in agreement. A node that reports `master` and has replicas wins; a
# lone `master` comes next; with no node answering yet, compose's default primary.
current=""
lone=""
for node in $DATA_NODES; do
    info="$(redis-cli -h "$node" -p "$MASTER_PORT" --no-raw INFO replication 2>/dev/null || true)"
    case "$info" in
        *role:master*)
            replicas="$(printf '%s\n' "$info" | sed -n 's/^connected_slaves:\([0-9]*\).*/\1/p' | head -1)"
            if [ "${replicas:-0}" -gt 0 ]; then
                current="$node"
                break
            fi
            [ -z "$lone" ] && lone="$node"
            ;;
    esac
done
MASTER_HOST="${current:-${lone:-$MASTER_HOST}}"

cat > "$CONFIG_FILE" <<CONF
port 26379
bind 0.0.0.0
protected-mode no
sentinel resolve-hostnames no

dir /data

sentinel monitor ${MASTER_NAME} ${MASTER_HOST} ${MASTER_PORT} 2
sentinel down-after-milliseconds ${MASTER_NAME} 5000
sentinel failover-timeout ${MASTER_NAME} 60000
sentinel parallel-syncs ${MASTER_NAME} 1
CONF

echo "sentinel: monitoring ${MASTER_NAME} at ${MASTER_HOST}:${MASTER_PORT}"
exec redis-server "$CONFIG_FILE" --sentinel
