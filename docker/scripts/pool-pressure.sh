#!/usr/bin/env bash
#
# FlashSeats — watch the connection pool while several sales run at once.
#
#   docker/scripts/pool-pressure.sh [seconds]
#
# Run it DURING the concurrent-sales run. It answers the one question that drill
# exists to ask, with a number:
#
#   Does admitting from E sales at once outrun the connection pool?
#
# WHY k6 CANNOT ANSWER IT. ADR-028 caps the promotion batch at
# `hikariMax x 1.5` — correct for ONE sale, which is the assumption it never
# stated. PromotionWorker loops every open event and applies that cap PER EVENT,
# and `queue:promote:{e}` is per event too, so replicas promote different sales
# in the same second:
#
#     R x E x batchSize = 3 x 5 x 45 = 675 admissions/sec into 90 connections
#
# and a checkout is EIGHT sequential transactions, not the ~1 that "capacity to
# serve" priced. Under virtual threads that combination produces NO error, NO
# 500 and NO drift: the requests queue on HikariCP and p99 collapses. A load
# harness watching status codes sees a healthy run.
#
# So the instrument reads the gauges directly, and reads them PER REPLICA:
# nginx routes only /actuator/health, and going through a load balancer would
# hand you one instance at random — which for a per-instance gauge is worse than
# not measuring at all, because it looks like a measurement.
#
# WHAT TO CONCLUDE.
#
#   hikaricp_connections_pending   Sustained > 0 on any replica IS the ADR-049
#                                  finding. Brief spikes at the ramp are normal;
#                                  a floor that never returns to 0 is not.
#   hikaricp_connections_active    At the pool maximum (30) for long stretches
#                                  says the same thing from the other side.
#   flashseats_stock_drift         MUST stay 0.0 everywhere. This drill is about
#                                  latency; if drift moves, stop and read it as a
#                                  correctness failure instead (ADR-046).
#   flashseats_stock_counters_missing
#                                  Any non-zero means a tier has no counter and
#                                  is answering 503 (ADR-004). Not a load result.
#
# The actuator endpoints are ROLE_ADMIN — metrics describe inventory levels,
# queue depth and pool pressure, which is a live read on how a sale is going and
# a useful one to anyone attacking it.

set -euo pipefail

cd "$(dirname "$0")/../.."

DURATION="${1:-120}"
EVERY="${EVERY:-5}"
REPLICAS="${REPLICAS:-app-1 app-2 app-3}"
POOL_MAX="${POOL_MAX:-30}"

if [[ ! -f .env ]]; then
    echo "error: .env not found. Run docker/secrets/gen-env.sh first." >&2
    exit 1
fi

ADMIN_USER="$(grep -E '^FLASHSEATS_ADMIN_USERNAME=' .env | head -1 | cut -d= -f2-)"
ADMIN_PASS="${FLASHSEATS_ADMIN_PLAINTEXT:-}"

if [[ -z "$ADMIN_PASS" ]]; then
    echo "error: FLASHSEATS_ADMIN_PLAINTEXT is not set." >&2
    echo "       /actuator/metrics is ROLE_ADMIN, and .env holds only the bcrypt" >&2
    echo "       digest. Export the password gen-env.sh printed:" >&2
    echo "         export FLASHSEATS_ADMIN_PLAINTEXT='...'" >&2
    exit 1
fi

# `docker.exe` exists only on Windows. Hardcoding it here — as this script did —
# does not fail loudly on macOS or Linux: read_gauge swallows the error, every
# gauge prints "-", and the run finishes reporting worst-pending 0 and PASS having
# measured NOTHING. That is this script's own header ("an instrument that measures
# the wrong thing is worse than no instrument") coming true in the instrument.
# sold-count.sh and hold-expiry-check.sh already detect it; so does this now.
DOCKER_CLI="docker"
if command -v docker.exe >/dev/null 2>&1; then
    DOCKER_CLI="docker.exe"
fi

# Reads one gauge from one replica, from INSIDE the compose network — these
# ports are not published, and going through nginx would defeat the purpose.
read_gauge() {
    local replica="$1" metric="$2"
    "$DOCKER_CLI" compose exec -T "$replica" sh -c \
        "curl -fsS -u '${ADMIN_USER}:${ADMIN_PASS}' 'http://localhost:8080/actuator/metrics/${metric}'" \
        < /dev/null \
        2>/dev/null | tr ',' '\n' | grep -A1 '"VALUE"' | grep '"value"' \
        | head -1 | sed 's/[^0-9.]//g' || echo ""
}

WORST_PENDING=0
SAW_DRIFT=0
SAW_MISSING=0
SAMPLES=0

# Drift is judged on CONSECUTIVE samples for the SAME replica, never on one.
#
# ADR-046 is explicit: alarm on *sustained* non-zero, because Redis and
# PostgreSQL are not read in one snapshot — a hold created between the two reads
# shows as a momentary gap that is not a defect. Failing on a single sample made
# this instrument contradict the ADR it cites, and in the Pass 9 drill it called
# a correctness failure on one sample out of sixty while sold-count.sh — the
# authority, which reads the ledger — reported `sold + held + redis == capacity`
# on every tier. An instrument that measures the wrong thing is worse than no
# instrument, because its answer is specific (ADR-047).
# "Sustained" means the SAME replica drifted on two CONSECUTIVE sweeps, so all
# this needs to remember is which replicas drifted on the previous one. A space-
# delimited list says that directly, and — unlike the associative array this
# replaced — runs on the bash macOS actually ships. `declare -A` is bash 4; macOS
# is still on 3.2, so the instrument this drill depends on exited immediately with
# `declare: -A: invalid option` on any stock Mac.
#
# And "consecutive" is measured in COMPUTATIONS, not in samples. The gauge is
# recomputed every flashseats.order.drift-interval-ms (60 s by default), while
# this script reaches a replica every 5-25 s depending on load. Two samples inside
# one interval read the SAME computation twice. In Pass 13's 2,000-VU run that
# reported a sustained drift of 13 on app-3 from two reads 24 s apart. The next
# computation read 0.0, and sold-count.sh found the ledger exact on every tier.
# So a replica's drift is sustained only when it is still non-zero at least one
# drift interval after it was first seen, with no zero reading in between.
DRIFT_INTERVAL="${DRIFT_INTERVAL_SECONDS:-60}"
DRIFT_SINCE=""          # space-delimited "replica:epoch" -- bash 3.2 has no maps
SUSTAINED_DRIFT=0
TRANSIENT_DRIFT=0

printf 'Sampling %s every %ss for %ss. Pool maximum is %s per replica.\n\n' \
    "$REPLICAS" "$EVERY" "$DURATION" "$POOL_MAX"
printf '%-9s %-8s %9s %9s %8s %8s\n' \
    'time' 'replica' 'pending' 'active' 'drift' 'missing'
printf '%s\n' '--------- -------- --------- --------- -------- --------'

DEADLINE=$(( $(date +%s) + DURATION ))
while [[ $(date +%s) -lt $DEADLINE ]]; do
    for replica in $REPLICAS; do
        PENDING="$(read_gauge "$replica" hikaricp.connections.pending)"
        ACTIVE="$(read_gauge  "$replica" hikaricp.connections.active)"
        DRIFT="$(read_gauge   "$replica" flashseats.stock.drift)"
        MISSING="$(read_gauge "$replica" flashseats.stock.counters.missing)"

        # A replica that is down or still starting reads empty. Say so rather
        # than printing 0, which would look like a healthy sample.
        [[ -z "$PENDING" ]] && PENDING="-"
        [[ -z "$ACTIVE"  ]] && ACTIVE="-"
        [[ -z "$DRIFT"   ]] && DRIFT="-"
        [[ -z "$MISSING" ]] && MISSING="-"

        FLAG=""
        if [[ "$PENDING" != "-" ]]; then
            PENDING_INT="${PENDING%%.*}"
            [[ "$PENDING_INT" -gt "$WORST_PENDING" ]] && WORST_PENDING="$PENDING_INT"
            [[ "$PENDING_INT" -gt 0 ]] && FLAG="  <-- queuing on the pool"
        fi
        if [[ "$DRIFT" != "-" && "${DRIFT%%.*}" -ne 0 ]]; then
            SAW_DRIFT=1
            NOW="$(date +%s)"
            SINCE=""
            for entry in $DRIFT_SINCE; do
                [[ "${entry%%:*}" == "$replica" ]] && SINCE="${entry#*:}"
            done
            if [[ -z "$SINCE" ]]; then
                DRIFT_SINCE="$DRIFT_SINCE $replica:$NOW"
                TRANSIENT_DRIFT=$((TRANSIENT_DRIFT + 1))
                FLAG="  <-- drift on one computation; transient unless the next one repeats"
            elif [[ $((NOW - SINCE)) -ge "$DRIFT_INTERVAL" ]]; then
                SUSTAINED_DRIFT=1
                FLAG="  <-- DRIFT, SUSTAINED across computations — a correctness failure"
            else
                FLAG="  <-- same drift computation as before ($((NOW - SINCE))s < ${DRIFT_INTERVAL}s); not a second reading"
            fi
        elif [[ "$DRIFT" != "-" ]]; then
            # A zero reading clears it. An unreadable one ("-") proves nothing either way.
            KEPT=""
            for entry in $DRIFT_SINCE; do
                [[ "${entry%%:*}" != "$replica" ]] && KEPT="$KEPT $entry"
            done
            DRIFT_SINCE="$KEPT"
        fi
        [[ "$MISSING" != "-" && "${MISSING%%.*}" -ne 0 ]] && SAW_MISSING=1

        printf '%-9s %-8s %9s %9s %8s %8s%s\n' \
            "$(date +%H:%M:%S)" "$replica" "$PENDING" "$ACTIVE" "$DRIFT" "$MISSING" "$FLAG"
        SAMPLES=$((SAMPLES + 1))
    done
    sleep "$EVERY"
done

echo
echo "--------------------------------------------------------------------"
echo "  ${SAMPLES} samples across ${REPLICAS// /, }"
echo "  worst hikaricp_connections_pending: ${WORST_PENDING}"
echo

if [[ "$SAW_MISSING" -eq 1 ]]; then
    echo "  FAIL  A tier had no live counter during the run. Every hold against it"
    echo "        answered 503 INVENTORY_UNAVAILABLE (ADR-004). This is not a load"
    echo "        result — rebuild the event and re-run."
    exit 1
fi

if [[ "$SUSTAINED_DRIFT" -eq 1 ]]; then
    echo "  FAIL  flashseats_stock_drift was non-zero on one replica for at least"
    echo "        ${DRIFT_INTERVAL}s -- two separate computations, not one read twice."
    echo "        Invariant 1 is broken: confirmed + held + remaining no longer equals"
    echo "        capacity. Read this as a correctness failure, not a"
    echo "        capacity one (ADR-046)."
    exit 1
fi

if [[ "$SAW_DRIFT" -eq 1 ]]; then
    echo "  NOTE  flashseats_stock_drift was non-zero on ${TRANSIENT_DRIFT} isolated sample(s) and"
    echo "        zero on the next read of that replica. That is the measurement's own"
    echo "        artefact, not a defect: Redis and PostgreSQL are not read in one"
    echo "        snapshot, so a hold created between them shows as a momentary gap"
    echo "        (ADR-046). Confirm with docker/scripts/sold-count.sh, which reads the"
    echo "        ledger and is the authority."
    echo
fi

if [[ "$WORST_PENDING" -gt 0 ]]; then
    echo "  ADR-049 CONFIRMED, with a number."
    echo
    echo "  Requests queued on HikariCP while ${REPLICAS// /, } promoted from several"
    echo "  sales at once. Nothing errored — that is the point, and it is why this"
    echo "  script exists rather than the load harness answering it."
    echo
    echo "  The fix is a cluster-wide admission budget spent across events, with the"
    echo "  per-event batch kept as a secondary cap. Enlarging the pool is the wrong"
    echo "  lever: it moves the queue OUT of the waiting room, where it is visible,"
    echo "  ordered and fair, and INTO HikariCP, where it is none of those."
    exit 2
fi

echo "  No pool pressure observed at this VU count and this E."
echo
echo "  Read that narrowly. It means the budget was not exceeded HERE — not that"
echo "  ADR-049's arithmetic is wrong. Scale VUS up, or EVENTS up, until it is,"
echo "  and record the point at which it turns over. A drill that only ever"
echo "  passes has not found the edge, it has just not looked far enough."
