#!/usr/bin/env bash
# Stage 2's sandbox suite (wiki: staged-rollout-stage-2, package 9): REALLY changes the sandboxes through the sync engine
# and checks each tool (create, add, gate, Vaultwarden accept and confirm, sign, remove, close, clean up), against the
# local sandbox's database. Run scripts/sandbox-setup.sh once first.
#
#   scripts/sandbox-e2e.sh [--keep] [--sign] [--fixture <file>] [env files…]
#
# Env files default to deploy/sandbox/sandbox.env (local Vaultwarden and Documenso). Add the shared sandboxes'
# file (Slack, GitHub, Google, from the vault) to include them; later files win. Each tool takes part when its
# PORTAL_ADAPTERS_<TOOL>_WRITE=true.
#   --keep     leave the created channel, group, team and collection in the tools
#   --sign     wait up to 15 minutes for you to sign the test agreement by hand (Mailpit: http://localhost:8025);
#              without it the suite signs in the local Documenso itself (and records the signature for any other)
#   --fixture  the people (default deploy/sandbox/sandbox-fixture.yml)
set -euo pipefail
cd "$(dirname "$0")/.."
extra=""; sign="--portal.sandbox-e2e.sign-locally=true"; fixture=deploy/sandbox/sandbox-fixture.yml; files=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) extra="$extra --portal.sandbox-e2e.keep=true" ;;
    --sign) extra="$extra --portal.sandbox-e2e.wait-for-signature=PT15M"; sign="" ;;
    --fixture) fixture="$2"; shift ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) files+=("$1") ;;
  esac
  shift
done
[[ ${#files[@]} -gt 0 ]] || files=(deploy/sandbox/sandbox.env)
env_file=$(mktemp --suffix=.env); trap 'rm -f "$env_file"' EXIT
cat "${files[@]}" > "$env_file"
docker compose -f deploy/sandbox/compose.yml up -d --wait >/dev/null 2>&1 || { echo "the local sandbox didn't start: run scripts/sandbox-setup.sh" >&2; exit 1; }

./gradlew bootRun -PenvFile="$env_file" --console=plain --args="--spring.profiles.active=sandbox-e2e \
  --portal.sandbox-e2e.reset-database=true '--portal.sandbox-e2e.fixture=$PWD/$fixture' \
  --spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5437/portal \
  --spring.datasource.username=portal --spring.datasource.password=portal $sign $extra"
