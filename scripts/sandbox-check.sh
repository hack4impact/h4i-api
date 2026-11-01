#!/usr/bin/env bash
# Stage 2 package 9: loads a fixture into the local sandbox's database, dry-runs against the sandboxes, and compares the
# planned diff with the fixture's `expect` list. Nothing in the tools is changed. Run scripts/sandbox-setup.sh once first.
#
#   scripts/sandbox-check.sh <fixture> [env files…]      (env files default to deploy/sandbox/sandbox.env)
#   scripts/sandbox-check.sh --discover [env files…]     prints what the adapters see, to help write a fixture
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -gt 0 ]] || { echo "usage: scripts/sandbox-check.sh <fixture>|--discover [env files…]" >&2; exit 2; }
if [[ "$1" == "--discover" ]]; then args="--portal.sandbox-check.discover=true"; else args="'--portal.sandbox-check.fixture=$(realpath "$1")'"; fi
shift
files=("$@"); [[ ${#files[@]} -gt 0 ]] || files=(deploy/sandbox/sandbox.env)
env_file=$(mktemp --suffix=.env); trap 'rm -f "$env_file"' EXIT
cat "${files[@]}" > "$env_file"
docker compose -f deploy/sandbox/compose.yml up -d --wait >/dev/null 2>&1 || { echo "the local sandbox didn't start: run scripts/sandbox-setup.sh" >&2; exit 1; }

./gradlew bootRun -PenvFile="$env_file" --console=plain --args="--spring.profiles.active=sandbox-check \
  --portal.sandbox-check.reset-database=true $args \
  --spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5437/portal \
  --spring.datasource.username=portal --spring.datasource.password=portal"
