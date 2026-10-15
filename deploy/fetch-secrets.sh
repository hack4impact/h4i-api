#!/usr/bin/env bash
# Writes a .env file from one Vaultwarden item (wiki decision 30): each custom field on the item
# becomes NAME="value". Secrets live only in the vault and in this gitignored, owner-only file.
#
# Needs the Bitwarden CLI (`bw`) pointed at vault.hack4impact.org, logged in and unlocked, and jq:
#   bw config server https://vault.hack4impact.org && bw login && export BW_SESSION="$(bw unlock --raw)"
# Usage: deploy/fetch-secrets.sh "<vault item name>" <output file>
set -euo pipefail

item="${1:?usage: fetch-secrets.sh \"<vault item name>\" <output file>}"
out="${2:?usage: fetch-secrets.sh \"<vault item name>\" <output file>}"

command -v bw >/dev/null || { echo "Install the Bitwarden CLI (bw) first." >&2; exit 1; }
command -v jq >/dev/null || { echo "Install jq first." >&2; exit 1; }
[[ -n "${BW_SESSION:-}" ]] || { echo 'Unlock the vault first: export BW_SESSION="$(bw unlock --raw)"' >&2; exit 1; }

bw sync >/dev/null
umask 077
bw get item "$item" | jq -r '.fields // [] | .[] | "\(.name)=\(.value | @json)"' > "$out"
echo "Wrote $(wc -l < "$out") variables from \"$item\" to $out"
