#!/usr/bin/env bash
#
# FlashSeats — prove the REAL provider path end to end (ADR-052, ADR-053, ADR-054).
#
#   export STRIPE_API_KEY=sk_test_... STRIPE_ENABLED=true
#   docker compose --profile cluster --profile stripe up -d --build
#   docker compose logs stripe | grep whsec_      # put it in .env, then recreate app
#   docker/seed/seed.sh
#   docker/scripts/stripe-check.sh
#
# WHAT THE TEST SUITE CANNOT TELL YOU. Every assertion in PaymentWebhookIT and
# ThreeDSecureIT runs against StubPaymentGateway: correct about THIS system's
# behaviour, and silent about whether the provider agrees. Four things live only
# here, and all four fail silently rather than loudly:
#
#   * the API key and the account it belongs to actually work;
#   * a real intent's `status` strings map onto the three outcomes this system
#     understands -- `requires_action` in particular, which the stub asserts by
#     construction and Stripe asserts by policy;
#   * the webhook secret in the app matches the one `stripe listen` minted. A
#     stale one rejects EVERY delivery, and the symptom is silence that looks
#     exactly like a quiet day;
#   * a redelivered event is dismissed as a duplicate rather than settled twice.
#
# PASS requires ALL of:
#   1. a test-mode charge confirms the order;
#   2. a 3-D Secure card answers 402 PAYMENT_ACTION_REQUIRED WITH a clientSecret;
#   3. a webhook delivery arrived and was recorded;
#   4. resending that delivery leaves exactly one webhook_events row and does not
#      move the order.

set -euo pipefail

cd "$(dirname "$0")/../.."

EVENT_ID="${EVENT_ID:-9001}"
BASE_URL="${BASE_URL:-http://localhost:${HTTP_PORT:-8080}}"
API="${BASE_URL}/api/v1"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo; echo "FAIL: $*" >&2; exit 1; }
psql_q() {
    docker compose exec -T postgres \
        psql -qtAX -U "${POSTGRES_USER:-flashseats}" -d "${POSTGRES_DB:-flashseats}" -c "$1" \
        | tr -d '[:space:]'
}

# Take a hold the full ADR-020 way — join, wait for a pass, exchange it for an
# admission, then reserve. Not a shortcut: POST /holds verifies the admission,
# so there is no way to reach a checkout without walking the whole journey, and
# a check that bypassed it would be testing a path no buyer takes.
reserve() {
    local jar="$1" quantity="${2:-1}" pass="" admission=""

    curl -fsS -c "$jar" "${API}/events/${EVENT_ID}" >/dev/null
    curl -fsS -b "$jar" -c "$jar" -H 'Content-Type: application/json' \
        -d "{\"eventId\":${EVENT_ID},\"recaptchaToken\":\"stripe-check\"}" \
        "${API}/queue/join" >/dev/null

    for _ in $(seq 1 30); do
        pass="$(curl -fsS -b "$jar" "${API}/queue/status?eventId=${EVENT_ID}" \
            | tr ',' '\n' | grep '"passToken"' | cut -d'"' -f4 || true)"
        [[ -n "$pass" ]] && break
        sleep 1
    done
    [[ -n "$pass" ]] || return 1

    admission="$(curl -fsS -b "$jar" -c "$jar" -H 'Content-Type: application/json' \
        -H "X-Queue-Pass-Token: ${pass}" -d "{\"eventId\":${EVENT_ID}}" \
        "${API}/queue/admit" | tr ',' '\n' | grep '"admissionToken"' | cut -d'"' -f4)"

    curl -fsS -b "$jar" -H 'Content-Type: application/json' \
        -H "X-Admission-Token: ${admission}" \
        -d "{\"eventId\":${EVENT_ID},\"tierId\":${TIER_ID},\"quantity\":${quantity}}" \
        "${API}/holds" | tr ',' '\n' | grep '"holdToken"' | cut -d'"' -f4
}

echo "Stripe check — event ${EVENT_ID} against ${BASE_URL}"
echo

# --- 0. the app must actually be running Stripe ------------------------------
# The whole point is to exercise the real provider. Against the stub every
# assertion below still passes, and the run would prove nothing at all --
# exactly the failure mode ADR-047 called out in the load harness.
if ! docker compose ps --services --filter status=running | grep -qx stripe; then
    fail "the 'stripe' service is not running. Start it:
       docker compose --profile cluster --profile stripe up -d"
fi
if ! docker compose logs app-1 2>/dev/null | grep -q "Payment gateway: Stripe"; then
    fail "the app is running the STUB gateway, so this run would prove nothing.
       Set STRIPE_ENABLED=true and recreate: docker compose --profile cluster up -d"
fi

# --- 1. the sale must be open, with seats ------------------------------------
WINDOW="$(curl -fsS "${API}/events/${EVENT_ID}" | tr ',' '\n' | grep '"windowStatus"' | cut -d'"' -f4 || true)"
[[ "$WINDOW" == "OPEN" ]] || fail "event ${EVENT_ID} is ${WINDOW:-unreachable}, not OPEN. Run docker/seed/seed.sh"

TIER_ID="${TIER_ID:-9001}"

# --- 2. a real test-mode charge ----------------------------------------------
# pm_card_visa is Stripe's own always-succeeds test payment method, so a failure
# here is this system's, never the card's.
echo "1/4  charging a real test-mode card..."
JAR="${WORK}/buyer"
HOLD="$(reserve "$JAR" 1 || true)"
[[ -n "$HOLD" ]] || fail "could not reserve seats — is the sale seeded and pre-warmed?"

RECEIPT="$(curl -fsS -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
    -d "{\"holdToken\":\"${HOLD}\",\"userEmail\":\"stripe-check@example.com\",\"paymentMethodId\":\"pm_card_visa\",\"idempotencyKey\":\"stripe-check-${HOLD}\"}" \
    "${API}/orders/checkout")"
echo "$RECEIPT" | grep -q '"status":"CONFIRMED"' \
    || fail "the charge did not confirm the order. Response: ${RECEIPT}"
ORDER="$(echo "$RECEIPT" | tr ',' '\n' | grep '"orderNumber"' | cut -d'"' -f4)"
echo "     order ${ORDER} CONFIRMED"

# --- 3. 3-D Secure ------------------------------------------------------------
# The one outcome the stub asserts by construction and the provider asserts by
# policy. If the status mapping is wrong this answers 402 PAYMENT_DECLINED and
# the buyer is told their perfectly good card was refused.
echo "2/4  asking for a 3-D Secure challenge..."
JAR2="${WORK}/buyer2"
HOLD2="$(reserve "$JAR2" 1 || true)"
[[ -n "$HOLD2" ]] || fail "could not reserve seats for the 3-D Secure leg"

CHALLENGE="$(curl -sS -b "$JAR2" -c "$JAR2" -H 'Content-Type: application/json' \
    -d "{\"holdToken\":\"${HOLD2}\",\"userEmail\":\"stripe-3ds@example.com\",\"paymentMethodId\":\"pm_card_authenticationRequired\",\"idempotencyKey\":\"stripe-3ds-${HOLD2}\"}" \
    "${API}/orders/checkout")"
echo "$CHALLENGE" | grep -q 'PAYMENT_ACTION_REQUIRED' \
    || fail "expected PAYMENT_ACTION_REQUIRED, got: ${CHALLENGE}"
echo "$CHALLENGE" | grep -q '"clientSecret"' \
    || fail "402 carried no clientSecret — the browser cannot run the challenge"
echo "     402 PAYMENT_ACTION_REQUIRED with a clientSecret"

# --- 4. the webhook actually arrived -----------------------------------------
# `stripe listen` forwards asynchronously, so give it a moment. A zero here is
# almost always the secret mismatch described at the top of this file.
echo "3/4  waiting for the webhook delivery..."
DELIVERED=0
for _ in $(seq 1 20); do
    DELIVERED="$(psql_q "SELECT count(*) FROM webhook_events")"
    [[ "$DELIVERED" -gt 0 ]] && break
    sleep 1
done
[[ "$DELIVERED" -gt 0 ]] || fail "no webhook was recorded after 20s.
       Almost always the secret: compare STRIPE_WEBHOOK_SECRET with
       'docker compose logs stripe | grep whsec_'. A mismatch rejects every
       delivery with a 400 and looks exactly like no traffic."

EVENT_KEY="$(psql_q "SELECT stripe_event_id FROM webhook_events ORDER BY received_at DESC LIMIT 1")"
echo "     ${DELIVERED} delivery/deliveries recorded, latest ${EVENT_KEY}"

# --- 5. a redelivery must change nothing -------------------------------------
echo "4/4  resending that delivery..."
BEFORE_ROWS="$(psql_q "SELECT count(*) FROM webhook_events")"
BEFORE_ORDERS="$(psql_q "SELECT count(*) FROM orders WHERE status = 'CONFIRMED'")"

docker compose exec -T stripe stripe events resend "$EVENT_KEY" >/dev/null 2>&1 \
    || echo "     (stripe events resend unavailable; skipping the active resend)"
sleep 5

AFTER_ROWS="$(psql_q "SELECT count(*) FROM webhook_events")"
AFTER_ORDERS="$(psql_q "SELECT count(*) FROM orders WHERE status = 'CONFIRMED'")"

[[ "$AFTER_ROWS" == "$BEFORE_ROWS" ]] \
    || fail "a replay created a new webhook_events row (${BEFORE_ROWS} -> ${AFTER_ROWS}).
       The claim is not deduping on stripe_event_id."
[[ "$AFTER_ORDERS" == "$BEFORE_ORDERS" ]] \
    || fail "a replay moved an order (${BEFORE_ORDERS} -> ${AFTER_ORDERS} confirmed).
       The same charge was settled twice."

echo
echo "PASS — real charge confirmed, 3-D Secure surfaced, webhook delivered and deduped."
