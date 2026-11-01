#!/usr/bin/env bash
# Starts a throwaway Vaultwarden on 127.0.0.1:8090 (container h4i-vaultwarden-local, data lost when it's removed),
# sets up a service admin, a member and an organization, and writes the portal's Vaultwarden settings to an env file:
#
#   scripts/vaultwarden-local.sh vaultwarden.env
#   ./gradlew bootRun -PenvFile=vaultwarden.env --args='--spring.profiles.active=adapter-check'
#
# Add PORTAL_ADAPTERS_VAULTWARDEN_WRITE=true to the file to try writes. Remove it with
# `docker rm -f h4i-vaultwarden-local`. Needs Docker and Java 25. Never point it at a real Vaultwarden.
set -euo pipefail
out=${1:?usage: scripts/vaultwarden-local.sh <env file>}
here=$(cd "$(dirname "$0")" && pwd)
docker rm -f h4i-vaultwarden-local >/dev/null 2>&1 || true
docker run -d --name h4i-vaultwarden-local -p 127.0.0.1:8090:80 \
  -e SIGNUPS_ALLOWED=true -e I_REALLY_WANT_VOLATILE_STORAGE=true vaultwarden/server:1.37.4 >/dev/null
for _ in $(seq 60); do curl -sf http://127.0.0.1:8090/alive >/dev/null && break; sleep 1; done
VW=http://127.0.0.1:8090 java "$here/VaultwardenLocal.java" > "$out"
echo "wrote $out (organization $(grep ORGANIZATIONID "$out" | cut -d= -f2))"
