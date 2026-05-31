#!/usr/bin/env bash
# update-snapshot.sh — refresh vyos/snapshot-config.boot from the live router, SANITIZED.
#
# The router's /config/config.boot contains secrets in cleartext (PKI private keys, the
# login password hash, any pre-shared secrets). This script fetches it and redacts those
# before writing the repo copy, so the committed snapshot keeps full structure + public
# certs but carries no usable secret.
#   (The OpenVPN LDAP bind password is NOT in config.boot — it lives in /config/auth/ on the
#    box and is never snapshotted.)
#
# Usage:
#   ./vyos/update-snapshot.sh
#   ROUTER=vyos@88.200.24.237 SSH_KEY=~/.ssh/id_ed25519 ./vyos/update-snapshot.sh
set -euo pipefail

ROUTER="${ROUTER:-vyos@10.7.99.1}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/vyos}"   # key used to reach the router; override as needed
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${SCRIPT_DIR}/snapshot-config.boot"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# Use the SSH key if it exists; otherwise fall back to the agent / ssh defaults.
SSH_OPTS=()
[ -f "$SSH_KEY" ] && SSH_OPTS=(-i "$SSH_KEY")

echo "Fetching config.boot from ${ROUTER} (key: ${SSH_KEY}) ..."
scp "${SSH_OPTS[@]}" "${ROUTER}:/config/config.boot" "$TMP"

# Redact secret-bearing leaves -> "<REDACTED>". `^\s*key` is anchored so it only hits the
# pki `private { key "..." }` leaves (and SSH authorized public-keys, harmless to redact).
sed -E \
  -e 's/^([[:space:]]*key) "[^"]*"/\1 "<REDACTED>"/' \
  -e 's/(encrypted-password) "[^"]*"/\1 "<REDACTED>"/' \
  -e 's/(plaintext-password) "[^"]*"/\1 "<REDACTED>"/' \
  -e 's/(pre-shared-secret) "[^"]*"/\1 "<REDACTED>"/' \
  "$TMP" > "$OUT"

# Fail loudly if any of those leaves still carries a real value (not "<REDACTED>").
if grep -qE '(^[[:space:]]*key|encrypted-password|plaintext-password|pre-shared-secret) "[^<]' "$OUT"; then
  echo "ERROR: an unredacted secret slipped through — NOT writing a leaky snapshot." >&2
  echo "       offending lines:" >&2
  grep -nE '(^[[:space:]]*key|encrypted-password|plaintext-password|pre-shared-secret) "[^<]' "$OUT" >&2
  rm -f "$OUT"
  exit 1
fi

echo "Wrote sanitized snapshot -> ${OUT}"
echo "Review before committing:  git diff -- vyos/snapshot-config.boot"
