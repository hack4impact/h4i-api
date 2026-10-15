#!/usr/bin/env bash
# Loads a CSV saved from scripts/member-db-export.sql into a throwaway local Postgres (container
# h4i-memberdb-export, port 5434) shaped like the member DB, so the importer can read it:
#
#   scripts/load-member-db-export.sh ~/Downloads/member-db-export.csv
#   MEMBER_DB_URL=jdbc:postgresql://localhost:5434/memberdb MEMBER_DB_USERNAME=memberdb MEMBER_DB_PASSWORD=memberdb \
#     ./gradlew bootRun --args='--spring.profiles.active=import'
#   docker rm -f h4i-memberdb-export   # afterwards: it holds personal data
set -euo pipefail

csv="${1:?usage: load-member-db-export.sh <export.csv>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
name=h4i-memberdb-export
tables=(academic_institutions academic_terms chapters partners volunteers academic_profiles volunteer_accounts
	project_roles projects project_engagements project_assignments leadership_assignments)
psql() { docker exec -i "$name" psql -q -v ON_ERROR_STOP=1 -U memberdb "$@"; }

docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" -e POSTGRES_USER=memberdb -e POSTGRES_PASSWORD=memberdb -e POSTGRES_DB=memberdb \
	-p 127.0.0.1:5434:5432 postgres:17 >/dev/null
# Wait for the real server (it listens on TCP only after initialization finishes).
until docker exec "$name" psql -h 127.0.0.1 -U memberdb -c 'SELECT 1' >/dev/null 2>&1; do sleep 1; done

# Same columns and types as the member DB, without constraints: the importer reports bad rows itself.
sed -n '/^CREATE TABLE/,/^);/p' "$root/src/test/resources/memberdb/schema.sql" | sed 's/ NOT NULL//' | psql
psql -c 'CREATE TABLE export_raw (t text, j json)'
psql -c '\copy export_raw FROM STDIN WITH (FORMAT csv, HEADER true)' < "$csv"
for t in "${tables[@]}"; do
	psql -c "INSERT INTO $t SELECT r.* FROM export_raw e, json_populate_record(NULL::$t, e.j) r WHERE e.t = '$t'"
done
psql -c 'DROP TABLE export_raw'

echo "Loaded into $name (localhost:5434):"
for t in "${tables[@]}"; do
	printf '  %-24s %s\n' "$t" "$(docker exec "$name" psql -At -U memberdb -c "SELECT count(*) FROM $t")"
done
