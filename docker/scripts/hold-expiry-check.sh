#!/usr/bin/env bash
#
# FlashSeats — prove the hold expiry listener across replicas (ADR-003).
#
#   docker compose --profile cluster up -d --build
#   docker/seed/seed.sh
#   docker/scripts/hold-expiry-check.sh
#
# THE CLAIM THIS SETTLES. `hold:{token}` expiring is broadcast pub/sub: every
# replica subscribes to __keyevent@0__:expired, so all three receive the same
# event and all three try to reclaim the same hold. Nothing coordinates them.
# What makes that safe is the settle-once claim — one conditional UPDATE with
# `AND status = 'ACTIVE'`, which exactly one replica wins — and a listener that
# skipped it and restored stock directly would return the seats THREE TIMES.
#
# That is the second entry in CLAUDE.md's table of traps, and it is invisible on
# one instance: a single replica restores once whether or not the claim is there.
# So the check is a cluster check or it is nothing.
#
# It also measures LATENCY, because that is the listener's entire reason to
# exist. HoldReconciliationSweeper already reclaims every expired hold on a
# 10-second cadence and is untouched by any of this; if the seats come back in
# ten seconds we have proven only that the sweeper still works.
#
# PASS requires BOTH:
#   * stock returns to exactly its starting value — never more, and
#   * it returns faster than the sweeper's interval.

set -euo pipefail

cd "$(dirname "$0")/../.."

EVENT_ID="${EVENT_ID:-9001}"
TIER_ID="${TIER_ID:-9001}"
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"
QUANTITY="${QUANTITY:-4}"
# flashseats.hold.sweeper-interval-ms is 10000. Anything at or past that is
# indistinguishable from the sweeper having done the work.
SWEEPER_INTERVAL_MS="${SWEEPER_INTERVAL_MS:-10000}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

POSTGRES_USER_VALUE="$(grep -E '^POSTGRES_USER=' .env | head -1 | cut -d= -f2-)"
POSTGRES_DB_VALUE="$(grep -E '^POSTGRES_DB=' .env | head -1 | cut -d= -f2-)"

psql_q() {
    docker compose exec -T postgres psql -qtAX \
        -U "${POSTGRES_USER_VALUE:-flashseats}" -d "${POSTGRES_DB_VALUE:-flashseats}" -c "$1"
}
counter() { docker compose exec -T redis redis-cli GET "catalog:stock:${EVENT_ID}:${TIER_ID}" | tr -d '\r'; }

echo "Hold expiry listener — ${BASE_URL}, event ${EVENT_ID}"
echo

WINDOW="$(curl -fsS "${BASE_URL}/api/v1/events/${EVENT_ID}" \
    | tr ',' '\n' | grep '"windowStatus"' | cut -d'"' -f4 || true)"
if [[ "$WINDOW" != "OPEN" ]]; then
    echo "error: event ${EVENT_ID} is ${WINDOW:-unreachable}, not OPEN. Run docker/seed/seed.sh." >&2
    exit 1
fi

REPLICAS="$(docker compose --profile cluster ps --format '{{.Service}}' | grep -c '^app-' || true)"
echo "  replicas subscribed to __keyevent@0__:expired: ${REPLICAS}"
if [[ "$REPLICAS" -lt 2 ]]; then
    echo "  INCONCLUSIVE  this check needs more than one replica to mean anything." >&2
    exit 2
fi

# --- take a hold, the full ADR-020 way --------------------------------------
JAR="${WORK}/cookies"
curl -fsS -c "$JAR" "${BASE_URL}/api/v1/events/${EVENT_ID}" >/dev/null
curl -fsS -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
    -d "{\"eventId\":${EVENT_ID},\"recaptchaToken\":\"expiry-check\"}" \
    "${BASE_URL}/api/v1/queue/join" >/dev/null

PASS=""
for _ in $(seq 1 30); do
    PASS="$(curl -fsS -b "$JAR" "${BASE_URL}/api/v1/queue/status?eventId=${EVENT_ID}" \
        | tr ',' '\n' | grep '"passToken"' | cut -d'"' -f4 || true)"
    [[ -n "$PASS" ]] && break
    sleep 1
done
[[ -n "$PASS" ]] || { echo "error: never promoted out of the queue." >&2; exit 1; }

ADMISSION="$(curl -fsS -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
    -H "X-Queue-Pass-Token: ${PASS}" -d "{\"eventId\":${EVENT_ID}}" \
    "${BASE_URL}/api/v1/queue/admit" | tr ',' '\n' | grep '"admissionToken"' | cut -d'"' -f4)"

BEFORE="$(counter)"

HOLD_TOKEN="$(curl -fsS -b "$JAR" -H 'Content-Type: application/json' \
    -H "X-Admission-Token: ${ADMISSION}" \
    -d "{\"eventId\":${EVENT_ID},\"tierId\":${TIER_ID},\"quantity\":${QUANTITY}}" \
    "${BASE_URL}/api/v1/holds" | tr ',' '\n' | grep '"holdToken"' | cut -d'"' -f4)"

HELD="$(counter)"
echo "  held ${QUANTITY}: counter ${BEFORE} -> ${HELD}"

if ! docker compose exec -T redis redis-cli EXISTS "hold:${HOLD_TOKEN}" | grep -q 1; then
    echo "  FAIL  no hold:${HOLD_TOKEN} timer was armed. Is the listener wired at all?" >&2
    exit 1
fi
echo "  timer armed, TTL $(docker compose exec -T redis redis-cli TTL "hold:${HOLD_TOKEN}" | tr -d '\r')s"

# --- expire it, in both places ----------------------------------------------
# The row first, then the key. reclaimExpired re-reads the row and settles only
# what the sweeper would have settled anyway, so a key expiring against a row
# that is still live is correctly a no-op — the guard that stops a grace-extended
# hold being torn up mid-payment. Expiring both is what makes this the real case
# rather than that one.
echo
echo "  expiring the hold row, then firing the timer..."
psql_q "UPDATE ticket_holds SET expires_at = now() - interval '5 seconds' WHERE hold_token = '${HOLD_TOKEN}';" >/dev/null
START_MS=$(perl -MTime::HiRes=time -e 'print int(time*1000)')
docker compose exec -T redis redis-cli PEXPIRE "hold:${HOLD_TOKEN}" 1 >/dev/null

# --- watch, and keep watching past the first restore ------------------------
# Polling rather than sleeping: the moment it returns is the measurement, and
# overshoot beyond BEFORE is the triple-restore this whole check exists to catch.
RESTORED_MS=""
PEAK="$HELD"
for _ in $(seq 1 200); do
    NOW="$(counter)"
    [[ "$NOW" -gt "$PEAK" ]] && PEAK="$NOW"
    if [[ -z "$RESTORED_MS" && "$NOW" -ge "$BEFORE" ]]; then
        RESTORED_MS=$(perl -MTime::HiRes=time -e 'print int(time*1000)')
    fi
    # Keep polling ~3s past the restore: a second or third replica returning the
    # same seats would show up here and nowhere else.
    if [[ -n "$RESTORED_MS" ]] && (( $(perl -MTime::HiRes=time -e 'print int(time*1000)') - RESTORED_MS > 3000 )); then
        break
    fi
    sleep 0.1
done

FINAL="$(counter)"
STATUS="$(psql_q "SELECT status FROM ticket_holds WHERE hold_token = '${HOLD_TOKEN}';" | tr -d ' ')"

echo
echo "=========================================================="
echo "  Hold expiry listener (ADR-003)"
echo "=========================================================="
echo "  Replicas            ${REPLICAS}"
echo "  Counter before      ${BEFORE}"
echo "  After the hold      ${HELD}"
echo "  Final               ${FINAL}"
echo "  Peak observed       ${PEAK}"
echo "  Hold status         ${STATUS}"
if [[ -n "$RESTORED_MS" ]]; then
    echo "  Restored in         $((RESTORED_MS - START_MS)) ms   (sweeper interval ${SWEEPER_INTERVAL_MS} ms)"
else
    echo "  Restored in         never"
fi
echo "----------------------------------------------------------"

if [[ "$PEAK" -gt "$BEFORE" || "$FINAL" -ne "$BEFORE" ]]; then
    echo "  FAIL  the seats came back more than once."
    echo "        ${REPLICAS} replicas each restored the same hold: the listener is"
    echo "        restoring stock directly instead of going through the"
    echo "        settle-once claim in HoldService.reclaimExpired."
    echo "=========================================================="
    exit 1
fi

if [[ -z "$RESTORED_MS" ]]; then
    echo "  FAIL  the seats never came back."
    echo "=========================================================="
    exit 1
fi

ELAPSED=$((RESTORED_MS - START_MS))
if [[ "$ELAPSED" -ge "$SWEEPER_INTERVAL_MS" ]]; then
    echo "  INCONCLUSIVE  restored once, correctly — but no faster than the"
    echo "                sweeper, so this proves nothing about the listener."
    echo "                Is notify-keyspace-events Ex set on the server?"
    echo "=========================================================="
    exit 2
fi

echo "  PASS  restored exactly once, in ${ELAPSED} ms, across ${REPLICAS} replicas"
echo "=========================================================="
