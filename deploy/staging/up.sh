#!/usr/bin/env bash
# Deploys a CI-built image to staging on this computer, then smoke-tests it.
# Usage: deploy/staging/up.sh [tag]   (default: main; any git SHA that CI built also works)
set -euo pipefail
cd "$(dirname "$0")"

tag="${1:-main}"
export PORTAL_IMAGE="ghcr.io/hack4impact/membership-portal-stage-1:${tag}"
port="${STAGING_PORT:-8082}"

if [[ ! -f .env ]]; then
	echo "deploy/staging/.env is missing. Create it from the vault: ../fetch-secrets.sh \"portal staging\" .env" >&2
	exit 1
fi

docker compose pull portal
docker compose up -d --wait
echo "Staging is up on http://localhost:${port}"
curl -sf "http://127.0.0.1:${port}/api/status"
echo
