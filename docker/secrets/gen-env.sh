#!/usr/bin/env bash
#
# FlashSeats — fill .env with real secrets.
#
#   docker/secrets/gen-env.sh
#
# SecretsGuard is @Profile("!dev & !test"), so the `docker` profile that the
# cluster runs REFUSES TO START while any secret is still `dev-only-change-me`
# or the admin password is still `admin` (ADR-039). That is deliberate — a
# default secret breaks nothing visible, so a startup that stops is the only
# signal that cannot be missed — but it does mean `--profile cluster` cannot
# come up from a fresh checkout until this has run.
#
# Idempotent, and deliberately conservative: a value that is already non-default
# is left ALONE. Rotating a secret invalidates every live token it signs, so a
# script that regenerated on every run would log out an entire sale the next
# time somebody typed it out of habit.

set -euo pipefail

cd "$(dirname "$0")/../.."

ENV_FILE=".env"
EXAMPLE_FILE=".env.example"

if [[ ! -f "$EXAMPLE_FILE" ]]; then
    echo "error: $EXAMPLE_FILE not found; run this from the repository." >&2
    exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
    cp "$EXAMPLE_FILE" "$ENV_FILE"
    echo "created $ENV_FILE from $EXAMPLE_FILE"
fi

# Replaces KEY=<default> with KEY=<fresh>, and only that. `sed -i` differs
# between BSD and GNU, so write to a temp file and move it back instead.
replace_if_default() {
    local key="$1" default="$2" fresh="$3"
    local current

    # Presence is tested separately from emptiness: FLASHSEATS_TRUSTED_PROXIES
    # is legitimately empty, so `-z "$current"` cannot stand in for "missing".
    if ! grep -qE "^${key}=" "$ENV_FILE"; then
        echo "  ${key}  — not present in $ENV_FILE, skipping"
        return
    fi
    current="$(grep -E "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2-)"

    if [[ "$current" != "$default" ]]; then
        echo "  ${key}  — already set, left alone"
        return
    fi

    awk -v k="$key" -v v="$fresh" \
        'BEGIN { FS = "="; OFS = "=" }
         $1 == k { print k, v; next }
         { print }' "$ENV_FILE" > "$ENV_FILE.tmp"
    mv "$ENV_FILE.tmp" "$ENV_FILE"
    echo "  ${key}  — generated"
}

echo "Filling $ENV_FILE:"

# One key per token domain. They are separate on purpose: a single shared value
# meant one leak forged all three, and rotating any rotated the others (ADR-039).
for key in FLASHSEATS_SESSION_SECRET FLASHSEATS_QUEUE_PASS_SECRET FLASHSEATS_RECEIPT_SECRET; do
    replace_if_default "$key" "dev-only-change-me" "$(openssl rand -base64 48 | tr -d '\n')"
done

# The admin password is STORED HASHED, so what goes in .env is a bcrypt digest
# carrying its own algorithm prefix — SecretsGuard refuses to start on any
# `{noop}` value outside dev/test, which is every plaintext password rather than
# one known string.
#
# htpasswd rather than a hand-rolled hash, and via Docker rather than assuming
# it is installed: macOS does not ship apache2-utils, and Docker is already a
# hard requirement for everything else in this workflow. The empty username and
# the `tr` leave just the digest.
if grep -qE '^FLASHSEATS_ADMIN_PASSWORD=\{noop\}|^FLASHSEATS_ADMIN_PASSWORD=admin$' "$ENV_FILE"; then
    ADMIN_PLAINTEXT="$(openssl rand -hex 24)"
    ADMIN_HASH="$(docker run --rm httpd:alpine \
        htpasswd -bnBC 12 "" "$ADMIN_PLAINTEXT" 2>/dev/null | tr -d ':\n')"

    if [[ -z "$ADMIN_HASH" ]]; then
        echo "  FLASHSEATS_ADMIN_PASSWORD  — FAILED to hash (is Docker running?)" >&2
        exit 1
    fi

    awk -v v="{bcrypt}${ADMIN_HASH}" \
        'BEGIN { FS = "="; OFS = "=" }
         $1 == "FLASHSEATS_ADMIN_PASSWORD" { print $1, v; next }
         { print }' "$ENV_FILE" > "$ENV_FILE.tmp"
    mv "$ENV_FILE.tmp" "$ENV_FILE"

    echo "  FLASHSEATS_ADMIN_PASSWORD  — generated and hashed"
    echo
    echo "  ############################################################"
    echo "  #  OPERATOR PASSWORD — shown once, not recoverable from .env"
    echo "  #"
    echo "  #    ${ADMIN_PLAINTEXT}"
    echo "  #"
    echo "  #  Store it somewhere before you close this terminal."
    echo "  ############################################################"
    echo
else
    echo "  FLASHSEATS_ADMIN_PASSWORD  — already hashed, left alone"
fi

# Not a secret, but the cluster is wrong without it and wrong quietly: the rate
# limiter ignores X-Forwarded-For from an untrusted peer, so every buyer shares
# nginx's one IP bucket. Pinned to nginx's static compose address (ADR-047).
replace_if_default FLASHSEATS_TRUSTED_PROXIES "" "172.28.0.10"

echo "Done. Operator username: $(grep -E '^FLASHSEATS_ADMIN_USERNAME=' "$ENV_FILE" | cut -d= -f2-)"
echo
echo "The password in .env is a bcrypt digest and CANNOT be used to log in. Scripts"
echo "that call the admin API need the plaintext, so export it for the session:"
echo "  export FLASHSEATS_ADMIN_PLAINTEXT='<the password above>'"
