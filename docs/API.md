# Stage 1 API reference

The HTTP API of the H4I member portal, stage 1 (Members, Approvals, Claim and Join). This app has no UI: a separate frontend calls it. This page is for the people building that frontend.

- **Machine-readable contract:** [`../openapi.json`](../openapi.json) (OpenAPI 3.1). Generate your client and types from it, e.g. with `openapi-typescript` and `openapi-fetch`. A test (`OpenApiSpecTests`) fails the build if it no longer matches the code, so it's always current on `main`.
- **This page** adds what the spec can't say: how sign-in works, who may call what, every error, the side effects (emails, accounts), and the order of calls in each flow.
- **Running it next to your frontend:** [LOCAL-DEVELOPMENT.md](LOCAL-DEVELOPMENT.md).

Checked against the code and a running instance on 2026-10-31.

## Contents

1. [Basics](#1-basics)
2. [Sign-in, sessions and CSRF](#2-sign-in-sessions-and-csrf)
3. [Errors](#3-errors)
4. [Pages the frontend must serve](#4-pages-the-frontend-must-serve)
5. [Flows, step by step](#5-flows-step-by-step)
6. [Endpoint reference](#6-endpoint-reference)
   - [Public](#61-public)
   - [Signed-in members](#62-signed-in-members-google)
   - [Chapters: members and approvals](#63-chapters-members-approvals-settings-roster)
   - [Projects](#63a-projects-stage-2)
   - [National](#64-national)
   - [Applicants](#65-applicants-apiapply)
   - [Agreements and webhooks](#66-agreements-and-webhooks)
7. [Types and values](#7-types-and-values)
8. [Limits and timings](#8-limits-and-timings)

---

## 1. Basics

| | |
| --- | --- |
| Base path | Everything is under `/api`, plus Google sign-in at `/oauth2/authorization/google`, `/login/oauth2/code/google` and `/logout` |
| Format | JSON in and out (`Content-Type: application/json`), except one PDF download |
| Errors | RFC 9457 problem details (`application/problem+json`), see [Errors](#3-errors) |
| IDs | UUIDs as strings |
| Times | ISO 8601 with offset, e.g. `2026-10-31T22:30:27.123Z` |
| Chapter codes | Lowercase short names, e.g. `umd`, `gt`; used in paths |
| Nulls | Optional fields are present with `null`, not left out |
| Live spec | `GET /v3/api-docs` (needs Google sign-in) serves the same spec as `openapi.json` |

There's no versioning in the path. Breaking changes show up as a changed `openapi.json` in a pull request.

## 2. Sign-in, sessions and CSRF

There are **two kinds of caller** with separate sessions, both kept in cookies. The frontend never handles a token itself: the browser sends the cookies.

| Caller | Who | How they sign in | Session cookie | Reaches |
| --- | --- | --- | --- | --- |
| **Member** | Leads, co-leads, viewers, national admins, members | Google, `@hack4impact.org` accounts only (or a subdomain such as `umd.hack4impact.org`) | `JSESSIONID` (server session) | Everything under `/api` except `/api/apply/**` |
| **Applicant** | Someone joining, claiming their record, or following a roster invite | A code sent to their school email, or a claim or roster link | `h4i_applicant` (httpOnly, `Path=/api/apply`, `SameSite=Lax`, `Secure` when `PORTAL_BASE_URL` is https, 7 days) | Only `/api/apply/**`, and only their own registration |

### Same site required

The cookies only work when the browser sees the frontend and this API as **one origin**. In practice, the frontend's server (or dev server) forwards these paths to the API:

```
/api/**   /oauth2/**   /login/oauth2/**   /logout
```

There is no CORS configuration, so calling the API directly from another origin fails. Whether to support that is an open question (wiki: Q58).

`PORTAL_BASE_URL` and `PORTAL_FRONTEND_URL` should then both be the address people's browsers use, i.e. the frontend's.

### Member sign-in (Google)

1. **Start:** do a full-page navigation (not `fetch`) to `/oauth2/authorization/google`. The API redirects to Google, with `hd=hack4impact.org` so Google's account picker suggests H4I accounts.
2. **Callback:** Google returns to `/login/oauth2/code/google` on the same host. The redirect URI is built from the request's `Host` header, so through a proxy it's the frontend's address, e.g. `https://portal.hack4impact.org/login/oauth2/code/google`. That exact URI must be registered on the Google OAuth client.
3. **Result:** the API checks the ID token (verified email, Workspace `hd` and the address both in the allowed domain) and redirects:
   - Success: to `PORTAL_FRONTEND_URL/`.
   - Refused (wrong domain, unverified email, or the user cancelled): to `PORTAL_FRONTEND_URL/?signin=denied`.
4. **Who they are:** call `GET /api/me`. A 401 means not signed in. A 200 with `personId: null` means signed in with a Google account that isn't linked to anyone in the portal. Empty `chapters` with `nationalAdmin: false` means signed in but no access to any chapter.
5. **Sign out:** `POST /logout` with the CSRF header. It answers `302` to `PORTAL_FRONTEND_URL/`. With `fetch`, ignore the redirect and navigate yourself.

API calls never redirect to Google: a call without a session gets `401`, and the frontend decides when to start sign-in.

A person is found by the Google account ID linked on an earlier sign-in, else by their `@hack4impact.org` address. People whose status is `removed` or `applicant` sign in but get no roles.

### Applicant sessions

Created by three endpoints, which set the `h4i_applicant` cookie on success:

- `POST /api/apply/chapters/{code}/verify`: a new applicant proved their school email.
- `POST /api/apply/claim`: someone opened their claim link.
- `POST /api/apply/roster`: someone opened their roster invite.

The session is tied to that one registration. It ends:
- at `POST /api/apply/signout`;
- after 7 days;
- when the registration is approved, declined or completed (the cookie stays in the browser, but calls answer 401).

### CSRF

Every request that changes something (`POST`, `PUT`, `DELETE`, `PATCH`) needs a CSRF token, for both kinds of caller. Only `/api/webhooks/**` is exempt.

- The token is in the `XSRF-TOKEN` cookie (`Path=/`, readable by JavaScript).
- Send it back as the `X-XSRF-TOKEN` header, the cookie's value as is.
- **Getting the first token:** any `GET` outside `/api/apply` sets the cookie. Applicant endpoints don't. So before an applicant's first `POST`, call `GET /api/status` once (public, cheap). Calling it when the app starts is the simplest way.
- A missing or wrong token answers `403` with the Spring error body, not a problem detail.

```ts
function csrfHeader(): Record<string, string> {
  const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}
}

await fetch('/api/status')                     // once, so the cookie exists
await fetch('/api/apply/chapters/umd/code', {
  method: 'POST',
  credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', ...csrfHeader() },
  body: JSON.stringify({ email: 'ada@umd.edu' }),
})
```

### Roles

| Role | Where it comes from | Can |
| --- | --- | --- |
| `viewer` | Chapter role | Read their chapter: overview, members, applications, settings, roster invites |
| `co_lead`, `lead` | Chapter role | Everything a viewer can, plus approve, decline, edit questions and the application switch, upload rosters, download their chapter's signed agreements |
| National admin | `national_admin` | Everything, in every chapter, plus everything under `/api/national`, plus the chapter's Google domain and org unit |
| Member | A linked person | `GET /api/me/home`, their own signed agreements |

Someone with two roles in one chapter gets the stronger one. Roles are granted on the server (`grant` profile, see the README), not through the API.

## 3. Errors

Two shapes, depending on where the request was refused.

**Refused by the application:** a problem detail. `detail` is a sentence written to show to the person as is.

```http
HTTP/1.1 409
Content-Type: application/problem+json

{"title": "Conflict", "status": 409, "detail": "This application is approved", "instance": "/api/chapters/umd/registrations/…/approve"}
```

**Refused by the security layer** (no session, wrong role, no CSRF token):

| Status | Body |
| --- | --- |
| `401` | Empty |
| `403` | `{"timestamp": "…", "status": 403, "error": "Forbidden", "path": "/api/…"}`, with no reason. Show your own text, e.g. "Only chapter leads and co-leads can change this" |

Statuses used:

| Status | Meaning here |
| --- | --- |
| `400` | The input is wrong; `detail` says what. Also unreadable JSON (`"Failed to read request"`) |
| `401` | No session, or the applicant session ended |
| `403` | Not allowed: no role in the chapter, not a lead, not national, no CSRF token. Also, as a problem detail, a chapter that runs its own application process |
| `404` | No such chapter, registration, person, task, template or link |
| `409` | Not possible in the current state: already decided, already sent, already claimed, a second H4I account not confirmed, the member database not configured |
| `410` | A claim link or roster invite that's used, expired or already done |
| `429` | Rate limited: "Too many tries. Wait a little and try again." See [limits](#8-limits-and-timings) |
| `500` | A bug: "Something went wrong on our side. Try again, and tell national if it keeps happening." Logged with a stack trace |
| `503` | GitHub sign-in isn't configured on this server |

Unique-constraint races answer `409` "Someone else just changed this, or it already exists. Reload and try again."

## 4. Pages the frontend must serve

The API sends people to these addresses on `PORTAL_FRONTEND_URL`, in emails and redirects. All of them must exist.

| Path | Who arrives | From | What the page does |
| --- | --- | --- | --- |
| `/` | Members | After Google sign-in and sign-out; the link in "approved" and "claimed" emails | Home. Call `GET /api/me` |
| `/?signin=denied` | Members | Google sign-in refused | Explain that only `@hack4impact.org` accounts can sign in |
| `/join/<chapter>` | New applicants | The chapter's public link (`PORTAL_JOIN_BASE_URL/<chapter>`, e.g. `https://join.hack4impact.org/umd`; that host must serve this page too) | Start of [Join](#join-a-new-applicant) |
| `/join/<chapter>?invite=<token>` | Roster invitees | The roster invite email | `POST /api/apply/roster` with the token, then continue at `/apply` |
| `/claim/<token>` | Imported members | The claim email | `POST /api/apply/claim` with the token, then continue at `/apply` |
| `/apply` | Applicants with a session | After verifying, opening a link, GitHub and Documenso | The applicant's form: `GET /api/apply/me` |
| `/apply?github=failed` | Applicants | GitHub sign-in failed or was cancelled | Show "GitHub sign-in didn't work, try again" |
| `/apply?signed=<kind>` | Applicants | Back from signing in Documenso (`kind` = `volunteer` or `code_of_conduct`) | Reload `GET /api/apply/me`; the agreement may show `sent` for a few seconds until Documenso's webhook arrives |
| `/agreements/<id>` | Members | *Stage 2.* Back from signing the project agreement in Documenso | Say it's signed; their page (`GET /api/me/home`) shows `signed` once Documenso's webhook arrives, usually within seconds. To sign one still open: `POST /api/me/agreements/<id>/sign` |
| `/projects/<chapter>/<slug>` | Members, leads | *Stage 2.* The "Your projects and access" email after a project change | The project: `GET /api/chapters/<chapter>/projects/<slug>` for its leads and the chapter's roles; for a member without either (`403`), their own page (`GET /api/me/home`) |

## 5. Flows, step by step

### Join (a new applicant)

```
GET  /api/apply/chapters/{code}            public: name, school domains, questions, open or not
POST /api/apply/chapters/{code}/code       {email}           → 202, 6-digit code emailed
POST /api/apply/chapters/{code}/verify     {email, code}     → ApplicantView + h4i_applicant cookie
GET  /api/apply/github/start               full-page navigation → GitHub → back to /apply
PUT  /api/apply/me                         {details, answers} → ApplicantView (repeat as they type)
POST /api/apply/me/agreements/{kind}       → {url}: navigate there; Documenso → /apply?signed=kind
POST /api/apply/me/submit                  → ApplicantView, status "submitted"
```

1. If `applicationsOpen` is `false`, the chapter runs its own process. Show `applicationUrl` if set, and don't offer the form: sending a code answers `403`.
2. If `emailDomains` isn't empty, the address must be at one of them (or a subdomain), e.g. `umd.edu` accepts `ada@terpmail.umd.edu`.
3. Verifying the same address again picks up the unfinished application instead of starting another.
4. `ApplicantView.missing` lists in words what's still needed, e.g. `["your GitHub account", "your major", "“Why do you want to join?”"]`. Submit refuses with `400 "Still needed: …"` until it's empty. Use it to drive the checklist.
5. `githubConfigured: false` means GitHub sign-in isn't set up on the server: the applicant can't finish, so say so.
6. `agreements` lists the documents to sign. It's empty until national sets up a template and Documenso can send. Sign each one whose `status` isn't `signed`. When `status` is `sent`, `signUrl` resumes signing.
7. After submit, the status is `submitted` and leads see it on Approvals. The applicant gets an email when it's decided. The session stays usable for viewing until then.

### Roster invite (a lead uploads accepted members)

1. A lead posts a CSV to `POST /api/chapters/{code}/roster`. Each new row gets an email with `/join/<chapter>?invite=<token>`, valid 14 days.
2. The page calls `POST /api/apply/roster {token}`, which sets the session. The school email is already proven (the link went there), and the name is pre-filled. The link can be opened again until the person finishes.
3. The rest is like Join: GitHub, details, agreements, submit.
4. On submit, if they match nobody already on record, they're approved at once (status `approved`, account created, session ended). If they match someone, the application waits for a lead like any other (`submitted`).

### Claim (imported members confirm their record)

1. National previews with `GET /api/national/claims/preview?chapter=umd`, then sends with `POST /api/national/claims/send` (up to 200 per call by default), or sends to one person with `POST /api/national/claims/people/{personId}`.
2. Each person gets an email at every address on file with `/claim/<token>`. It's single use and valid 14 days. Sending one person a new link stops their earlier one.
3. The page calls `POST /api/apply/claim {token}`, which sets the session. The link is used up at that moment, so a reload of `/claim/<token>` answers `410`: keep the person on `/apply`.
4. `kind` is `claim`. The form is pre-filled from their record. Claims ask `stillActive` (still in the chapter, else they're recorded as alumni) and no chapter questions.
5. On submit, there's no approval: the status becomes `completed`, their record is confirmed, a Google account is created if they need one, they get an email, and the session ends.

### Approvals (a lead decides)

1. `GET /api/chapters/{code}/registrations` lists waiting applications (`submitted`) first, then those decided in the last 30 days. Claims never appear here.
2. Each waiting one has `matchStatus` and `matches`: people already on record who might be them, with `why` (e.g. `"same school email"`, `"same GitHub account"`, `"same personal email (not verified)"`) and their H4I `account` if they have one.
3. To **approve as a new person**: `POST …/approve {}`. To **approve as one of the matches** (a returning member or alumnus): `POST …/approve {"personId": "<match>"}`.
4. If a match already has an H4I address and the approval isn't into that record, the API answers `409` with the reason. Ask the lead, then send `"secondAccount": true` to approve anyway.
5. To **decline**: `POST …/decline {"reason": "…"}`. The reason is required and is emailed to the applicant.
6. Both answer with the updated list.

What approval does: the person becomes `active` in the chapter, their GitHub account is recorded, and they get an `@hack4impact.org` Google account if they have none (first name, then first.last and so on, in the chapter's org unit). If the account can't be created automatically, national gets a task on the admin queue instead, and the approval still succeeds. Then they're emailed (with the temporary password, if an account was made) and queued for the member database.

## 6. Endpoint reference

Each entry: who may call it, input, output, errors, side effects. Types are in [section 7](#7-types-and-values). "Lead" means lead or co-lead of that chapter, or a national admin.

### 6.1 Public

#### `GET /api/status`

No sign-in. Use it as a health check, and to get the CSRF cookie.

```json
{"service": "portal", "version": "dev"}
```

`version` is the git SHA in deployed images.

`/actuator/health`, `/actuator/health/readiness` and `/actuator/info` are public too, for monitoring.

### 6.2 Signed-in members (Google)

#### `GET /api/me`

Any signed-in Google account. Who's signed in and which chapters they may open.

```json
{
  "email": "lena@hack4impact.org",
  "name": "Lena Test",
  "personId": "6b0c…",
  "nationalAdmin": false,
  "chapters": [{"id": "1f2e…", "code": "umd", "name": "Hack4Impact UMD", "role": "lead"}]
}
```

- `personId: null`: the Google account isn't linked to anyone in the portal.
- `chapters[].role`: `lead`, `co_lead`, `viewer`, or `national` (national admins see every chapter with this role).
- Errors: `401`.

#### `GET /api/me/home`

Any signed-in member. Their own page: status, chapters, accounts and signed documents; since stage 2 their projects and access. Returns `MemberHome`.

- `agreements[].hasPdf`: the signed copy can be downloaded at `GET /api/agreements/{id}/pdf`.
- `projects[]` (stage 2): `{"chapterCode", "chapter", "slug", "name", "status", "role", "leads", "agreementStatus", "canOpen"}`, projects that aren't closed. `canOpen`: they lead it or have a role in its chapter, so `GET …/projects/{slug}` works for them.
- `agreements[]` gain `project` (the project's name, for a project agreement) and `canSign` (stage 2).
- `access[]` (stage 2): `{"tool", "name", "url", "state", "access", "reasons"}`. `state`: `ready`, `invited` (accept the tool's invite), `setting_up`, `after_agreement` (sign the project agreement first), `not_connected` (this portal doesn't sync that tool yet). Resources the portal leaves alone (unmanaged, or waiting for national) aren't listed. `reasons` in words: "On RISE DC", "Member of UMD", "Lead of UMD".
- Errors: `401`; `404` "Your Google account isn't linked to a member yet".

#### `POST /api/me/agreements/{id}/sign`

*Stage 2.* A fresh Documenso signing link for one of their own unsigned agreements → `{"url"}`; send them there. Documenso brings them back to `/agreements/<id>`. `404` "No such agreement of yours"; `409` when Documenso can't give a link now (signed already, or not connected): "use the link in your email".

#### `GET /api/tools`

Any signed-in member. The tools people have accounts in, in display order. Use it to label and sort `accounts` lists.

```json
[{"key": "slack", "name": "Slack", "order": 1}, {"key": "google", "name": "Google", "order": 2},
 {"key": "github", "name": "GitHub", "order": 3}, {"key": "notion", "name": "Notion", "order": 4},
 {"key": "vaultwarden", "name": "Vaultwarden", "order": 5}]
```

Vaultwarden is listed since stage 2 (project vault collections); the shape is unchanged.

### 6.3 Chapters: members, approvals, settings, roster

All need Google sign-in and a role in the chapter. An unknown chapter is `404` "No chapter {code}"; no role is `403`. Endpoints marked **lead** answer `403` to viewers.

#### `GET /api/chapters/{code}`

Any role. Returns `ChapterOverview`: the chapter, the viewer's role, counts, and its public registration link.

```json
{
  "id": "1f2e…", "code": "umd", "name": "Hack4Impact UMD", "status": "active", "role": "lead",
  "stats": {"activeMembers": 3, "alumni": 1, "waitingApplications": 0, "leads": 1},
  "registrationLink": "https://join.hack4impact.org/umd"
}
```

`activeMembers` counts `active` and `removal_requested`. `waitingApplications` counts applications waiting for a lead.

#### `GET /api/chapters/{code}/members`

Any role. Returns `MemberRow[]`: the chapter's members, people with a chapter role first, then active people, then by name.

- `email` is the H4I address, else the school address. Personal emails are never returned.
- `claimable: true` with `claimLinkSentAt` lets national offer "Send claim link" per row (`POST /api/national/claims/people/{personId}`).

#### `GET /api/chapters/{code}/registrations`

Any role. Returns `RegistrationSummary[]`: applications waiting (`submitted`), then those approved or declined in the last 30 days. Claims aren't included. `answers` is keyed by question label; `matches` is filled only for waiting ones.

#### `POST /api/chapters/{code}/registrations/{id}/approve` (lead)

Body `ApproveInput`:

```json
{"personId": null, "secondAccount": false}
```

- `personId`: approve as this matched record, which must be one of its `matches`. Leave it `null` (or send `{}`) to approve as a new person.
- `secondAccount`: the lead confirmed a second H4I account (see [Approvals](#approvals-a-lead-decides)).

Returns the updated `RegistrationSummary[]`.

| Error | When |
| --- | --- |
| `400` "Approve as a new person or as one of the matches" | `personId` isn't one of the matches |
| `404` "Not this chapter's" / "No such registration" | Wrong chapter or ID |
| `409` "This application is approved" (or `declined`, `started`, …) | It isn't waiting |
| `409` "Ada Lovelace already has an H4I account (ada@…). Approve as that record, or confirm that they should get a second account." | A match has an address, and `secondAccount` isn't `true` |

Side effects: [what approval does](#approvals-a-lead-decides). The applicant's session ends. Audited.

#### `POST /api/chapters/{code}/registrations/{id}/decline` (lead)

Body `{"reason": "We're full this semester"}`. Returns the updated list.

- Errors: `400` "Say why: the applicant gets the reason by email (PRD)" for a blank reason; `404`; `409` when it isn't waiting.
- Side effects: emails the reason to the applicant's personal and school addresses; ends their session; audited.

#### `GET /api/chapters/{code}/registration-settings`

Any role. Returns `RegistrationSettings`:

```json
{
  "questions": [{"id": "9a…", "label": "Why do you want to join?", "kind": "long_text", "options": [], "required": true}],
  "googleDomain": "umd.hack4impact.org", "googleOrgUnit": "/Chapters/UMD",
  "applicationsOpen": true, "applicationUrl": null,
  "canEditQuestions": true, "canEditAccounts": false
}
```

- `canEditQuestions`: the viewer is a lead.
- `canEditAccounts`: the viewer is national (only national sets `googleDomain` and `googleOrgUnit`).
- `googleDomain` defaults to `<code>.hack4impact.org`.

#### `PUT /api/chapters/{code}/registration-settings` (lead)

Body `RegistrationSettingsInput`:

```json
{
  "questions": [{"label": "Why do you want to join?", "kind": "long_text", "options": [], "required": true}],
  "applicationsOpen": true,
  "applicationUrl": null,
  "googleDomain": "umd.hack4impact.org",
  "googleOrgUnit": "/Chapters/UMD"
}
```

- **`questions` replaces the whole list**, in order. Send all of them every time. A question keeps its ID (so earlier answers still point at it) when its label and position are unchanged.
- `kind`: `text`, `long_text`, `choice` (needs at least two non-blank `options`), or `yes_no`.
- `applicationsOpen: null` (or left out) leaves the switch and `applicationUrl` as they are. `false` means the chapter runs its own process: new applicants are refused, roster invites still work. `applicationUrl` must start with `https://`.
- `googleDomain` and `googleOrgUnit` are applied only for national admins, and ignored for leads. Blank clears them.

Returns `RegistrationSettings`.

Errors (`400`):
- "Question 2 needs a label"
- "Question 2: unknown kind"
- "Question 2: a choice needs at least two options"
- "The application link should start with https://"

Audited.

#### `GET /api/chapters/{code}/roster`

Any role. Returns `RosterInviteView[]`, the latest 500 roster invites, newest first. `status` is `sent`, `started`, `completed` or `expired`; an open invite past `expiresAt` shows as `expired`.

#### `POST /api/chapters/{code}/roster` (lead)

Body `{"csv": "<the file's text>"}`. The first line is the header; column names are case-insensitive, and spaces count as `_`.

```csv
first_name,last_name,school_email,personal_email
Ada,Lovelace,ada@umd.edu,ada@example.com
Alan,Turing,alan@umd.edu,
```

`personal_email` is optional. Quoted cells (`"Smith, Jr."`) are allowed. At most 500 rows.

Returns `RosterResult`:

```json
{"invited": 1, "skipped": ["row 3: alan@umd.edu is already invited"], "invites": [ … all invites, as GET … ]}
```

- Rows are skipped, not failed: missing name or school email, a bad address, or an address with an open invite. An expired invite doesn't block a new one.
- Errors: `400` "The roster is empty"; `400` "The first line needs columns first_name, last_name, school_email (and optionally personal_email)"; `400` "At most 500 people per roster".
- Side effects: each invited row is emailed a personal link (after the request commits, in the background); audited.

### 6.3a Projects (stage 2)

Projects, their teams and resources. Reading a project needs a role in its chapter, or leading it (a lead project role); changing it needs lead, co-lead or national; a project's own leads change its team, and nothing else (decision 133). Every change answers with the updated `ProjectDetail`, asks for a sync in the same transaction (the tools follow once it commits), and emails everyone whose projects or access it changed one summary ("Your UMD projects and access", linking to `/projects/<chapter>/<slug>`). A closed project can't change (`409` "This project is closed").

- **`GET /api/project-roles`** (signed in) → `[{"id", "name", "isLead"}]`, leads first. Role names are the member database's; the frontend chooses labels.
- **`GET /api/partners`** (leads) → `[{"id", "name", "website"}]`. A missing one is added by national: **`POST /api/national/partners`** `{"name", "website"?}` → the partner; `409` a name that exists in any case, or one deleted in the member database ("Restore it there…", since the pull follows the member database); `422` a blank name.
- **`GET /api/chapters/{code}/terms`** (lead) → `[{"id", "label"}]`, the school's semesters, newest first.
- **`GET /api/chapters/{code}/past-projects`** (lead) → `[{"id", "name"}]`: the member database's projects with no engagement, read live, that no portal project links yet. `503` when the member database isn't connected or can't be reached.
- **`GET /api/chapters/{code}/resources`** (lead) → `[{"id", "tool", "name", "state", "projects"}]`, to attach one to another project.
- **`GET /api/chapters/{code}/projects`** (any role) → `ProjectSummary[]`: `{"id", "name", "slug", "status", "type", "tags", "term", "partner", "members", "resources", "updatedAt"}`, active first.
- **`POST /api/chapters/{code}/projects`** (lead) with `{"name", "slug"?, "type"?, "tags"?, "termId"?, "partnerId"?, "pastProjectId"?, "startsOn"?, "endsOn"?}` → `201` `ProjectDetail`, a `draft` with the standard resources (Slack channel and Google group for the team; GitHub team and Vaultwarden collection after the project agreement). `409` the short name is taken; `422` an unknown or deleted `partnerId`, or a `pastProjectId` that has an engagement, isn't in the member database or is linked already; `400` other bad fields.
- **`GET /api/chapters/{code}/projects/{slug}`** (a role in the chapter, or a project lead of it) → `ProjectDetail`: the fields above plus `partnerId`, `pastProjectId`, `startsOn`, `endsOn`, `members[]` (`{"personId", "name", "email", "status", "roleId", "role", "isLead", "agreementStatus", "agreement"}`; `agreement` is their latest project agreement, `{"id", "status", "sentAt", "signedAt", "reminders", "hasPdf", "error", "declineReason"}`, or null), `resources[]` (`{"resourceId", "tool", "name", "state", "managed", "audience", "roleId", "role", "access", "requiresAgreement", "tags", "sharedWith", "existsInTool", "getting", "awaitingAgreement", "url", "sync"}`), `agreementsSigned`, `preview` (a draft as if active), `planned` (the latest runs' changes for its resources, as in `ChapterSync`), `lastDryRunAt`, `plannedOutOfDate`, `activity`, `activityMore`, `canEdit`, `canEditTeam`.
- **`PUT /api/chapters/{code}/projects/{slug}`** (lead), the create body without `slug`. `pastProjectId` changes only while a draft (`422` after); a started project keeps a partner (`422`).
- **`POST /api/chapters/{code}/projects/{slug}/status`** (lead) `{"status": "active" | "paused" | "closed"}`. Starting needs a partner (`422` "Choose the project's partner before starting it"), one not deleted in the member database since it was picked (`422`), and sets `startsOn` to today if empty. An edit checks the partner only when it changes. `409` a move that isn't allowed (draft → active or closed; active ↔ paused; either → closed).
- **`GET …/projects/{slug}/candidates`** (lead, project lead) → `[{"personId", "name", "status"}]`: the chapter's active members and alumni not on the team.
- **`POST …/projects/{slug}/members`** (lead, project lead) `{"personId", "roleId"}` (on a live project, the project agreement goes out; the GitHub team and vault collection wait for the signature); **`PUT …/members/{personId}`** `{"roleId"}`; **`DELETE …/members/{personId}`**. `roleId` is required (`400` "Choose the person's project role"); `400` someone not in the chapter, or not active or alumni; `409` already on it; `404` not on it.
- **`POST …/projects/{slug}/resources`** (lead) `{"resourceId"}` to share one of the chapter's, or `{"tool", "name"}` for a new one, plus `audience` (`team`, `role` with `roleId`, `leads`), `access` (`read`, `write`, `admin`), `requiresAgreement`, `tags`. **`PUT …/resources/{resourceId}`** changes who gets it; **`DELETE …/resources/{resourceId}`** detaches it (one never created and used by nothing else is dropped). `400` a tool the portal doesn't create resources in, or a name the tool doesn't allow; `409` already attached, or the chapter has that name already.
- **`GET …/projects/{slug}/activity?before=&limit=20`** (as reading) → `{"items": [{"at", "kind", "text", "actor", "details"}], "more"}`, newest first. `kind`: `change`, `sync` or `agreement` (signed or declined).
- **`GET /api/chapters/{code}/members/{personId}/access`** (lead) → the member's `access[]` (as on `/api/me/home`) for this chapter's resources. `404` not a member of the chapter.

#### `GET /api/chapters/{code}/sync` (lead)

*Stage 2.* The latest sync run of each tool, counted for this chapter's resources, and up to 200 of its changes. Returns `ChapterSync`:

```json
{"tools": [{"tool": "slack", "status": "completed", "mode": "dry_run", "ranAt": "2026-11-01T14:00:00Z", "error": null,
            "adds": 2, "changes": 0, "removals": 0, "drift": 1, "unmatchedAccounts": 1, "missingResources": 0, "creates": 1, "existing": 0}],
 "changes": [{"kind": "add", "tool": "slack", "resource": "umd-rise-dc", "person": "Ada Lovelace", "accountId": null,
              "fromAccess": null, "toAccess": "write", "mode": "dry_run", "outcome": null}]}
```

- `status`: `completed`, `paused` (a real run would stop at the removal limit, or the tool is switched off) or `failed`.
- `kind`: `add`, `change`, `remove`, `drift` (access the portal didn't give; never removed), `unmatched_account`, `missing_resource`, `create_resource`, `archive_resource`, `resource_exists` (the tool already has the name; national decides).
- `outcome` (apply runs only): `applied`, `invited`, `waiting`, `queued`, `held`, `failed`, `dead_letter`, `skipped`.

#### `POST /api/chapters/{code}/sync/run` (lead)

*Stage 2.* `202`. Plans every tool now without changing any, whatever national's switches say (decision 157); read the result with `GET …/sync`.

### 6.4 National

All need a national admin; anyone else gets `403`.

#### Admin queue

Things the portal couldn't do itself, for a person to do by hand, e.g. creating a Google account when a chapter has no org unit yet, or checking a GitHub account linked to two people. Stage 2 adds the sync's tasks: `confirm_removals` (a run held at the removal limit; send its `runId` back with `done`), `create_resource` (creation kept failing; `done` tries again), `add_member`/`remove_member`, and `resource_exists` (a name the tool already has: decide with the [resource endpoints](#resources-linking-existing-ones), not here).

- **`GET /api/national/queue`** → `QueueTask[]`: open tasks, plus those finished in the last 7 days (up to 500). `runId` and `resourceId` are set on sync tasks.
- **`POST /api/national/queue/{id}`** with body `{"action": "done"}` (`{"action": "done", "runId": "…"}` for `confirm_removals`), or `{"action": "cancel", "reason": "Duplicate"}`. Returns the updated list.
  - `400` "action must be done or cancel"
  - `404` "No such task"
  - `409` "This task is already done"; "Newer removals were held since this page loaded…" (a `confirm_removals` whose `runId` isn't the latest); "Decide about the resource instead…" (a `resource_exists`)
  - Audited.

#### Sync controls

*Stage 2.* The syncing tools are Slack, Google, GitHub and Vaultwarden. Every tool starts in dry run.

- **`GET /api/national/tools`** → `ToolStatus[]` in list order: `{"tool", "enabled", "dryRun", "writable", "pauseReason", "maxRemovals", "lastRun": {"status", "mode", "ranAt", "applied", "removed", "error"} | null}`. `writable`: this deployment has the tool's write switch (`PORTAL_ADAPTERS_<TOOL>_WRITE`).
- **`PUT /api/national/tools/{tool}`** with `{"enabled": true, "dryRun": false, "reason": null}` → the list. `404` for a tool that doesn't sync; `409` leaving dry run without the write switch; `400` "Say why the tool is paused" when `enabled` is false without a `reason`. Audited.
- **`POST /api/national/sync/run`** → `202`: every tool, every chapter, as the switches say.
- **`GET /api/national/dead-letters`** → `DeadLetter[]`: `{"personId", "person", "resourceId", "tool", "resource", "attempts", "lastError", "lastAttemptAt"}`, changes that failed five times.
- **`POST /api/national/dead-letters/retry`** with `[{"personId", "resourceId"}]`, or `[]` for all → the list. The next run tries them again. Audited.

#### Resources (linking existing ones)

*Stage 2* (decision 170). When a project's channel, group or team already exists, or for a chapter's own channels, national ties the portal's resource to the existing one. A linked resource only gains people: everyone already in it stays.

- **`GET /api/national/chapters/{code}/resources`** → `NationalResource[]`: `{"id", "tool", "name", "externalId", "managed", "target", "projectSlug", "access", "existsInTool"}`. `managed`: `portal`, `adopted` (linked) or `unmanaged`. `target`: `project_team`, `chapter_members`, `chapter_leads` or null. `existsInTool`: the tool has the name and national hasn't decided.
- **`POST /api/national/chapters/{code}/resources`** with `{"tool": "slack", "name": "#umd"}` or `{"tool": "slack", "externalId": "C0123"}` → the new `NationalResource`, `unmanaged` until linked. `422` both or neither of `name` and `externalId`; `404` the tool doesn't list it (for Slack, the message says to invite the portal's app to a private channel); `409` already linked; `503` the portal isn't connected to the tool or can't read it.
- **`GET /api/national/resources/{id}/link?target=project_team&projectSlug=rise-dc&access=write`** → `{"resource": {"tool", "name", "externalId"}, "people": [{"personId", "account", "name", "verdict"}], "willAdd": 1}`, read live from the tool. `verdict`: `will_add`, `already_there`, `kept` (in it, not in the target: stays) or `unknown_account`. `access` defaults to `write`; a gated tool's people who haven't signed aren't in `will_add`. `404` the tool no longer lists it; `422` a target that doesn't fit (unknown, or a project not in the resource's chapter).
- **`POST /api/national/resources/{id}/link`** → the `NationalResource`, and the resource's `resource_exists` task closes.
  - `{"action": "link", "target": "project_team", "projectSlug": "rise-dc", "access": "write", "confirmedAdds": 1}`: `confirmedAdds` is the preview's `willAdd`. A template resource keeps its audience, level and gate; one national started gets the team, and the agreement gate on GitHub and Vaultwarden. A run of that tool starts after the request. `409` the count changed (preview again) or the existing one is linked to another resource; `422` a missing field, or already linked.
  - `{"action": "rename", "newName": "umd-rise-dc-2026"}`: the portal creates its own under the new name; the existing one is left alone. `422` a name the tool doesn't allow, or a resource the portal wasn't about to create.
  - `{"action": "unmanaged"}`: the portal leaves it alone.
- **`DELETE /api/national/resources/{id}?mode=detach|unmanaged`** → `204`. `detach`: no longer any project's or chapter's; the next run takes back what the portal added. `unmanaged`: the portal stops touching it and removes no one. `422` a resource that isn't linked or a chapter resource, or another mode.

All audited.

#### Claim links

- **`GET /api/national/claims/preview?chapter=umd`** → `ClaimPreview`. Leave out `chapter` for every chapter.
  ```json
  {"eligible": 42, "alreadySent": 10, "withoutEmail": 3}
  ```
  - `eligible`: claimable, without an open link, and with an address.
  - `alreadySent`: they have an open link.
  - `withoutEmail`: no address on file, so a link can't be sent.
- **`POST /api/national/claims/send`** with body `{"chapter": "umd", "limit": 200}`. Both are optional; `chapter: null` means every chapter. Sends links to up to `limit` people (kept between 1 and 1000) who don't have an open one. Returns the new `ClaimPreview`. Emails go out after commit; audited.
- **`POST /api/national/claims/people/{personId}`** sends one person a new link, ending their earlier one.
  - Returns `{"addresses": 2, "expiresAt": "…"}`: how many addresses it went to, and until when it works.
  - `404` "No such person".
  - `409`: "Ada already claimed their record", "Ada joined through the portal, so there's no imported record to claim", "Ada was removed", or "Ada has no email address on file".

Who can be claimed: imported from the member database, not claimed yet, not removed, in the chapter. The one-month claim deadline counts from a person's first link.

#### Member database

The integration with the national member database: pulls on a schedule, writes back what people confirm, and reports fields changed on the other side ("drift").

- **`GET /api/national/member-db`** → `MemberDbStatus`:
  - `configured`, `writeSwitch` and `scheduledPull`: server settings.
  - `dryRun` and `paused` (with `pauseReason`): switches national controls.
  - The last pull's time, status and error.
  - `waiting` and `failed` writes (up to 500 each), and `drift`.
- **`PUT /api/national/member-db`** with body `{"dryRun": true, "paused": false, "reason": null}`. Returns the status.
  - `409` "The write switch (PORTAL_MEMBERDB_WRITE) isn't set on this server, so it stays in dry run": leaving dry run without the server switch.
  - `400` "Say why it's paused": `paused: true` without a reason.
  - Audited.
- **`POST /api/national/member-db/pull`** pulls now, even if nothing seems changed. Returns `PullResult`:
  ```json
  {"status": "completed", "counts": {"institution": 0, "term": 2, "chapter": 0, "person": 3, "chapter_membership": 3, "tool_account": 5}, "drift": 0, "error": null}
  ```
  `status` is `completed`, `unchanged` (nothing changed, so `counts` is empty) or `failed` (`error` says why). `counts` is rows inserted or updated per kind; `drift` is the number of drift reports. `409` when the member database isn't configured. Audited.
- **`GET /api/national/member-db/writes/{personId}/preview`** shows what would be written for one person. Returns `MemberDbPreview`:
  ```json
  {"newVolunteer": false, "fields": {"volunteers.email": "ada@example.com"}, "notes": ["…"]}
  ```
  `409` when not configured, or when the person can't be written (the reason is in `detail`).
- **`POST /api/national/member-db/writes/{personId}/retry`** puts a failed write back in line with a fresh attempt count. Returns the status. `404` "Nothing to write for them". Audited.

#### Agreement templates

The Documenso templates agreements are made from: one active template per kind, and a new one becomes the next version. Kinds: `volunteer` and `code_of_conduct` (signed during registration) and, since stage 2, `project` (sent when someone joins a live project, or when a project with them on it starts). Agreements go out only while `sending` is true, for every kind.

- **`GET /api/national/agreement-templates`** → `AgreementTemplates`: `templates[]` (newest version first per kind, with `sent` counts), `connected` (Documenso is reachable with a token) and `sending` (its write switch is on, so agreements actually go out).
- **`POST /api/national/agreement-templates`** with body `{"kind": "volunteer", "name": "Volunteer agreement", "documensoTemplateId": "envelope_abc123"}`.
  - `documensoTemplateId` may be the ID or a link to the template.
  - The template is checked in Documenso: it must exist and have a signer.
  - The new template becomes the active one for its kind.
  - `400`: "Kind is volunteer, code_of_conduct or project", the name is missing, "Paste the template's ID or link from Documenso", or "Documenso doesn't have a usable template …".
  - Audited.
- **`POST /api/national/agreement-templates/{id}/deactivate`** stops making agreements from it; signed ones stay valid. `404` "No such template". Audited.

### 6.5 Applicants (`/api/apply`)

These use the applicant session, not Google. The first four are public; the rest answer `401` without a session. All `POST` and `PUT` calls need the CSRF header.

#### `GET /api/apply/chapters/{code}`

Public. Returns `ApplyChapter`: what the join page shows.

```json
{"code": "umd", "name": "Hack4Impact UMD", "emailDomains": ["umd.edu"],
 "questions": [{"id": "9a…", "label": "Why do you want to join?", "kind": "long_text", "options": [], "required": true}],
 "applicationsOpen": true, "applicationUrl": null}
```

- Only chapters people can join (status `active`, `supported` or `pending`); others are `404`.
- `questions` is empty when `applicationsOpen` is `false`.

#### `POST /api/apply/chapters/{code}/code`

Public. Body `{"email": "ada@umd.edu"}`. Answers `202` with no body, and emails a 6-digit code valid for 10 minutes.

| Error | When |
| --- | --- |
| `400` "That doesn't look like an email address" | |
| `400` "Use your school address (@umd.edu)" | Not one of the chapter's domains |
| `403` "Hack4Impact UMD runs its own application process: apply at https://…" | Applications are off |
| `404` | No such chapter, or not open to joining |
| `429` | 5 codes per address, or 30 per IP address, in an hour |

#### `POST /api/apply/chapters/{code}/verify`

Public. Body `{"email": "ada@umd.edu", "code": "123456"}`. Returns `ApplicantView` and sets `h4i_applicant`.

| Error | When |
| --- | --- |
| `400` "That code isn't right" | Counts as a wrong attempt |
| `400` "Too many wrong codes. Ask for a new one." | 5 wrong attempts on this code |
| `400` "That code has expired. Ask for a new one." | Older than 10 minutes, or none sent |
| `403` / `404` | As for sending a code |
| `429` | 60 tries per IP address in an hour |

#### `POST /api/apply/claim`

Public. Body `{"token": "<from /claim/<token>>"}`. Returns `ApplicantView` (`kind: "claim"`) and sets the session. Uses the link up.

| Error | When |
| --- | --- |
| `404` "This link isn't valid. Ask national for a new one." | |
| `410` "This link was already used." / "This link has expired." / "This record is already claimed. Sign in with your Hack4Impact Google account." | |
| `409` "This record has no chapter. Ask national." | |
| `429` | 60 links per IP address in an hour |

#### `POST /api/apply/roster`

Public. Body `{"token": "<from ?invite=>"}`. Returns `ApplicantView` (`kind: "roster"`) and sets the session. It can be opened again until the person finishes, and returns the same registration.

| Error | When |
| --- | --- |
| `404` "This invite isn't valid. Ask your chapter's leads." | |
| `410` "You've already joined. Sign in with your Hack4Impact Google account." / "This invite has expired. Ask your chapter's leads for a new one." | |
| `429` | As for claims |

#### `GET /api/apply/me`

Session. Returns their `ApplicantView`. After a decision it shows the outcome: `declined` with `declineReason`, or `approved`/`completed` with `account`. In practice the session has usually ended by then, so this answers `401`: show the outcome from the last response you had, or tell them to check their email.

#### `PUT /api/apply/me`

Session. Saves the form, as often as you like while `status` is `started`. Body `DetailsInput`:

```json
{
  "details": {
    "firstName": "Ada", "lastName": "Lovelace", "preferredName": null,
    "personalEmail": "ada@example.com",
    "graduationYear": 2028, "graduationSeason": "spring",
    "major": "Computer Science", "degreeType": "Bachelor's",
    "roleInterest": "Developer",
    "stillActive": null
  },
  "answers": {"9a…question id…": "To build things"}
}
```

- **Every field is replaced**: send the whole form, not only what changed. Blank strings are saved as `null`.
- `answers` is keyed by question `id` (from `chapter.questions`). Unknown IDs are dropped, and answers are cut at 4000 characters.
- `stillActive` is for claims only: are they still in the chapter?

Returns `ApplicantView`, with `missing` recomputed.

Errors:
- `400`: "That personal email doesn't look right"; "Graduation year should look like 2028" (outside 2000–2100); "Graduation season is spring, summer, fall or winter"; "Major and degree type are at most 150 characters".
- `409` "This application was already sent".

#### `GET /api/apply/github/start`

Session. **Navigate the browser here** (`window.location.assign`); don't `fetch` it. It redirects to GitHub (no scopes; it only reads who they are). GitHub comes back to `PORTAL_BASE_URL/api/apply/github/callback`, which records the account and redirects to `PORTAL_FRONTEND_URL/apply`, or `/apply?github=failed`. `503` if GitHub sign-in isn't configured.

For a claim, linking a GitHub account different from the one on record sets `githubChanged`, which leads and national see.

`GET /api/apply/github/callback` is GitHub's return address. Don't call it yourself.

#### `POST /api/apply/me/agreements/{kind}`

Session. `kind` is `volunteer` or `code_of_conduct`, as listed in `ApplicantView.agreements`. Prepares the document in Documenso and returns where to sign:

```json
{"url": "https://sign.hack4impact.org/sign/…"}
```

Navigate there. Documenso returns to `PORTAL_FRONTEND_URL/apply?signed=<kind>`.

Errors:
- `400` "Save your name first: it goes on the agreement".
- `400` "No volunteer agreement to sign".
- `409`: "Already signed", "The agreement couldn't be prepared. Try again in a moment.", or "This application was already sent".

#### `POST /api/apply/me/submit`

Session. No body. Sends the application. Returns `ApplicantView`:

| Kind | Result `status` | Then |
| --- | --- | --- |
| `new` | `submitted` | Waits for a lead |
| `roster`, matching nobody | `approved` | Account created, email sent, session ended |
| `roster`, with a match | `submitted` | Waits for a lead |
| `claim` | `completed` | Record confirmed, account created if needed, email sent, session ended |

Errors:
- `400` "Still needed: your GitHub account, your major, …".
- `403`: a new application to a chapter that just turned applications off.
- `409` "This application was already sent".

#### `POST /api/apply/signout`

Session. Ends every session of this registration and clears the cookie. `204`.

### 6.6 Agreements and webhooks

#### `GET /api/agreements/{id}/pdf`

Google sign-in. The signed PDF (`application/pdf`, downloaded as `agreement-<First>-<Last>.pdf`). Allowed for national, the chapter's leads, and the signer themselves. Link to it directly (`<a href>`); the session cookie goes along.

- `403` "Not your chapter's agreement"; `404` "No such agreement" or "No signed PDF yet".
- Every download is audited.

#### `POST /api/webhooks/documenso`

For Documenso only, not the frontend. No sign-in and no CSRF; checked by the shared secret in `X-Documenso-Secret`. The payload is only a hint: the envelope is re-read from Documenso.

- `202` accepted.
- `401` wrong secret.
- `404` when no webhook secret is configured.

## 7. Types and values

The full schemas are in `openapi.json`. These are the fields whose meaning or values the spec doesn't spell out.

### `ApplicantView`

| Field | Type | Meaning |
| --- | --- | --- |
| `kind` | string | `new`, `claim` or `roster` |
| `status` | string | `started` (editing), `submitted` (waiting for a lead), `approved`, `declined`, `completed` (claim done) |
| `chapter` | `ApplyChapter` | The chapter, whatever its status |
| `schoolEmail`, `emailVerified` | string?, boolean | The proven school address |
| `githubLogin` | string? | The linked GitHub username |
| `githubConfigured` | boolean | GitHub sign-in is set up on the server |
| `details` | `Details` | What they saved (see `PUT /api/apply/me`) |
| `answers` | map | Question ID → answer |
| `missing` | string[] | What's still needed before submitting, in words, ready to show |
| `declineReason` | string? | Set when `declined` |
| `account` | string? | Their `@hack4impact.org` address, after approval or a claim |
| `agreements` | `ApplicantAgreement[]` | `{kind, name, status, signUrl}`; `status` is `not_started`, `draft`, `sent` or `signed`; `signUrl` is set while `sent` |

### `RegistrationSummary` (leads)

| Field | Meaning |
| --- | --- |
| `kind` | `new` or `roster` |
| `status` | `submitted`, `approved` or `declined` |
| `name` | Preferred name if set, else first name, plus last name |
| `matchStatus` | `new` (nobody matched), `returning`, `alumni` (a record found), `duplicate` (only weak evidence, e.g. same name or an unverified email) |
| `matches[]` | `{personId, name, status, chapters, why[], account}` |
| `answers` | Question **label** → answer |
| `githubChanged` | A claim linked a different GitHub account than on record |

### People, chapters and accounts

| Value | Values |
| --- | --- |
| Person `status` (`MemberRow`, `MatchView`, `MemberHome`) | `applicant`, `active`, `alumni`, `removal_requested`, `removed` |
| Person `kind` (`MemberRow`) | `student`, `community` |
| Chapter `status` | `active`, `inactive`, `pending`, `supported`, `suspended` |
| Chapter role | `lead`, `co_lead`, `viewer` (and `national` in `/api/me` and `ChapterOverview`) |
| Account `state` (`AccountSummary`, `MyAccount`) | `unverified`, `invited`, `accepted`, `confirmed`, `suspended`, `removed` |
| Account `tool` | `slack`, `google`, `github`, `notion`, `vaultwarden` (see `GET /api/tools`) |
| Question `kind` | `text`, `long_text`, `choice`, `yes_no` |
| Graduation season | `spring`, `summer`, `fall`, `winter` |
| Roster invite `status` | `sent`, `started`, `completed`, `expired` |
| Admin task `status` | `open`, `done`, `cancelled` |
| Admin task `action` | e.g. `create_account`, `check_account` (show `description`, which says what to do) |
| Agreement `status` (`MyAgreement`) | `sent`, `signed`, `declined`, `expired` |
| Agreement template `kind` | `volunteer`, `code_of_conduct` |
| Member database pull | `completed`, `unchanged`, `failed` |

## 8. Limits and timings

| What | Limit |
| --- | --- |
| Email codes | 6 digits, valid 10 minutes, 5 wrong attempts each |
| Sending codes | 5 per address and 30 per IP address per hour → `429` |
| Verifying codes | 60 per IP address per hour → `429` |
| Opening claim or roster links | 60 per IP address per hour → `429` |
| Claim links | Single use, 14 days |
| Roster invites | Reusable until done, 14 days |
| Applicant session | 7 days, or until decided |
| Roster upload | 500 rows |
| Claim batch | `limit` 1–1000, default 200 |
| Answers | 4000 characters each |
| Major, degree type | 150 characters |
| Lists | Members unlimited; roster invites, queue tasks, member-DB rows: latest 500 |

Rate limits are kept in memory per app instance and reset when it restarts.
