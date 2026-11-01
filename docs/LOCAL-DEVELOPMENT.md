# Running the stage 1 API next to your frontend

How to run this API on your machine with the H4I portal frontend in front of it, so sign-in, Join, Claim and Approvals work end to end. What each endpoint does is in [API.md](API.md).

## What runs where

```
 browser ──► frontend dev server  http://localhost:5173
               ├─ your pages (/, /apply, /claim/…, /join/…)
               └─ forwards /api  /oauth2  /login/oauth2  /logout ──► stage 1 API  http://localhost:8080
                                                                        ├─ Postgres (app database)    Docker, random port
                                                                        ├─ fake member database       Docker, localhost:5433
                                                                        └─ Mailpit (catches email)    Docker, localhost:1026 / UI on :8026
```

The browser only ever talks to the frontend's address. Because of that, the API's cookies (Google session, applicant session, CSRF token) belong to that one origin, which is how the API expects to be deployed. Calling `http://localhost:8080` directly from the frontend's JavaScript won't work: there's no CORS.

Port 5173 is Vite's default. If your dev server uses another port, use it everywhere 5173 appears below.

## 1. Prerequisites

- **Docker**, running. `bootRun` starts Postgres, the fake member database and Mailpit from `compose.yaml`.
- **Nothing else for the API.** Gradle downloads JDK 25 if you don't have it.
- **Free ports:** 8080, 5433, 1026 and 8026. The full portal (`../Membership-Project-Portal`) uses the same ones, so stop it first.
- **An `@hack4impact.org` Google account** to sign in with. The OAuth client is internal to H4I's Workspace.

## 2. OAuth apps for local use

Ask national (or whoever holds the vault) for a development Google OAuth client and GitHub OAuth app, or make your own. These URLs go through the frontend's port, not 8080:

| App | Setting | Value |
| --- | --- | --- |
| Google OAuth client (Web application, in the `h4i-portal` Google Cloud project) | Authorized redirect URI | `http://localhost:5173/login/oauth2/code/google` |
| | Authorized JavaScript origin | `http://localhost:5173` |
| GitHub OAuth app | Authorization callback URL | `http://localhost:5173/api/apply/github/callback` |
| | Homepage URL | `http://localhost:5173` |

Without Google nobody can sign in. Without GitHub, applicants can't finish Join or Claim (`githubConfigured` is `false`). Documenso is optional: without it there's no document to sign and registration skips that step.

## 3. A local settings file

Create `local.env` in this folder. It's git-ignored by the `*.env` rule; never commit it.

```sh
# Where browsers reach the app: the frontend's dev server, for both.
PORTAL_BASE_URL=http://localhost:5173
PORTAL_FRONTEND_URL=http://localhost:5173
# Chapter links in API responses; point them at your frontend's join page.
PORTAL_JOIN_BASE_URL=http://localhost:5173/join

# Email goes to Mailpit (http://localhost:8026). SMTP mode needs the host and port too.
PORTAL_MAIL_MODE=smtp
SPRING_MAIL_HOST=localhost
SPRING_MAIL_PORT=1026

PORTAL_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
PORTAL_GOOGLE_CLIENT_SECRET=...
PORTAL_GITHUBOAUTH_CLIENTID=...
PORTAL_GITHUBOAUTH_CLIENTSECRET=...

# Optional: the fake member database as a live integration, so the National page's member-database
# section has something to show. Writes stay in dry run unless PORTAL_MEMBERDB_WRITE=true.
# MEMBER_DB_URL=jdbc:postgresql://localhost:5433/memberdb
# MEMBER_DB_USERNAME=memberdb
# MEMBER_DB_PASSWORD=memberdb
# PORTAL_MEMBERDB_PULL=true

# Stage 2: the sync's tools. Each is read only with ENABLED and changed only with WRITE as well; every tool starts in
# dry run. Their credentials come with each tool's adapter (packages 3-Slack, 3-Google, 3-GitHub, 3-Vaultwarden).
# PORTAL_ADAPTERS_SLACK_ENABLED=true
# PORTAL_ADAPTERS_SLACK_BOTTOKEN=xoxb-...   (the sandbox app's, from the secrets store)
# PORTAL_SYNC_CRON=0 0 3 * * *
```

For Vaultwarden and Documenso there's no shared sandbox. `scripts/sandbox-setup.sh` starts local ones (Docker: Vaultwarden over HTTPS on `localhost:8443`, Documenso on `localhost:3010`, their mail in Mailpit on `localhost:8025`), seeds them, and writes their settings to `deploy/sandbox/sandbox.env`; pass that file as the `-PenvFile`, or copy its lines into `local.env`. `scripts/sandbox-e2e.sh` runs the sandbox suite against them (README, "Sandbox suite"). `docker compose -f deploy/sandbox/compose.yml down -v` removes them. For a quick Vaultwarden on its own, `scripts/vaultwarden-local.sh vaultwarden.env` starts a throwaway one on `127.0.0.1:8090`.

To see what a sync would do without changing anything: `./gradlew bootRun -PenvFile=local.env --args='--spring.profiles.active=sync-dry-run'` prints one dry run per enabled tool and exits; `--spring.profiles.active=adapter-check` lists what each enabled tool's adapter can read. Like `preflight`, both run in the `one-shot` profile: no background work starts.

`bootRun -PenvFile=local.env` loads it (`NAME=value` lines; values may be JSON-quoted). `deploy/fetch-secrets.sh "<vault item>" local.env` writes the same format from the vault.

## 4. Data to sign in with

Stage 1 has no demo seed. Build the data from the fake member database, which has no real people and chapters `umd` and `gt`. Run these once, from this folder, before starting the API.

```sh
# Import chapters, schools, terms and people (claimable records) from the fake member database.
./gradlew bootRun --args='--spring.profiles.active=import'
#   The report is build/import-report.md.

# Make yourself national admin, and lead of a chapter, using the Google account you'll sign in with.
./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=you@hack4impact.org --portal.grant.role=national_admin'
./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=you@hack4impact.org --portal.grant.chapter=umd --portal.grant.role=lead'
#   Roles: lead, co_lead, viewer, national_admin (no chapter needed).
```

Each of these starts the Docker services, does its job and exits.

## 5. Start the API

```sh
./gradlew bootRun -PenvFile=local.env
```

It's ready when the log says `Started PortalApplicationKt`. Check:

```sh
curl http://localhost:8080/api/status          # {"service":"portal","version":"dev"}
```

Leave it running. Stopping it (Ctrl+C) also stops the Docker services; the data stays for next time.

## 6. Point the frontend at it

The dev server must forward four path prefixes to `http://localhost:8080`, **keeping the browser's `Host` header** (`localhost:5173`). The API builds the Google redirect URI from that header, so if the proxy rewrites it to `localhost:8080`, Google refuses the sign-in.

```
/api   /oauth2   /login/oauth2   /logout
```

Forward `/v3` too if you want the live spec at `/v3/api-docs`.

### Vite

```ts
// vite.config.ts
import { defineConfig } from 'vite'

const api = 'http://localhost:8080'

export default defineConfig({
  server: {
    port: 5173,
    // changeOrigin stays false (the default) so the Host header is kept.
    proxy: Object.fromEntries(['/api', '/oauth2', '/login/oauth2', '/logout', '/v3'].map((path) => [path, api])),
  },
})
```

### Other dev servers

| Server | Setting |
| --- | --- |
| webpack-dev-server | `devServer.proxy: [{ context: ['/api', '/oauth2', '/login/oauth2', '/logout'], target: 'http://localhost:8080', changeOrigin: false }]` |
| nginx in front | `proxy_pass http://localhost:8080;` with `proxy_set_header Host $host;` for each prefix |
| Caddy in front | `reverse_proxy /api/* localhost:8080`, and the same for the other prefixes (it keeps `Host` by default) |
| Anything else | Forward the four prefixes and keep `Host`. To check, open `http://localhost:5173/oauth2/authorization/google` and look at the `redirect_uri` in Google's address: it must start with `http://localhost:5173` |

### In the frontend's code

- **Call the API with relative URLs** (`/api/me`, not `http://localhost:8080/api/me`) and `credentials: 'same-origin'`.
- **Call `GET /api/status` once at startup**, so the `XSRF-TOKEN` cookie exists before the first `POST`. Then send its value as `X-XSRF-TOKEN` on every `POST`, `PUT` and `DELETE` ([API.md: CSRF](API.md#csrf)).
- **Start Google sign-in, GitHub sign-in and Documenso signing with full-page navigations**, not `fetch`: `window.location.assign('/oauth2/authorization/google')`, `'/api/apply/github/start'`, or the `url` the agreement endpoint returns.
- **Generate types from the contract:**
  ```sh
  npx openapi-typescript ../Membership-Portal-Stage-1/openapi.json -o src/api/schema.ts
  ```
  Then use `openapi-fetch` (or your client of choice) with those types. Regenerate whenever `openapi.json` changes.

## 7. Check it works

With both running, from the browser at `http://localhost:5173`:

1. **API through the proxy:** `http://localhost:5173/api/status` shows `{"service":"portal",…}`.
2. **Sign in:** go to `http://localhost:5173/oauth2/authorization/google`. After Google you should land back on `http://localhost:5173/`, and `/api/me` shows your chapters with `nationalAdmin: true`.
3. **Email:** open Mailpit at `http://localhost:8026`. Every email the API sends (codes, claim links, invites, approvals) lands there.

### Trying the flows

- **Join:** in a private window (no Google session), open your `/join/umd` page. Send a code to an address at one of the chapter's school domains, take the code from Mailpit, sign in with GitHub, fill in the details and the chapter's questions, sign the document (only with Documenso set up) and send.
- **Approvals:** as the lead, open UMD's applications, check how the application matches people on record, then approve it (as a new person or as the match) or decline it with a reason. Approving as new when a match already has an H4I address needs "approve with a second account". Google accounts aren't created locally unless `PORTAL_ADAPTERS_GOOGLE_*` is set; national gets an admin-queue task instead.
- **Claim:** as national, send a claim link to an imported person (one, or a batch for UMD), open the link from Mailpit in a private window, and confirm the record.
- **Roster:** as the lead, upload a CSV (`first_name,last_name,school_email`). Open the invite from Mailpit and finish joining.
- **Members and national:** the chapter's member list, your own member page (`/api/me/home`), the admin queue, and, with the member database settings on, what writes would be made in dry run and any drift.

## 8. Automated tests

```sh
./gradlew build      # generate jOOQ classes, compile, run every test (Postgres in Testcontainers; needs Docker)
./gradlew test --tests '*RegistrationTests'      # one class
./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec   # rewrite openapi.json after changing an endpoint
```

The tests use fake GitHub, Google, Documenso and member database, with no real services or people. The sandbox suite (`scripts/sandbox-e2e.sh`) is the one that changes real tools: see the README.

## 9. Resetting

```sh
docker compose down -v        # from this folder: deletes the local databases and Mailpit's mail
```

Then repeat [step 4](#4-data-to-sign-in-with).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Google says `redirect_uri_mismatch` | The redirect URI Google got isn't registered. It's built from the `Host` header: check the proxy keeps `localhost:5173` (no `changeOrigin: true`), and that `http://localhost:5173/login/oauth2/code/google` is on the OAuth client |
| Back on `/?signin=denied` | The account isn't `@hack4impact.org` (or a subdomain), or its email isn't verified |
| Signed in, but `/api/me` shows no chapters | The `grant` step used a different address, or the account isn't linked to a person. `personId: null` means not linked |
| Every `POST` answers `403` with `"error":"Forbidden"` | No CSRF token. Call `GET /api/status` first, and send `X-XSRF-TOKEN` with the cookie's value. Also check `credentials: 'same-origin'` |
| `401` on applicant calls right after verifying | The `h4i_applicant` cookie wasn't stored: the call didn't go through the proxy (wrong origin), or `credentials` is missing |
| `401` on `/api/apply/me` after approval or a claim | Expected: the applicant session ends once the registration is decided |
| GitHub returns to `localhost:8080` | `PORTAL_BASE_URL` isn't the frontend's address. GitHub's callback is `PORTAL_BASE_URL/api/apply/github/callback` |
| Emails link to the wrong place | `PORTAL_FRONTEND_URL` isn't set; it falls back to `PORTAL_BASE_URL` |
| The API won't start: `portal.mail.mode=smtp needs spring.mail.host` | Add `SPRING_MAIL_HOST=localhost` and `SPRING_MAIL_PORT=1026`, or use `PORTAL_MAIL_MODE=log` (email is only written to the log) |
| The API won't start: port 8080, 5433 or 1026 in use | The full portal or another app is running. Stop it |
| `/join/umd` shows the chapter as not found | The import hasn't run, or the chapter isn't open to joining (status `active`, `supported` or `pending`) |
