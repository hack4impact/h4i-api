# H4I Member Portal, stage 1

The first stage of the Hack4Impact member portal, as its own app: **Members, Approvals, Claim and Join**.

- **Join:** a student opens their chapter's link (`/join/<chapter>`), proves their school email with a code, signs in with GitHub, fills in their details (major, degree and graduation term included) and the chapter's questions, and signs the registration document in Documenso. Leads can also upload a roster of accepted members, who get personal pre-filled links.
- **Approvals:** chapter leads see each application with how it matches people already on record, and approve (as a new person or as the matched record) or decline with a reason.
- **Claim:** national sends imported members a single-use link to confirm their record, in batches or one person at a time.
- **Members:** each chapter's member list, a member's own page, and national's admin queue.
- **The member database** as an integration: the portal pulls the national member database on a schedule and writes back what people confirm and leads decide (below).

On approval or claim, the portal creates the member's `@hack4impact.org` Google account (or asks national to, as an admin-queue task), emails them, and queues their record for the member database.

Copied on 2026-10-13 from `../Membership-Project-Portal` (the full portal: projects, tool access, drafts, automations, adoption, agreements) and cut down to stage 1. The plan is in the wiki: `../Docs/wiki/staged-rollout.md`.

Kotlin + Spring Boot 4 on Java 25, PostgreSQL 17 with Flyway and jOOQ, built with Gradle.

## API only

Since 2026-10-31 this app has no UI: it's the backend the H4I portal's own frontend calls. Everything is under `/api` (plus Google sign-in at `/oauth2/authorization/google` and `/logout`). The pages named above live in that frontend.

- **Docs:** [docs/API.md](docs/API.md), every endpoint with its access rules, errors, side effects and flows; [docs/LOCAL-DEVELOPMENT.md](docs/LOCAL-DEVELOPMENT.md), running it next to the frontend.
- **Contract:** [openapi.json](openapi.json), checked against the live API by `OpenApiSpecTests` (regenerate with `./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec`). Generate the frontend's client from it.
- **The frontend must serve** `/apply` (also `?github=failed` and `?signed=<kind>`), `/claim/<token>`, `/join/<chapter>?invite=<token>`, `/projects/<chapter>/<slug>` and `/agreements/<id>` (stage 2), and `/` (also `?signin=denied`), at `PORTAL_FRONTEND_URL`: email links and redirects point there.
- **Sign-in is cookies:** the Google session (`JSESSIONID`), the applicant session (`h4i_applicant`, path `/api/apply`) and the CSRF token (`XSRF-TOKEN`, sent back as `X-XSRF-TOKEN` on writes). They work when the frontend and this API share one site, e.g. the frontend's server proxies `/api`, `/oauth2`, `/login/oauth2` and `/logout` here. There's no CORS set up for calls from another origin.

## The member database

Settled 2026-10-14 (wiki: staged-rollout, decisions 159 and 162):

- **Pull** (`PORTAL_MEMBERDB_PULL=true`, every 15 minutes): when any table changed, the importer brings in what the member database owns (chapters, schools, terms, Slack and Notion accounts) and new volunteers, as records to claim. A record its person confirmed is never overwritten.
- **Write**: approving, claiming or finishing a roster invite queues the person (`member_db_write`, in the same transaction). A worker writes their volunteer row, school profile and GitHub and Google accounts every minute, in one member-database transaction each; failures back off and wait for national after five. It never deletes.
- **Switches**: writes start in **dry run** (national sees what would be written, per person, on the National page). Leaving dry run needs the server's write switch (`PORTAL_MEMBERDB_WRITE=true`); national can pause it with a reason.
- **Drift**: a field the portal owns that someone changes in the member database is reported on the National page, not taken.
- **Stage 2 writes**: a project's `projects` row, engagement, team (assignments) and, the first time, its partner, in one transaction each; a chapter lead's or co-lead's leadership assignment. Off until national turns on project writes (`projectsEnabled`), after checking the previews. A write waits for its people's own records first. Removals and closures set end dates; nothing is deleted.
- **Changes for Khoa**: `scripts/member-db-changes.sql` (a primary key on `volunteer_accounts.id`, one row per `(provider, external_id)`, `updated_at` triggers, and suggestions). It refuses to run while duplicate accounts exist; the tests run it against the fake member database. Its stage 2 part fixes `projects.status_valid`, adds `leadership_assignments.volunteer_id` and `chapter_id`, and seeds the "Chapter lead" and "Co-lead" roles; `scripts/member-db-stage2-grants.sql` gives the `portal_sync` login what stage 2 writes.
- **Before go-live: national seeds `academic_terms`** (each school's graduation terms). Until a term exists, people's graduation is kept in the portal and written once it's there.

## Requirements

- Docker. Tests, jOOQ code generation and local runs all start Postgres in containers.
- Nothing else: Gradle downloads JDK 25 if it isn't installed.

## Run locally

```sh
./gradlew build                   # generate jOOQ classes, compile, run tests
./gradlew bootRun                 # starts compose.yaml (Postgres, a fake member DB, Mailpit), then the API on http://localhost:8080
```

It uses the same local ports as the full portal (8080, Mailpit on 1026/8026, the fake member DB on 5433), so run one at a time. With a frontend, sign-in and test data: [docs/LOCAL-DEVELOPMENT.md](docs/LOCAL-DEVELOPMENT.md).

## Database

`V1__stage1_schema.sql` (schema `portal`) is made from the full portal's V1–V17 with only the tables stage 1 uses; `V2` adds the member database integration (study fields, writes waiting, drift, its switches) and `V3` the registration document (Documenso). Later stages add their tables in new migrations. jOOQ classes are generated from it into `build/generated/jooq` by `./gradlew generateJooq`, which runs automatically before compiling.

## Importing the national member database

The importer copies schools, terms, chapters, people and their tool accounts in, never overwrites anything edited or claimed in the portal, and writes a report of everything that needs a person to look at.

```sh
# Against the fake member DB that compose.yaml starts (no real people):
./gradlew bootRun --args='--spring.profiles.active=import'

# Against a real (preferably anonymized) copy:
MEMBER_DB_URL=jdbc:postgresql://<host>:5432/<db> MEMBER_DB_USERNAME=portal_import MEMBER_DB_PASSWORD=... \
  ./gradlew bootRun --args='--spring.profiles.active=import'
```

If the member DB isn't reachable from your machine (it usually isn't), run `scripts/member-db-export.sql` in pgAdmin's Query Tool, save the result as CSV outside the repo, and load it with `scripts/load-member-db-export.sh <file.csv>`; then import with `MEMBER_DB_URL=jdbc:postgresql://localhost:5434/memberdb` (user and password `memberdb`).

The report is written to `build/import-report.md`. It contains personal data, so don't commit or share it outside national. `scripts/anonymize-member-db.sql` anonymizes a copy of the member DB for rehearsals.

Chapter leads and national admins are entered in the portal (the member DB can't record them):

```sh
./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=lena@hack4impact.org --portal.grant.chapter=umd --portal.grant.role=lead'
# roles: lead, co_lead, viewer, national_admin (no chapter needed)
```

## Configuration

| Setting | What |
| --- | --- |
| `PORTAL_BASE_URL` | Where this API lives (OAuth callbacks, the Documenso webhook) |
| `PORTAL_FRONTEND_URL` | The frontend that uses this API: links in emails and the redirects after sign-in, sign-out, GitHub and Documenso go there (defaults to `PORTAL_BASE_URL`) |
| `PORTAL_JOIN_BASE_URL` | Where the chapter join links live |
| `PORTAL_GOOGLE_CLIENT_ID`, `PORTAL_GOOGLE_CLIENT_SECRET` | Google sign-in for leads, national and members |
| `PORTAL_GITHUBOAUTH_CLIENTID`, `PORTAL_GITHUBOAUTH_CLIENTSECRET` | GitHub sign-in for applicants |
| `PORTAL_ADAPTERS_GOOGLE_*` | Creating members' Google accounts (`ENABLED`, `CREATEACCOUNTS`, `ADMINSUBJECT`, `SERVICEACCOUNTKEY`, `USERSCOPE`) |
| `PORTAL_MAIL_MODE` | `gmail` (production, as noreply@), `smtp` (Mailpit), `log` |
| `MEMBER_DB_URL`, `MEMBER_DB_USERNAME`, `MEMBER_DB_PASSWORD` | The member database (the `portal_sync` login in production) |
| `PORTAL_MEMBERDB_PULL`, `PORTAL_MEMBERDB_WRITE` | Pull on a schedule; the write switch (writes also need national to leave dry run) |
| `PORTAL_ADAPTERS_DOCUMENSO_*` | The registration document and, since stage 2, the project agreement (`ENABLED`, `BASEURL`, `TOKEN`, `WEBHOOKSECRET`, `WRITE`); national picks the templates on the National page |
| `PORTAL_ADAPTERS_<TOOL>_ENABLED`, `…_WRITE` | *Stage 2.* The tools the sync manages (`SLACK`, `GOOGLE`, `GITHUB`, `VAULTWARDEN`): read with `ENABLED`, changed only with `WRITE` too. Every tool starts in dry run; national takes it out on the National page. Each tool's credentials come with its adapter |
| `PORTAL_SYNC_CRON` | *Stage 2.* A nightly sync, e.g. `0 0 3 * * *` (off unless set); changes ask for a sync anyway |

**One-shot commands** (`preflight`, `adapter-check`, `sync-dry-run`, `import`, `grant`) join the `one-shot` profile: no scheduled jobs, no replay of outbox events left in the database, no sync recovery and no background syncs or agreement sends, so running one against a live deployment's database changes nothing but what it's for. Flyway still migrates first.

## Preflight

Before going live (and after changing settings), check the deployment with its real settings. It only reads: nothing is created, written or emailed.

```sh
./gradlew bootRun --args='--spring.profiles.active=preflight'                               # every chapter taking applications
./gradlew bootRun --args='--spring.profiles.active=preflight --portal.preflight.chapters=umd'  # just the pilot
# or, on the server: java -jar portal.jar --spring.profiles.active=preflight
```

It prints one line per check (OK, WARN or FAIL) and exits 1 if anything failed:

- **Portal:** the public address and the frontend's, Google and GitHub sign-in set, email not in `log` mode, at least one national admin.
- **Member database:** the connection, the change check, `SELECT` on the six tables it reads, `INSERT` and `UPDATE` on the three it writes, no `DELETE`, Khoa's changes (`scripts/member-db-changes.sql`: keys, unique accounts, `updated_at` triggers), and the switches. *Stage 2:* the grants on the project, partner and leadership tables (`scripts/member-db-stage2-grants.sql`), and the script's stage 2 part (`leadership_assignments.volunteer_id` and `chapter_id`, the "Chapter lead" and "Co-lead" roles, the fixed `projects.status_valid`); missing ones fail when the write switch is on.
- **Google:** the service account can look up the admin; a sandbox org-unit scope is flagged.
- **Documenso:** each active template is a template with a signer; the webhook secret. *Stage 2:* when a template gates resources (the GitHub team and vault collection), a project template must be active and Documenso able to send, or it fails: members would wait for an agreement that's never sent.
- **Sync** *(stage 2)*: for each of Slack, Google, GitHub and Vaultwarden, whether it's synced here (its adapter enabled), whether the adapter can read it, its own permission check (added with each tool's adapter), and its switches.
- **Each chapter:** school email domains, Google domain and org unit, a current lead or co-lead, its questions, and academic terms after this year.

## Deploy

Every push to `main` runs CI and pushes `ghcr.io/<owner>/<repository>:<sha>` and `:main`. The Dockerfile packages the jar built by `./gradlew bootJar`.

Staging runs on a developer's computer: `deploy/staging/up.sh [tag]` pulls an image and starts it with its own Postgres on http://localhost:8082, beside the full portal's staging (8081). It needs `deploy/staging/.env` with `PORTAL_DB_PASSWORD`, built from the vault with `deploy/fetch-secrets.sh "<vault item>" deploy/staging/.env`.
