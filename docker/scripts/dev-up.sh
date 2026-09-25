#!/usr/bin/env bash
#
# FlashSeats — bring up a LOCAL development stack that is actually walkable.
#
#   docker/scripts/dev-up.sh            # check, fix what is safe, then print what to run
#   docker/scripts/dev-up.sh --reset    # DESTROYS the volumes first, for a clean seeded sale
#
# WHY THIS EXISTS. `docker compose up -d && ./mvnw spring-boot:run` is the
# documented dev loop and it has three ways to fail that all look like "the
# backend is broken", none of which says so:
#
#   1. Port 8080 held by an unrelated process. Spring says "Port 8080 was
#      already in use" and stops, which is clear — but the port is just as often
#      held by THIS project's own nginx or a replica, and then the browser shows
#      a working app that is not the code you just edited.
#
#   2. A cluster left running. `--profile cluster` replicas talk to the same
#      Redis and the same PostgreSQL that a local `spring-boot:run` uses, so
#      both are live in one shared world. They do not share SECRETS: the
#      containers were built with the real values gen-env.sh wrote, and a local
#      run falls back to `dev-only-change-me`. Whichever app mints a queue pass,
#      the other rejects it — `401 QUEUE_PASS_INVALID` halfway through the
#      journey, on correct code. Their promotion workers, sweepers and drift
#      gauges also interleave on the same keys.
#
#   3. Every sale expired. CatalogDevSeeder seeds only when the database is
#      EMPTY — deliberately, so a restart never resets a live sale — so once the
#      volume holds anything (a drill, yesterday's walkthrough), a fresh start
#      gives you a browse list of CLOSED events and no journey at all.
#
# WHAT THIS SCRIPT WILL NOT DO. It never writes a stock counter. Seeding a
# counter from `total_capacity` is the first trap in CLAUDE.md's table: it
# resurrects every sold ticket. Where counters are missing it says so and points
# at the two legal recoveries (a clean reset, or the rebuild endpoint), because
# only those two derive the number from something that knows it (ADR-004).

set -euo pipefail

cd "$(dirname "$0")/../.."

APP_PORT="${APP_PORT:-8080}"
INFRA=(postgres redis rabbitmq mailpit)
CONFLICTING=(app-1 app-2 app-3 nginx)
RESET=0

for arg in "$@"; do
    case "$arg" in
        --reset) RESET=1 ;;
        -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }

psql_q() {
    docker compose exec -T postgres \
        psql -U "${POSTGRES_USER:-flashseats}" -d "${POSTGRES_DB:-flashseats}" -tAc "$1"
}

redis_q() { docker compose exec -T redis redis-cli "$@"; }

# ---------------------------------------------------------------- 1. the port
say "1. Port ${APP_PORT}"

holders="$(lsof -nP -tiTCP:"${APP_PORT}" -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "$holders" ]]; then
    bad "port ${APP_PORT} is in use by:"
    # shellcheck disable=SC2086
    ps -o pid=,command= -p $holders | cut -c1-110 | sed 's/^/      /'
    echo
    echo "  Stop it, or run the app elsewhere:"
    echo "      ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081"
    echo
    echo "  This script will not kill it: on this machine that process has more"
    echo "  than once been an unrelated project, and killing someone's other"
    echo "  service to free a default port is not a fix."
    exit 1
fi
ok "free"

# ------------------------------------------------------- 2. the stale cluster
say "2. Cluster replicas"

running_conflicts=()
for service in "${CONFLICTING[@]}"; do
    if [[ -n "$(docker compose ps -q "$service" 2>/dev/null)" ]] \
        && docker compose ps --status running --format '{{.Service}}' 2>/dev/null \
            | grep -qx "$service"; then
        running_conflicts+=("$service")
    fi
done

if (( ${#running_conflicts[@]} )); then
    warn "stopping ${running_conflicts[*]} — they share this Redis and PostgreSQL"
    warn "with a local run but sign queue passes with different secrets"
    docker compose stop "${running_conflicts[@]}" >/dev/null 2>&1
    ok "stopped (docker compose --profile cluster up -d brings them back)"
else
    ok "none running"
fi

# ------------------------------------------------------------- 3. the volumes
if (( RESET )); then
    say "3. Reset (--reset)"
    warn "this DESTROYS the postgres and redis volumes"
    read -r -p "  Type 'reset' to confirm: " answer
    [[ "$answer" == "reset" ]] || { bad "aborted"; exit 1; }
    docker compose down -v >/dev/null 2>&1
    ok "volumes removed; CatalogDevSeeder will seed a fresh open sale on boot"
fi

# --------------------------------------------------------------- 4. the infra
say "4. Infrastructure"

docker compose up -d "${INFRA[@]}" >/dev/null 2>&1
for service in "${INFRA[@]}"; do
    for _ in $(seq 1 60); do
        status="$(docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null \
            | awk -v s="$service" '$1 == s { print $2 }')"
        [[ "$status" == "healthy" || -z "$status" ]] && break
        sleep 1
    done
    ok "$service"
done

# ------------------------------------------------------------- 5. a live sale
say "5. A walkable sale"

events="$(psql_q "SELECT count(*) FROM events" || echo 0)"

if [[ "$events" == "0" ]]; then
    ok "database is empty — CatalogDevSeeder seeds an open sale on the next boot"
else
    # PUBLISHED, not ACTIVE. SaleWindows.statusOf answers CLOSED for anything
    # that is not PUBLISHED — that is how an operator's pause closes a sale for
    # free — so PUBLISHED is exactly the set that can be open.
    open_now="$(psql_q "
        SELECT count(*) FROM events
         WHERE status = 'PUBLISHED'
           AND sale_start_time <= now() AND sale_end_time > now()")"

    if [[ "${open_now:-0}" -gt 0 ]]; then
        ok "${open_now} sale(s) already open"
    else
        # Reopen the lowest-id event whose tiers ALL still have a live counter.
        # Counters are never written here (see the header): an event without them
        # is left alone and reported, because the only honest ways back are a
        # reset, where pre-warm runs legitimately on an UPCOMING event, and the
        # rebuild endpoint, which derives the number from the ledger.
        candidate="$(psql_q "
            SELECT e.id FROM events e
             WHERE e.status = 'PUBLISHED'
               AND EXISTS (SELECT 1 FROM ticket_tiers t WHERE t.event_id = e.id)
             ORDER BY e.id
             LIMIT 50" | tr -d '\r')"

        chosen=""
        for id in $candidate; do
            tiers="$(psql_q "SELECT id FROM ticket_tiers WHERE event_id = $id" | tr -d '\r')"
            all_present=1
            for tier in $tiers; do
                [[ "$(redis_q EXISTS "catalog:stock:${id}:${tier}" | tr -d '\r')" == "1" ]] \
                    || { all_present=0; break; }
            done
            (( all_present )) && { chosen="$id"; break; }
        done

        if [[ -z "$chosen" ]]; then
            bad "every event's sale window has passed, and none has intact counters"
            echo
            echo "  Two legal recoveries, and writing the counters here is neither:"
            echo "      docker/scripts/dev-up.sh --reset          # clean seeded sale"
            echo "      POST /api/v1/admin/events/{id}/rebuild-stock   # from the ledger"
            exit 1
        fi

        psql_q "
            UPDATE events
               SET sale_start_time = now() - interval '1 minute',
                   sale_end_time   = now() + interval '8 hours',
                   event_start_time = greatest(event_start_time, now() + interval '30 days')
             WHERE id = $chosen" >/dev/null
        ok "reopened event ${chosen} for 8 hours (its counters were intact)"
    fi
fi

# ------------------------------------------------------------------- 6. go
say "Ready"

cat <<EOF
  Backend       ./mvnw spring-boot:run                 http://localhost:${APP_PORT}
  Demo client                                          http://localhost:${APP_PORT}/
  React client  cd frontend && npm run dev             http://localhost:5173
  Mail                                                 http://localhost:8025
  RabbitMQ                                             http://localhost:15672

  The React client needs frontend/.env.local once:
      cd frontend && npm install && cp .env.example .env.local
  Leave VITE_STRIPE_PUBLISHABLE_KEY blank to drive the stub gateway, which is
  what the backend runs by default.
EOF
