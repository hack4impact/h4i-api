#!/usr/bin/env bash
# Sets up the local sandbox for stage 2's sandbox suite (wiki: staged-rollout-stage-2, package 9), once per machine:
# starts deploy/sandbox/compose.yml (Postgres, Vaultwarden over HTTPS, Mailpit, Documenso), and seeds
#   - Vaultwarden: a service admin, two members and an organization, and the admin's API key;
#   - Documenso: an account, an API token and a one-page project agreement template.
# Writes the portal's settings for both to deploy/sandbox/sandbox.env, and a fixture for them to
# deploy/sandbox/sandbox-fixture.yml. Running it again only starts the containers; --reset deletes their data first.
#
#   scripts/sandbox-setup.sh [--reset]
#
# Needs Docker, openssl, curl, python3 and Java 25. Everything is local and throwaway: example.test addresses and
# generated passwords. Never point it at a real Vaultwarden or Documenso.
set -euo pipefail
cd "$(dirname "$0")/.."
dir=deploy/sandbox
compose=(docker compose -f "$dir/compose.yml")

if [[ "${1:-}" == "--reset" ]]; then
  "${compose[@]}" down -v
  rm -rf "$dir/certs" "$dir/.env" "$dir/sandbox.env" "$dir/sandbox-fixture.yml"
fi

# Secrets and certificates (kept in deploy/sandbox, git-ignored).
if [[ ! -f "$dir/.env" ]]; then
  rand() { openssl rand -hex 32; }
  printf 'DOCUMENSO_NEXTAUTH_SECRET=%s\nDOCUMENSO_ENCRYPTION_KEY=%s\nDOCUMENSO_ENCRYPTION_SECONDARY_KEY=%s\nDOCUMENSO_SIGNING_PASSPHRASE=%s\n' \
    "$(rand)" "$(rand)" "$(rand)" "$(rand)" > "$dir/.env"
  chmod 600 "$dir/.env"
fi
if [[ ! -f "$dir/certs/documenso.p12" ]]; then
  # shellcheck disable=SC1091
  . "$dir/.env"
  c="$dir/certs"; mkdir -p "$c"
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=H4I sandbox CA" -keyout "$c/ca-key.pem" -out "$c/ca.pem" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout "$c/vaultwarden-key.pem" -out "$c/vaultwarden.csr" 2>/dev/null
  openssl x509 -req -in "$c/vaultwarden.csr" -CA "$c/ca.pem" -CAkey "$c/ca-key.pem" -CAcreateserial -days 3650 \
    -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth') -out "$c/vaultwarden.pem" 2>/dev/null
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=H4I Sign sandbox" -keyout "$c/documenso-key.pem" -out "$c/documenso-cert.pem" 2>/dev/null
  openssl pkcs12 -export -inkey "$c/documenso-key.pem" -in "$c/documenso-cert.pem" -passout "pass:$DOCUMENSO_SIGNING_PASSPHRASE" -out "$c/documenso.p12"
  chmod 644 "$c"/*.pem "$c/documenso.p12" # the containers run as other users
  rm -f "$c/truststore.p12"
fi
[[ -f "$dir/certs/truststore.p12" ]] || keytool -importcert -noprompt -alias sandbox-ca -file "$dir/certs/ca.pem" \
  -keystore "$dir/certs/truststore.p12" -storetype PKCS12 -storepass sandbox >/dev/null

"${compose[@]}" up -d
echo "Waiting for Vaultwarden and Documenso…"
for _ in $(seq 120); do
  curl -sf --cacert "$dir/certs/ca.pem" https://localhost:8443/alive >/dev/null && curl -s -o /dev/null http://localhost:3010/ && break
  sleep 2
done

if [[ -f "$dir/sandbox.env" ]]; then
  echo "Already seeded: $dir/sandbox.env (scripts/sandbox-setup.sh --reset starts over)"
  exit 0
fi

# Vaultwarden.
vault=$(VW=https://localhost:8443 java -Djavax.net.ssl.trustStore="$dir/certs/truststore.p12" -Djavax.net.ssl.trustStorePassword=sandbox \
  scripts/VaultwardenLocal.java)

# Documenso: sign up, confirm the address from Mailpit, sign in, make a team API token and the template.
B=http://localhost:3010
jar=$(mktemp); trap 'rm -f "$jar"' EXIT
email=sign-admin@example.test
password="local-only $(openssl rand -hex 12)"
json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
curl -sf -X POST "$B/api/auth/email-password/signup" -H 'Content-Type: application/json' -H "Origin: $B" \
  -d "{\"name\":\"Sandbox admin\",\"email\":\"$email\",\"password\":\"$password\"}" >/dev/null
link=""
for _ in $(seq 30); do
  id=$(curl -s "http://localhost:8025/api/v1/search?query=to:$email" | json "next((m['ID'] for m in d['messages'] if 'confirm' in m['Subject'].lower()), '')")
  [[ -n "$id" ]] && link=$(curl -s "http://localhost:8025/api/v1/message/$id" | json "__import__('re').findall(r'/verify-email/(\S+)', d['Text'])[0]") && break
  sleep 1
done
[[ -n "$link" ]] || { echo "Documenso's confirmation email didn't reach Mailpit" >&2; exit 1; }
curl -sf -X POST "$B/api/auth/email-password/verify-email" -H 'Content-Type: application/json' -H "Origin: $B" -d "{\"token\":\"$link\"}" >/dev/null
csrf=$(curl -sf -c "$jar" -b "$jar" "$B/api/auth/csrf" | json "d['csrfToken']")
curl -sf -c "$jar" -b "$jar" -X POST "$B/api/auth/email-password/authorize" -H 'Content-Type: application/json' -H "Origin: $B" \
  -d "{\"email\":\"$email\",\"password\":\"$password\",\"csrfToken\":\"$csrf\"}" >/dev/null
org=$(curl -sf -b "$jar" "$B/api/trpc/organisation.getMany" | json "d['result']['data']['json'][0]['id']")
team=$(curl -sf -b "$jar" -G "$B/api/trpc/team.find" --data-urlencode "input={\"json\":{\"organisationId\":\"$org\"}}" | json "d['result']['data']['json']['data'][0]['id']")
token=$(curl -sf -b "$jar" -X POST "$B/api/trpc/apiToken.create" -H 'Content-Type: application/json' -H "Origin: $B" \
  -d "{\"json\":{\"tokenName\":\"portal sandbox\",\"teamId\":$team,\"expirationDate\":null}}" | json "d['result']['data']['json']['token']")
pdf=$(mktemp --suffix=.pdf); trap 'rm -f "$jar" "$pdf"' EXIT
python3 -I - "$pdf" <<'PDF'
import sys
text = b"BT /F1 18 Tf 72 720 Td (H4I sandbox project agreement) Tj 0 -30 Td /F1 11 Tf (A test document for the portal's sandbox suite. Not a real agreement.) Tj ET"
objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d >>\nstream\n" % len(text) + text + b"\nendstream", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
out = bytearray(b"%PDF-1.4\n"); offsets = []
for i, o in enumerate(objs, 1):
    offsets.append(len(out)); out += b"%d 0 obj\n" % i + o + b"\nendobj\n"
xref = len(out); out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for o in offsets: out += b"%010d 00000 n \n" % o
out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(sys.argv[1], "wb").write(out)
PDF
payload='{"title":"H4I sandbox project agreement","type":"TEMPLATE","recipients":[{"email":"","name":"","role":"SIGNER","fields":[{"type":"SIGNATURE","page":1,"positionX":10,"positionY":75,"width":35,"height":8}]}]}'
template=$(curl -sf -X POST "$B/api/v2/envelope/create" -H "Authorization: $token" \
  -F "payload=$payload;type=application/json" -F "files=@$pdf;type=application/pdf" | json "d['id']")

{
  echo "# Written by scripts/sandbox-setup.sh: the local sandbox's Vaultwarden and Documenso (throwaway, localhost only)."
  echo "$vault"
  echo "PORTAL_ADAPTERS_VAULTWARDEN_WRITE=true"
  echo "PORTAL_ADAPTERS_VAULTWARDEN_TRUSTEDCERTIFICATE=$PWD/$dir/certs/ca.pem"
  echo "PORTAL_ADAPTERS_DOCUMENSO_ENABLED=true"
  echo "PORTAL_ADAPTERS_DOCUMENSO_BASEURL=$B"
  echo "PORTAL_ADAPTERS_DOCUMENSO_TOKEN=$token"
  echo "PORTAL_ADAPTERS_DOCUMENSO_WRITE=true"
  echo "PORTAL_SANDBOXE2E_AGREEMENTTEMPLATE=$template"
  echo "# Documenso sign-in (to look around): $email / $password"
} > "$dir/sandbox.env"
chmod 600 "$dir/sandbox.env"
cat > "$dir/sandbox-fixture.yml" <<'YAML'
# Written by scripts/sandbox-setup.sh for the local sandbox. Both people have no vault account yet, so the suite invites
# them by address, Vaultwarden accepts (it has no mail), and the next run confirms them. Add the shared sandboxes'
# accounts (slack, github, google) to test those too; see sandbox-fixture.example.yml.
chapter: { code: sandbox, name: H4I Portal Sandbox }
people:
  - { name: Ada Lead, role: lead, email: member@example.test }
  - { name: Ben Builder, email: member2@example.test }
YAML
echo "Seeded. Settings: $dir/sandbox.env; fixture: $dir/sandbox-fixture.yml. Next: scripts/sandbox-e2e.sh"
