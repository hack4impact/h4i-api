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

Port 5173 is Vite's default. If your dev server uses another port, use it everywhere 5173 appears below, and ask for that port's URLs to be added to the OAuth apps ([step 2](#2-oauth-apps-for-local-use)): sign-in is refused from a port they don't list.

Commands are written for bash: macOS, Linux, or WSL or Git Bash on Windows (the `scripts/*.sh` need one of those anyway). In Windows `cmd`, use `gradlew.bat` instead of `./gradlew`, and double quotes instead of single ones: `--args="--spring.profiles.active=import"`.

## 1. Prerequisites

- **Docker**, running. `bootRun` starts Postgres, the fake member database and Mailpit from `compose.yaml`.
- **Nothing else for the API.** Gradle downloads JDK 25 if you don't have it.
- **Free ports:** 8080, 5433, 1026 and 8026. The full portal (`../Membership-Project-Portal`) uses the same ones, so stop it first.
- **An H4I Google account** to sign in with: `@hack4impact.org` or a chapter's subdomain (`@umd.hack4impact.org`). The OAuth client is internal to H4I's Workspace.

## 2. OAuth apps for local use

**If national gave you the filled settings file** (the vault item "portal development", [step 3](#3-a-local-settings-file)), its development Google client and GitHub app are the ones to use. You create nothing: ask national to confirm the URLs below are on them, and go to step 3.

Otherwise, ask national (or whoever holds the vault) for a development Google OAuth client and GitHub OAuth app, or make your own. These URLs go through the frontend's port, not 8080:

| App | Setting | Value |
| --- | --- | --- |
| Google OAuth client (Web application, in the `h4i-portal` Google Cloud project) | Authorized redirect URI | `http://localhost:5173/login/oauth2/code/google` |
| | Authorized JavaScript origin | `http://localhost:5173` |
| GitHub OAuth app | Authorization callback URL | `http://localhost:5173/api/apply/github/callback` |
| | Homepage URL | `http://localhost:5173` |

Without Google nobody can sign in. Without GitHub, applicants can't finish Join or Claim (`githubConfigured` is `false`). Documenso is optional: without it there's no document to sign and registration skips that step.

## 3. A local settings file

1. **Copy** `local.env.example` to `local.env` in this folder, or save national's filled copy (the vault item "portal development") under that name. `local.env` is git-ignored by the `*.env` rule; never commit it.
2. **Fill in** every `<from the vault>` value in the sign-in block. The stage 2 tools further down are all off and can stay as they are.
3. **Change the three address lines to the frontend's port.** The template has them on 8080, for running the API on its own. Behind the frontend's dev server all three must be 5173, or GitHub returns to the wrong port and email links point at the API:
   ```sh
   PORTAL_BASE_URL=http://localhost:5173
   PORTAL_FRONTEND_URL=http://localhost:5173
   PORTAL_JOIN_BASE_URL=http://localhost:5173/join
   ```

`./gradlew bootRun -PenvFile=local.env` ([step 5](#5-start-the-api)) loads the file. It takes one file, with `NAME=value` lines; values may be JSON-quoted.

The lines that matter, once edited:

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

# The fake member database as a live integration, so the National page's member-database section has
# something to show. Set PULL to true to pull on a schedule. Writes stay in dry run unless PORTAL_MEMBERDB_WRITE=true.
MEMBER_DB_URL=jdbc:postgresql://localhost:5433/memberdb
MEMBER_DB_USERNAME=memberdb
MEMBER_DB_PASSWORD=memberdb
PORTAL_MEMBERDB_PULL=false
```

The stage 2 tools (Slack, GitHub, Google groups, Vaultwarden, Documenso) aren't needed for sign-in, Join, Claim or Approvals: see [Optional: the stage 2 tools](#optional-the-stage-2-tools).

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

Two more are optional: `/v3` and `/scalar`, for the live spec at `/v3/api-docs` and its [reference page](#the-api-reference-scalar) at `/scalar`.

### Vite

```ts
// vite.config.ts
import { defineConfig } from 'vite'

const api = 'http://localhost:8080'

export default defineConfig({
  server: {
    port: 5173,
    // The four the app needs, then the two for the API reference page (optional).
    // changeOrigin stays false (the default) so the Host header is kept.
    proxy: Object.fromEntries(
      ['/api', '/oauth2', '/login/oauth2', '/logout', '/v3', '/scalar'].map((path) => [path, api]),
    ),
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

### The API reference (Scalar)

The spec as a browsable page, with each endpoint's parameters, schemas and a "Test request" button. You need to be signed in with Google, because the page loads `/v3/api-docs`. Signing in is enough: no `grant` needed to read the spec.

**Behind the frontend** (the setup above, with `/v3` and `/scalar` forwarded as in [step 6](#6-point-the-frontend-at-it)): sign in as in "Check it works", then open `http://localhost:5173/scalar`.

**Without the frontend running:**

1. **Start the API** ([step 5](#5-start-the-api)). The same `local.env` works.
2. **Sign in:** open `http://localhost:8080/oauth2/authorization/google` and choose your H4I account. This uses the redirect URI `http://localhost:8080/login/oauth2/code/google`, which the development Google client has.
3. **Ignore where you land.** Google sends you on to `PORTAL_FRONTEND_URL/`. With the 5173 settings and no frontend running, the browser says it can't connect; with the template's 8080 settings it's a 404 page. Either way you're signed in.
4. **Open `http://localhost:8080/scalar`** in the same browser.

If the page loads but shows no endpoints, the spec request failed with 401: sign in again. "Test request" sends your session cookie, so GETs work as you. Writes also need the `X-XSRF-TOKEN` header, copied from the `XSRF-TOKEN` cookie, which Scalar doesn't add. Without sign-in, `openapi.json` in this folder is the same spec.

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

## Optional: the stage 2 tools

Skip this unless you're working on projects, the sync or agreements. Every tool is off in the template.

**Turning a tool on.** Set its `PORTAL_ADAPTERS_<TOOL>_ENABLED=true` in `local.env` and fill in its credentials from the vault. `ENABLED` only reads, and every tool starts in dry run. Changing a tool needs its `WRITE` as well, which changes the shared sandbox for everyone: leave that to the sandbox suite. What each setting means is in the README's "Configuration" table.

**Seeing what a sync would do**, without changing anything:

```sh
./gradlew bootRun -PenvFile=local.env --args='--spring.profiles.active=sync-dry-run'    # one dry run per enabled tool, then exits
./gradlew bootRun -PenvFile=local.env --args='--spring.profiles.active=adapter-check'   # what each enabled tool's adapter can read
```

Both are one-shot commands: no background work starts.

**Vaultwarden and Documenso** have no shared sandbox, so you run local ones:

```sh
scripts/sandbox-setup.sh                               # once: starts and seeds them in Docker
docker compose -f deploy/sandbox/compose.yml down -v   # removes them
```

They run at Vaultwarden `https://localhost:8443`, Documenso `http://localhost:3010`, and their own Mailpit `http://localhost:8025`. The script writes their settings to `deploy/sandbox/sandbox.env`. `bootRun` takes one env file, so either copy those lines into `local.env`, or pass `-PenvFile=deploy/sandbox/sandbox.env` on its own. For a quick Vaultwarden by itself, `scripts/vaultwarden-local.sh vaultwarden.env` starts a throwaway one on `127.0.0.1:8090`.

**The sandbox suite** (`scripts/sandbox-e2e.sh`) really changes the sandboxes and checks each tool. It does accept several env files. See the README, "Sandbox suite".

**Getting settings from the vault:** `deploy/fetch-secrets.sh "<vault item>" local.env` writes the file in the same format.

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
