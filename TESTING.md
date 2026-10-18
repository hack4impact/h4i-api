# Testing stage 1

How to test the stage 1 portal (Members, Approvals, Claim and Join): the automated tests, trying it by hand on your machine, and the checks before go-live.

Everything runs from this folder and needs Docker. Stage 1 uses the same local ports as the full portal (8080, 5433, Mailpit on 1026/8026), so stop the full portal first.

## 1. Automated tests

```sh
./gradlew build      # generate jOOQ classes, compile, run backend and web tests
```

- **Backend:** JUnit against a real Postgres 17 in Testcontainers, with fake GitHub, Google, Documenso and member database (`src/test/kotlin/org/hack4impact/portal/`). The fake member database is `src/test/resources/memberdb/`.
- **Web:** Vitest (`cd web && npm test` runs these alone).
- 83 backend and 17 web tests passed on 2026-10-15.

Narrower runs:

```sh
./gradlew test                                   # backend only
./gradlew test --tests '*StatusControllerTests'  # one class
```

## 2. Trying it by hand

Stage 1 has no demo seed, so you build the data yourself from the fake member database (no real people; chapters `umd` and `gt`).

### What you need

- A Google OAuth client (`PORTAL_GOOGLE_CLIENT_ID`, `PORTAL_GOOGLE_CLIENT_SECRET`). Without it nobody can sign in.
- A GitHub OAuth app (`PORTAL_GITHUBOAUTH_CLIENTID`, `PORTAL_GITHUBOAUTH_CLIENTSECRET`) for Join.
- Optional: `PORTAL_ADAPTERS_DOCUMENSO_*` to sign the registration document. Google account creation and member database writes stay off or in dry run unless you turn them on.

### Set up

```sh
# 1. Import the fake member database: chapters, schools, terms, people to claim
./gradlew bootRun --args='--spring.profiles.active=import'
#    The report is in build/import-report.md.

# 2. Give yourself roles (use the Google account you'll sign in with)
./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=you@hack4impact.org --portal.grant.role=national_admin'
./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=you@hack4impact.org --portal.grant.chapter=umd --portal.grant.role=lead'
#    Roles: lead, co_lead, viewer, national_admin (no chapter needed).

# 3. Start the app, with email going to Mailpit instead of the log
PORTAL_MAIL_MODE=smtp \
PORTAL_GOOGLE_CLIENT_ID=... PORTAL_GOOGLE_CLIENT_SECRET=... \
PORTAL_GITHUBOAUTH_CLIENTID=... PORTAL_GITHUBOAUTH_CLIENTSECRET=... \
./gradlew bootRun
```

`bootRun` starts Postgres, the fake member database and Mailpit, then the app:

- App: http://localhost:8080
- Mailpit (email codes, claim links): http://localhost:8026

### What to try

- **Join:** in a private window, open `/join/umd`. Prove the school email with the code from Mailpit, sign in with GitHub, fill in the details and the chapter's questions, and sign the document (only with Documenso set up).
- **Approvals:** as the lead, open the application, check how it matches people on record, and approve it (as a new person or as the match) or decline it with a reason.
- **Claim:** as national, send a claim link to an imported person (one, or a batch), open it from Mailpit and confirm the record.
- **Members:** the chapter's member list, a member's own page, and the admin queue on the National page.
- **Member database:** on the National page, see what writes would be made in dry run and any drift reported.

## 3. Staging

```sh
deploy/staging/up.sh [tag]    # pulls an image and runs it with its own Postgres on http://localhost:8082
```

It needs `deploy/staging/.env` with `PORTAL_DB_PASSWORD`, built from the vault with `deploy/fetch-secrets.sh "<vault item>" deploy/staging/.env`.

## 4. Preflight (before go-live)

Checks the deployment with its real settings. It only reads: nothing is created, written or emailed.

```sh
./gradlew bootRun --args='--spring.profiles.active=preflight --portal.preflight.chapters=umd'   # just the pilot
./gradlew bootRun --args='--spring.profiles.active=preflight'                                   # every chapter taking applications
```

It prints OK, WARN or FAIL per check and exits 1 if anything failed. See the README for what it checks.
