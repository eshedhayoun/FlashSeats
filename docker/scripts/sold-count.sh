#!/usr/bin/env bash
# ============================================================================
# What was ACTUALLY sold, and does the stock invariant hold?
#
# WHY THIS EXISTS. k6's `tickets sold` is a number the CLIENT observed: it counts
# checkout responses that arrived. k6's default request timeout is 60 s and its
# scenario ends with a graceful ramp-down, so every request still in flight when
# either fires is recorded as a failure and counted as nothing -- while the
# server went on to commit the order.
#
# In the Pass 8 run that gap was 8x. k6 reported 21 tickets across five sales.
# PostgreSQL held 109 confirmed orders and 162 seats. The harness was not lying;
# it was answering a different question, and the summary presented the answer as
# if it were the sale.
#
# So the authority is the ledger, and this reads it:
#
#     sold  = CONFIRMED order_items for the tier
#     held  = ACTIVE ticket_holds for the tier
#     redis = catalog:stock:{e}:{t}, the live counter
#
#     sold + held + redis == total_capacity        <- invariant 1, always
#
# Oversell is `sold + held > capacity` and is the one failure this system never
# accepts. Under-counting (`sum < capacity`) is invisible seats a rebuild
# recovers -- the deliberate direction (ADR-046).
#
# Usage:  docker/scripts/sold-count.sh [FIRST_ID] [EVENTS]
#         docker/scripts/sold-count.sh            # 9001..9005, the ADR-049 drill
#         docker/scripts/sold-count.sh 9001 1     # the single-sale harness
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.."
REDIS_CLI="./docker/scripts/redis-master-cli.sh"

FIRST_ID="${1:-9001}"
EVENTS="${2:-5}"
LAST_ID=$((FIRST_ID + EVENTS - 1))
DOCKER_CLI="docker"
if command -v docker.exe >/dev/null 2>&1; then
    DOCKER_CLI="docker.exe"
fi

if [[ ! -f .env ]]; then
    echo "error: .env not found. Run docker/secrets/gen-env.sh first." >&2
    exit 1
fi

# grep one value rather than sourcing: the bcrypt digest in .env carries `$$` for
# Compose, and a shell expands `$$` to its own PID (ADR-048).
PG_USER="$(grep -E '^POSTGRES_USER=' .env | head -1 | cut -d= -f2-)"
PG_DB="$(grep -E '^POSTGRES_DB=' .env | head -1 | cut -d= -f2-)"
PG_USER="${PG_USER:-flashseats}"
PG_DB="${PG_DB:-flashseats}"

rows="$("$DOCKER_CLI" compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -At -F'|' -c "
SELECT t.event_id, t.id, t.total_capacity,
       (SELECT coalesce(sum(oi.quantity),0)
          FROM order_items oi JOIN orders o ON o.id = oi.order_id
         WHERE oi.tier_id = t.id AND o.status = 'CONFIRMED'),
       (SELECT coalesce(sum(h.quantity),0)
          FROM ticket_holds h
         WHERE h.tier_id = t.id AND h.status = 'ACTIVE')
  FROM ticket_tiers t
 WHERE t.event_id BETWEEN $FIRST_ID AND $LAST_ID
 ORDER BY t.event_id, t.id;" 2>/dev/null)"

if [[ -z "$rows" ]]; then
    echo "No tiers for events $FIRST_ID..$LAST_ID. Seed first." >&2
    exit 1
fi

printf '\n  %-8s %-7s %6s %6s %6s %7s %6s  %s\n' \
       EVENT TIER CAP SOLD HELD REDIS SUM VERDICT
printf '  %-8s %-7s %6s %6s %6s %7s %6s  %s\n' \
       -------- ------- ------ ------ ------ ------- ------ -------

TOTAL_CAP=0; TOTAL_SOLD=0; OVERSOLD=0; DRIFTED=0; MISSING=0

while IFS='|' read -r event tier cap sold held; do
    [[ -n "$event" ]] || continue
    # `< /dev/null` is load-bearing: docker reads stdin, and without it the first
    # call swallows the rest of the row list and the loop reports one tier.
    redis="$("$REDIS_CLI" --no-raw GET "catalog:stock:$event:$tier" \
                 < /dev/null 2>/dev/null | tr -d '"\r')"

    if [[ "$redis" == "(nil)" || -z "$redis" ]]; then
        # A missing counter is a FAULT, never a zero (ADR-004). Printing 0 here
        # would make an unrecoverable state look like an ordinary sold-out tier.
        printf '  %-8s %-7s %6s %6s %6s %7s %6s  %s\n' \
               "$event" "$tier" "$cap" "$sold" "$held" "-" "-" "NO COUNTER -- rebuild"
        MISSING=$((MISSING + 1))
        continue
    fi

    sum=$((sold + held + redis))
    verdict="ok"
    if (( sold + held > cap )); then
        verdict="*** OVERSOLD ***"; OVERSOLD=$((OVERSOLD + 1))
    elif (( sum != cap )); then
        verdict="drift $((sum - cap))"; DRIFTED=$((DRIFTED + 1))
    fi

    printf '  %-8s %-7s %6s %6s %6s %7s %6s  %s\n' \
           "$event" "$tier" "$cap" "$sold" "$held" "$redis" "$sum" "$verdict"

    TOTAL_CAP=$((TOTAL_CAP + cap)); TOTAL_SOLD=$((TOTAL_SOLD + sold))
done <<< "$rows"

pct=0
(( TOTAL_CAP > 0 )) && pct=$((TOTAL_SOLD * 100 / TOTAL_CAP))
printf '\n  sold %s of %s seats (%s%%) across events %s..%s\n' \
       "$TOTAL_SOLD" "$TOTAL_CAP" "$pct" "$FIRST_ID" "$LAST_ID"

if (( OVERSOLD > 0 )); then
    echo "  OVERSOLD on $OVERSOLD tier(s). This is the one failure nothing recovers."
    exit 2
fi
if (( MISSING > 0 )); then
    echo "  $MISSING tier(s) have no counter: every hold on them answers 503 (ADR-004)."
    exit 1
fi
if (( DRIFTED > 0 )); then
    echo "  $DRIFTED tier(s) drifted. Negative is under-counted and a rebuild recovers it;"
    echo "  re-run to see whether it was the measurement racing live traffic or real."
    exit 1
fi
echo "  No oversell, no drift: sold + held + redis == capacity on every tier."
