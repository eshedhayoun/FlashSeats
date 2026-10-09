#!/usr/bin/env bash
#
# FlashSeats — one-command evaluator demo.
#
#   docker/scripts/professor-demo.sh
#   docker/scripts/professor-demo.sh --reset
#
# The evaluator needs Docker and a POSIX shell (Git Bash on Windows). Java,
# Maven, Node and local database clients are not required: the application,
# database, Redis, RabbitMQ, Mailpit and nginx all run in Compose.

set -euo pipefail

cd "$(dirname "$0")/../.."

# A fresh clone has no .env yet. Reading it unguarded made grep fail, and under
# `set -euo pipefail` that ended this script on its first line: exit 2, no output.
if [[ -z "${HTTP_PORT:-}" && -f .env ]]; then
    HTTP_PORT="$(grep -E '^HTTP_PORT=' .env | head -1 | cut -d= -f2-)" || HTTP_PORT=""
fi
HTTP_PORT="${HTTP_PORT:-8080}"
export HTTP_PORT

RESET=0
for arg in "$@"; do
    case "$arg" in
        --reset) RESET=1 ;;
        -h|--help)
            sed -n '2,10p' "$0"
            exit 0
            ;;
        *)
            echo "error: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "error: '$1' is required. Install Docker Desktop and Git for Windows, then run this from Git Bash." >&2
        exit 1
    }
}

need docker
need curl
need openssl

docker compose version >/dev/null 2>&1 || {
    echo "error: Docker Compose is unavailable. Start Docker Desktop and ensure 'docker compose version' works." >&2
    exit 1
}

if ! docker info >/dev/null 2>&1; then
    echo "error: Docker Desktop is not running." >&2
    exit 1
fi

if (( RESET )); then
    echo "Resetting the demo database and Redis volumes..."
    docker compose --profile cluster down -v
fi

password_file="$(mktemp)"
cleanup() {
    rm -f "$password_file"
}
trap cleanup EXIT

if [[ ! -f .env ]]; then
    echo "Creating local secrets..."
    bash docker/secrets/gen-env.sh | tee "$password_file"
else
    stored_password="$(grep -E '^FLASHSEATS_ADMIN_PASSWORD=' .env | head -1 | cut -d= -f2- || true)"
    if [[ "$stored_password" == '{noop}admin' || "$stored_password" == 'admin' ]]; then
        echo "Refreshing local secrets..."
        bash docker/secrets/gen-env.sh | tee "$password_file"
    fi
fi

# gen-env.sh prints the password after "  #    ". This took substr($0, 9), one column
# too far: it dropped the first character, and every operator call then answered 401.
if [[ -z "${FLASHSEATS_ADMIN_PLAINTEXT:-}" ]]; then
    FLASHSEATS_ADMIN_PLAINTEXT="$(
        awk '/^  #    / { sub(/^  #    /, ""); print; exit }' "$password_file"
    )"
    export FLASHSEATS_ADMIN_PLAINTEXT
fi

if [[ -z "${FLASHSEATS_ADMIN_PLAINTEXT:-}" ]]; then
    echo "Generating a fresh local admin password for this demo run..."
    tmp_env="$(mktemp)"
    cp .env "$tmp_env"
    trap 'rm -f "$password_file" "$tmp_env"' EXIT
    awk '
        /^FLASHSEATS_ADMIN_PASSWORD=/ {
            print "FLASHSEATS_ADMIN_PASSWORD={noop}admin"
            next
        }
        { print }
    ' "$tmp_env" > .env
    bash docker/secrets/gen-env.sh | tee "$password_file"
    FLASHSEATS_ADMIN_PLAINTEXT="$(
        awk '/^  #    / { sub(/^  #    /, ""); print; exit }' "$password_file"
    )"
    export FLASHSEATS_ADMIN_PLAINTEXT
    rm -f "$tmp_env"
fi

echo "Building and starting the evaluator stack..."
docker compose --profile cluster up -d --build

echo "Waiting for the demo to become healthy..."
for _ in $(seq 1 90); do
    if curl -fsS "http://localhost:${HTTP_PORT}/actuator/health" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

if ! curl -fsS "http://localhost:${HTTP_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "error: the stack did not become healthy within 180 seconds." >&2
    echo "Inspect the startup log with: docker compose --profile cluster logs app-1" >&2
    exit 1
fi

echo "Seeding the Coldplay and Arctic Monkeys demonstration sales..."
bash docker/seed/seed-demo.sh

cat <<EOF

FlashSeats is ready:
  Demo:    http://localhost:${HTTP_PORT}
  Mailpit: http://localhost:8025
  RabbitMQ: http://localhost:15672

The browser demo is the packaged React client and uses the in-process payment
stub, so no Node.js, Stripe account or API key is needed. Use pm_card_declined
to demonstrate a declined payment that keeps the seats, or the normal success
path to receive a PDF ticket.

To stop without deleting data:
  docker compose --profile cluster down

To start clean next time:
  docker/scripts/professor-demo.sh --reset
EOF