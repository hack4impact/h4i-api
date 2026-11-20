# Portal API reference

The HTTP API of the H4I member portal: joining, claiming and approving members, the chapter's members, projects and their tools, and agreements. This app has no UI: a separate frontend calls it. This page is for the people building that frontend.

- **Machine-readable contract:** [`../openapi.json`](../openapi.json) (OpenAPI 3.1). Generate your client and types from it, e.g. with `openapi-typescript` and `openapi-fetch`. A test (`OpenApiSpecTests`) fails the build if it no longer matches the code, so it's always current on `main`.
- **This page** adds what the spec can't say: how sign-in works, who may call what, every error, the side effects (emails, accounts), the order of calls in each flow, and how each thing's status changes.
- **Running it next to your frontend:** [LOCAL-DEVELOPMENT.md](LOCAL-DEVELOPMENT.md).

Checked against the code on 2026-11-20. Every operation in `openapi.json` is on this page.

## Contents

1. [Basics](#1-basics)
2. [Sign-in, sessions and CSRF](#2-sign-in-sessions-and-csrf)
3. [Who may call what](#3-who-may-call-what)
4. [Errors](#4-errors)
5. [Pages the frontend must serve](#5-pages-the-frontend-must-serve)
6. [Flows, step by step](#6-flows-step-by-step)
7. [States and what changes them](#7-states-and-what-changes-them)
8. [Endpoint reference](#8-endpoint-reference)
   - [Public](#81-public)
   - [Signed-in members](#82-signed-in-members)
   - [Chapters: members, approvals, settings, roster](#83-chapters-members-approvals-settings-roster)
   - [Projects and sync](#84-projects-and-sync)
   - [National](#85-national)
   - [Applicants](#86-applicants-apiapply)
   - [Agreements and webhooks](#87-agreements-and-webhooks)
9. [Types and values](#9-types-and-values)
10. [Limits and timings](#10-limits-and-timings)

---

## 1. Basics

| | |
| --- | --- |
| Base path | Everything is under `/api`, plus Google sign-in at `/oauth2/authorization/google`, `/login/oauth2/code/google` and `/logout` |
| Format | JSON in and out (`Content-Type: application/json`), except one PDF download |
| Errors | RFC 9457 problem details (`application/problem+json`), see [Errors](#4-errors) |
| IDs | UUIDs as strings |
| Times | ISO 8601 with offset, e.g. `2026-10-31T22:30:27.123Z` |
| Chapter codes | Lowercase short names, e.g. `umd`, `gt`; used in paths |
| Nulls | Optional fields are present with `null`, not left out |
| Live spec | `GET /v3/api-docs` (needs Google sign-in) serves the same spec as `openapi.json` |
| Reference page | `/scalar`: the spec as a browsable reference (Scalar). The page opens for anyone, but it loads `/v3/api-docs`, so sign in first (`/oauth2/authorization/google`). Its "Test request" works for GETs; writes need the `X-XSRF-TOKEN` header from the `XSRF-TOKEN` cookie, which it doesn't add |

There's no versioning in the path. Breaking changes show up as a changed `openapi.json` in a pull request.

## 2. Sign-in, sessions and CSRF

There are **two kinds of caller** with separate sessions, both kept in cookies. The frontend never handles a token itself: the browser sends the cookies.

| Caller | Who | How they sign in | Session cookie | Reaches |
| --- | --- | --- | --- | --- |
| **Member** | Leads, co-leads, viewers, national admins, members | Google, `@hack4impact.org` accounts only (or a subdomain such as `umd.hack4impact.org`) | `JSESSIONID` (server session) | Everything under `/api` except `/api/apply/**` |
| **Applicant** | Someone joining, claiming their record, or following a roster invite | A code sent to their school email, or a claim or roster link | `h4i_applicant` (httpOnly, `Path=/api/apply`, `SameSite=Lax`, `Secure` when `PORTAL_BASE_URL` is https, 7 days) | Only `/api/apply/**`, and only their own application |

### Same site required

The cookies only work when the browser sees the frontend and this API as **one origin**. In practice, the frontend's server (or dev server) forwards these paths to the API:

```
/api/**   /oauth2/**   /login/oauth2/**   /logout
```

There is no CORS configuration, so calling the API directly from another origin fails. Whether to support that is still undecided.

`PORTAL_BASE_URL` and `PORTAL_FRONTEND_URL` should then both be the address people's browsers use, i.e. the frontend's.

### Member sign-in (Google)

1. **Start:** do a full-page navigation (not `fetch`) to `/oauth2/authorization/google`. The API redirects to Google with `hd=*`, so Google's account picker offers only Workspace accounts. It doesn't send `hd=hack4impact.org`, because with that hint chapter accounts (`you@umd.hack4impact.org`) can't be typed in. The domain is checked in step 3 instead.
2. **Callback:** Google returns to `/login/oauth2/code/google` on the same host. The redirect URI is built from the request's `Host` header, so through a proxy it's the frontend's address, e.g. `https://portal.hack4impact.org/login/oauth2/code/google`. That exact URI must be registered on the Google OAuth client.
3. **Result:** the API checks the ID token (verified email, Workspace `hd` and the address both in the allowed domain) and redirects:
   - Success: to `PORTAL_FRONTEND_URL/`.
   - Refused (wrong domain, unverified email, or the user cancelled): to `PORTAL_FRONTEND_URL/?signin=denied`.
4. **Who they are:** call `GET /api/me`.

   | Answer | Meaning |
   | --- | --- |
   | `401` | Not signed in |
   | `200`, `personId: null` | Signed in with a Google account that isn't linked to anyone in the portal |
   | `200`, empty `chapters`, `nationalAdmin: false` | Signed in, but no access to any chapter |
   | `200` otherwise | Signed in; `chapters` lists what they may open, with their role in each |

5. **Sign out:** `POST /logout` with the CSRF header. It answers `302` to `PORTAL_FRONTEND_URL/`. With `fetch`, ignore the redirect and navigate yourself.

API calls never redirect to Google: a call without a session gets `401`, and the frontend decides when to start sign-in.

A person is found by the Google account ID linked on an earlier sign-in, else by their `@hack4impact.org` address. People whose status is `removed` or `applicant` sign in but get no roles.

### Applicant sessions

Created by three endpoints, which set the `h4i_applicant` cookie on success:

- `POST /api/apply/chapters/{code}/verify`: a new applicant proved their school email.
- `POST /api/apply/claim`: someone opened their claim link.
- `POST /api/apply/roster`: someone opened their roster invite.

The session is tied to that one application. It ends:
- at `POST /api/apply/signout`;
- after 7 days;
- **when the application is approved, declined or completed.** The cookie stays in the browser, but every call answers `401` from then on. So **the response that decided it is the last one you get**: when `POST /api/apply/me/submit` answers `approved` or `completed`, show the outcome from that response; don't call `GET /api/apply/me` again. A chapter lead's decision on a waiting application also ends the session; the applicant learns of it by email.

### CSRF

Every request that changes something (`POST`, `PUT`, `DELETE`, `PATCH`) needs a CSRF token, for both kinds of caller. Only `/api/webhooks/**` is exempt.

- The token is in the `XSRF-TOKEN` cookie (`Path=/`, readable by JavaScript).
- Send it back as the `X-XSRF-TOKEN` header, the cookie's value as is.
- **Getting the first token:** any `GET` sets the cookie, applicant endpoints under `/api/apply` included, and so do ones that answer `401` or `404`. The simplest way is to call `GET /api/status` once when the app starts (public, cheap), so a token is there before the first `POST`.
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

## 3. Who may call what

### Roles

| Role | Where it comes from | Means |
| --- | --- | --- |
| **Viewer** | Chapter role `viewer` | Reads their chapter |
| **Chapter lead** | Chapter role `lead` or `co_lead`. The two have the same rights | Runs their chapter: approvals, settings, rosters, projects |
| **National** | `national_admin` | Everything, in every chapter, plus everything under `/api/national`. Counts as a chapter lead of every chapter |
| **Project lead** | A project role marked as a lead role (e.g. Tech Lead) on one project | Reads that project and changes its team, nothing else |
| **Member** | A person linked to the Google account | Their own page and their own agreements |

Someone with two roles in one chapter gets the stronger one. Roles are granted on the server (`grant` profile, see the README), not through the API.

On this page, **"chapter lead" always includes co-leads and national**. A project lead isn't a chapter lead unless they also have that chapter role.

### By endpoint

The test `EndpointPermissionTests` holds this same table and calls every endpoint as each kind of person, so it stays true: an endpoint it doesn't list fails the build.

A `…` path below starts with `/api/chapters/{code}`.

| Who | Endpoints |
| --- | --- |
| **Anyone, no sign-in** | `GET /api/status`; `GET /api/apply/chapters/{code}`, `POST /api/apply/chapters/{code}/code`, `POST /api/apply/chapters/{code}/verify`; `POST /api/apply/claim`; `POST /api/apply/roster`; `POST /api/webhooks/documenso` (Documenso's shared secret instead) |
| **Applicant session** | `GET /api/apply/me`, `PUT /api/apply/me`, `POST /api/apply/me/agreements/{kind}`, `POST /api/apply/me/submit`, `GET /api/apply/github/start`, `GET /api/apply/github/callback`, `POST /api/apply/signout` |
| **Any signed-in Google account** | `GET /api/me`, `GET /api/me/home`, `GET /api/project-roles`, `GET /api/tools` |
| **The agreement's signer only** | `POST /api/me/agreements/{id}/sign` (anyone else gets `404`, not `403`) |
| **The signer, the chapter's leads, national** | `GET /api/agreements/{id}/pdf` |
| **A chapter lead of any chapter** | `GET /api/partners` |
| **Viewer or chapter lead of that chapter** | `GET …`, `GET …/members`, `GET …/projects`, `GET …/registrations`, `GET …/registration-settings`, `GET …/roster` |
| **Viewer or chapter lead of that chapter, or a project lead of that project** | `GET …/projects/{slug}`, `GET …/projects/{slug}/activity` |
| **Chapter lead of that chapter, or a project lead of that project** | `GET …/projects/{slug}/candidates`; `POST …/projects/{slug}/members`; `PUT` and `DELETE …/projects/{slug}/members/{personId}` |
| **Chapter lead of that chapter** | `POST …/registrations/{id}/approve`, `POST …/registrations/{id}/decline`; `PUT …/registration-settings`; `POST …/roster`; `GET …/terms`, `GET …/past-projects`, `GET …/resources`, `GET …/members/{personId}/access`, `GET …/sync`; `POST …/sync/run`; `POST …/projects`; `PUT …/projects/{slug}`; `POST …/projects/{slug}/status`; `POST …/projects/{slug}/resources`; `PUT` and `DELETE …/projects/{slug}/resources/{resourceId}` |
| **National** | Everything under `/api/national` |

Refused callers get `403` (the security layer's body, see [Errors](#4-errors)); signed-out callers get `401`.

## 4. Errors

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
| `403` | Not allowed: no role in the chapter, not a chapter lead, not national, no CSRF token. Also, as a problem detail, applying to a chapter that runs its own application process |
| `404` | No such chapter, application, person, task, template or link |
| `409` | Not possible in the current state: already decided, already sent, already claimed, a second H4I account not confirmed, the member database not configured, a closed project, a name that's taken |
| `410` | A claim link or roster invite that's used, expired or already done |
| `422` | The JSON is readable but a field is missing or names something that can't be used: an unknown or deleted partner, a link target that doesn't fit, a blank partner name. Used by the project, partner and resource endpoints. Handle it like `400`: `detail` says what |
| `429` | Rate limited: "Too many tries. Wait a little and try again." See [limits](#10-limits-and-timings) |
| `500` | A bug: "Something went wrong on our side. Try again, and tell national if it keeps happening." Logged with a stack trace |
| `503` | Something this server depends on isn't set up or can't be reached: GitHub sign-in, the member database, or a tool (Slack, Google, GitHub, Vaultwarden). `detail` says which |

Success is `200` with a body unless an endpoint says otherwise: `201` for a created project, `202` for work that continues in the background (sending a code, starting a sync), `204` for no body.

Unique-constraint races answer `409` "Someone else just changed this, or it already exists. Reload and try again."

**Don't match on `detail`.** Its wording is written for people and may change. Branch on the status code and show `detail`. Where a flow has to tell two answers with the same status apart, the endpoint's entry says how (e.g. [approve](#post-apichapterscoderegistrationsidapprove)).

## 5. Pages the frontend must serve

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
| `/agreements/<id>` | Members | Back from signing a project agreement in Documenso | Say it's signed; their page (`GET /api/me/home`) shows `signed` once Documenso's webhook arrives, usually within seconds. To sign one still open: `POST /api/me/agreements/<id>/sign` |
| `/projects/<chapter>/<slug>` | Members, leads | The "Your projects and access" email after a project change | The project: `GET /api/chapters/<chapter>/projects/<slug>` for its project leads and the chapter's roles; for a member without either (`403`), their own page (`GET /api/me/home`) |

## 6. Flows, step by step

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
4. GitHub, the form and the agreements can be done in any order. `ApplicantView.missing` lists in words what's still needed, e.g. `["your GitHub account", "your major", "“Why do you want to join?”"]`. Submit refuses with `400 "Still needed: …"` until it's empty. Use it to drive the checklist.
5. `githubConfigured: false` means GitHub sign-in isn't set up on the server: the applicant can't finish, so say so.
6. `agreements` lists the documents to sign (the volunteer agreement and the code of conduct). It's empty until national sets up a template and Documenso can send. Sign each one whose `status` isn't `signed`. When `status` is `sent`, `signUrl` resumes signing.
7. After submit, the status is `submitted` and chapter leads see it on Approvals. The applicant gets an email when it's decided. The session stays usable for viewing until then.

### Roster invite (a chapter lead uploads accepted members)

1. A chapter lead posts a CSV to `POST /api/chapters/{code}/roster`. Each new row gets an email with `/join/<chapter>?invite=<token>`, valid 14 days.
2. The page calls `POST /api/apply/roster {token}`, which sets the session. The school email is already proven (the link went there), and the name is pre-filled. The link can be opened again until the person finishes.
3. The rest is like Join: GitHub, details, agreements, submit.
4. On submit, if they match nobody already on record, they're approved at once (status `approved`, account created, session ended: show the outcome from the submit response). If they match someone, the application waits for a chapter lead like any other (`submitted`).

### Claim (imported members confirm their record)

```
GET  /api/national/claims/preview?chapter=umd      national: how many can be sent
POST /api/national/claims/send  {chapter, limit}   national: emails the links
POST /api/apply/claim           {token}            → ApplicantView (kind "claim") + h4i_applicant cookie
PUT  /api/apply/me              {details, answers} → ApplicantView, including stillActive
POST /api/apply/me/submit                          → ApplicantView, status "completed"; session ended
```

1. National previews, then sends links (up to 200 per call by default), or sends to one person with `POST /api/national/claims/people/{personId}`.
2. Each person gets an email at every address on file with `/claim/<token>`. It's single use and valid 14 days. Sending one person a new link stops their earlier one.
3. The page calls `POST /api/apply/claim {token}`, which sets the session. The link is used up at that moment, so a reload of `/claim/<token>` answers `410`: keep the person on `/apply`.
4. The form is pre-filled from their record. Claims ask `stillActive` (still in the chapter? if not, they're recorded as alumni) and no chapter questions. They sign the agreements like anyone joining.
5. On submit, no chapter lead is involved: the status becomes `completed`, their record is confirmed, a Google account is created if they need one, they get an email, and the session ends. Show the outcome from the submit response.

### Approvals (a chapter lead decides)

1. `GET /api/chapters/{code}/registrations` lists waiting applications (`submitted`) first, then those decided in the last 30 days. Claims never appear here.
2. Each waiting one has `matchStatus` and `matches`: people already on record who might be them, with `why` (e.g. `"same school email"`, `"same GitHub account"`, `"same personal email (not verified)"`) and their H4I `account` if they have one.
3. To **approve as a new person**: `POST …/approve {}`. To **approve as one of the matches** (a returning member or alumnus): `POST …/approve {"personId": "<match>"}`.
4. If a match already has an H4I address and the approval isn't into that record, the API answers `409` with the reason. Ask the lead, then send `"secondAccount": true` to approve anyway.
5. To **decline**: `POST …/decline {"reason": "…"}`. The reason is required and is emailed to the applicant.
6. Both answer with the updated list.

What approval does: the person becomes `active` in the chapter, their GitHub account is recorded, and they get an `@hack4impact.org` Google account if they have none (first name, then first.last and so on, in the chapter's org unit). If the account can't be created automatically, national gets a task on the admin queue instead, and the approval still succeeds. Then they're emailed (with the temporary password, if an account was made) and queued for the member database.

### Projects (a chapter lead runs a project)

```
GET  /api/project-roles, /api/partners, /api/chapters/{code}/terms      pick lists for the form
POST /api/chapters/{code}/projects                {name, …}      → 201 ProjectDetail, status "draft"
GET  /api/chapters/{code}/projects/{slug}/candidates             who can be added
POST /api/chapters/{code}/projects/{slug}/members {personId, roleId}
POST /api/chapters/{code}/projects/{slug}/status  {status: "active"}
GET  /api/chapters/{code}/sync                                   what the tools did, or would do
```

1. **Create.** The new project is a `draft` with the standard resources: a Slack channel and Google group for the team, and a GitHub team and Vaultwarden collection that wait for the project agreement.
2. **Build the team and adjust resources** while it's a draft. `ProjectDetail.preview` shows who would get access, as if it were active.
3. **Start it** by setting the status to `active`. It needs a partner (`422` otherwise). The project agreement goes out to everyone on the team, and to anyone added later.
4. **The tools follow.** Every change asks for a sync, which runs once the request commits. A tool that national still has in dry run only plans: `ProjectDetail.planned` and `GET /api/chapters/{code}/sync` show what it would do. `POST /api/chapters/{code}/sync/run` plans again on request.
5. **Each member** gets one email per change ("Your UMD projects and access") linking to `/projects/<chapter>/<slug>`. Their own page (`GET /api/me/home`) lists the project and each tool's `state` ([member access](#a-members-access-to-a-resource)). `after_agreement` means they must sign first: `POST /api/me/agreements/{id}/sign` gives the link, and Documenso returns them to `/agreements/<id>`.
6. **Pause or close.** `active` and `paused` switch back and forth; either can be `closed`. A closed project can't change (`409`).

A project lead can do the team calls and nothing else.

## 7. States and what changes them

Each table lists every move: the call that makes it, or, in *italics*, what makes it without a call (a timer, Documenso, a sync run). "—" is before the thing exists.

### Application

`ApplicantView.status` and `RegistrationSummary.status`.

| From | To | By |
| --- | --- | --- |
| — | `started` | `POST /api/apply/chapters/{code}/verify` (new), `POST /api/apply/claim` (claim), `POST /api/apply/roster` (roster invite) |
| `started` | `submitted` | `POST /api/apply/me/submit`: a new application, or a roster invitee who matches someone on record |
| `started` | `approved` | `POST /api/apply/me/submit`: a roster invitee who matches nobody |
| `started` | `completed` | `POST /api/apply/me/submit`: a claim |
| `submitted` | `approved` | `POST /api/chapters/{code}/registrations/{id}/approve` |
| `submitted` | `declined` | `POST /api/chapters/{code}/registrations/{id}/decline` |

`approved`, `declined` and `completed` are final and end the applicant's session.

### Roster invite

`RosterInviteView.status`.

| From | To | By |
| --- | --- | --- |
| — | `sent` | `POST /api/chapters/{code}/roster` |
| `sent` | `started` | `POST /api/apply/roster` (first opening) |
| `started` | `completed` | *Their application is approved* |
| `sent`, `started` | `expired` | *14 days pass* (an open invite past `expiresAt` is shown as `expired`) |

A new upload can invite someone whose invite expired.

### Claim link

There's no status field: the link is open until one of these happens. `POST /api/apply/claim` answers `410` for a used or expired link.

| From | To | By |
| --- | --- | --- |
| — | open | `POST /api/national/claims/send`, `POST /api/national/claims/people/{personId}` |
| open | used | `POST /api/apply/claim` (opens a claim application, above) |
| open | replaced | `POST /api/national/claims/people/{personId}` sends a new one |
| open | expired | *14 days pass* |

A month after a person's first link, if they still haven't claimed, a daily job records them as alumni.

### Project

`ProjectSummary.status` and `ProjectDetail.status`. Only `active` and `paused` projects give access.

| From | To | By |
| --- | --- | --- |
| — | `draft` | `POST /api/chapters/{code}/projects` |
| `draft` | `active` | `POST …/projects/{slug}/status {"status": "active"}`: needs a partner; sends the project agreement to the team |
| `active` | `paused` | `POST …/projects/{slug}/status {"status": "paused"}` |
| `paused` | `active` | `POST …/projects/{slug}/status {"status": "active"}` |
| `draft`, `active`, `paused` | `closed` | `POST …/projects/{slug}/status {"status": "closed"}` |

Any other move answers `409`. `closed` is final: every change to a closed project answers `409`.

### Agreement

An agreement is a document to sign in Documenso: the volunteer agreement and code of conduct while applying, and the project agreement for each live project someone is on.

| From | To | By |
| --- | --- | --- |
| — | `draft` | Applying: `POST /api/apply/me/agreements/{kind}`. Projects: *the project starts, or someone joins a live one* |
| `draft` | `sent` | *Documenso accepts it* (at once, normally) |
| `sent` | `signed` | *The person signs in Documenso*. The portal hears it from `POST /api/webhooks/documenso`, and also checks open ones every few minutes |
| `sent` | `declined` | *The person refuses in Documenso* |
| `draft`, `sent` | `voided` | *No longer needed*: the person left the project, the project closed, the application was declined or left unfinished for 30 days, or it was cancelled in Documenso |

`signed`, `declined` and `voided` are final. Each endpoint shows only some of the values:

| Where | Values |
| --- | --- |
| `ApplicantView.agreements[].status` | `not_started` (nothing created yet), `draft`, `sent`, `signed` |
| `MemberHome.agreements[]` (`MyAgreement`) | `sent`, `signed`, `declined`, `expired`. Drafts and voided ones aren't listed |
| `ProjectDetail.members[].agreement.status` | Any of `draft`, `sent`, `signed`, `declined`, `expired`, `voided` |

`expired` is allowed but nothing sets it today; show it like `declined` (not signed).

A project member's `agreementStatus` sums this up: `pending` (not signed yet), `signed`, or `waived` (excused from signing; set on the server, not through the API).

### A member's access to a resource

`MemberHome.access[].state`, and the same from `GET /api/chapters/{code}/members/{personId}/access`. Read only: the member's one action is signing.

| State | Means | Show the member | Moves on when |
| --- | --- | --- | --- |
| `not_connected` | The portal doesn't sync that tool yet | It will be set up later | *National connects the tool* → `setting_up` |
| `agreement_not_set_up` | It waits for the project agreement, but national hasn't set one up, or Documenso can't send | Waiting on national | *National adds a project template* → `after_agreement` |
| `after_agreement` | They must sign the project agreement first | A sign button: `POST /api/me/agreements/{id}/sign` | *They sign* → `setting_up` |
| `setting_up` | The portal will add them on its next run | Being set up | *A sync run* → `invited` or `ready` |
| `invited` | The tool sent them an invitation | Accept the invitation in your email | *They accept it* → `ready` |
| `ready` | They have it | A link (`url`) | |

Resources the portal leaves alone (unmanaged, or waiting for national's decision) aren't listed.

### A project's resource

`ProjectDetail.resources[].state` and `GET /api/chapters/{code}/resources`.

| State | Means |
| --- | --- |
| `to_create` | Only in the portal so far: the next applying run of that tool creates it |
| `exists` | Created in, or linked to, the tool |
| `archived` | Archived in the tool |

Admin tasks, the tool switches and resource linking are national's, and their states are laid out in [National](#85-national).

## 8. Endpoint reference

Each entry gives who may call it, the body, what it returns, its errors and its side effects. Types are in [Types and values](#9-types-and-values); who may call what is also in one table in [section 3](#3-who-may-call-what).

### 8.1 Public

#### `GET /api/status`

- **Who:** anyone. Use it as a health check, and to get the CSRF cookie.
- **Returns:** `{"service": "portal", "version": "dev"}`. `version` is the git SHA in deployed images.

`/actuator/health`, `/actuator/health/readiness` and `/actuator/info` are public too, for monitoring.

### 8.2 Signed-in members

#### `GET /api/me`

- **Who:** any signed-in Google account.
- **Returns:** who's signed in and which chapters they may open:
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
- **Errors:** `401`.

#### `GET /api/me/home`

- **Who:** any signed-in member.
- **Returns:** `MemberHome`, their own page:
  - status, chapters and tool accounts;
  - `agreements[]`: their agreements, with `project` (the project's name, for a project agreement), `canSign`, and `hasPdf` (the signed copy can be downloaded at `GET /api/agreements/{id}/pdf`);
  - `projects[]`: `{"chapterCode", "chapter", "slug", "name", "status", "role", "leads", "agreementStatus", "canOpen"}`, projects that aren't closed. `canOpen`: they're a project lead of it or have a role in its chapter, so `GET …/projects/{slug}` works for them;
  - `access[]`: `{"tool", "name", "url", "state", "access", "reasons"}`. `state` is in [member access](#a-members-access-to-a-resource). `reasons` in words: "On RISE DC", "Member of UMD", "Lead of UMD".
- **Errors:** `401`; `404` "Your Google account isn't linked to a member yet".

#### `POST /api/me/agreements/{id}/sign`

- **Who:** the agreement's signer. Anyone else gets `404`.
- **Body:** none.
- **Returns:** `{"url"}`: a fresh Documenso signing link for one of their own unsigned agreements. Send them there; Documenso brings them back to `/agreements/<id>`.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `404` "No such agreement of yours" | Not theirs, or no such agreement |
  | `409` "…use the link in your email" | Documenso can't give a link now: it's signed already, or Documenso isn't connected |

#### `GET /api/tools`

- **Who:** any signed-in member.
- **Returns:** the tools people have accounts in, in display order. Use it to label and sort `accounts` lists.
  ```json
  [{"key": "slack", "name": "Slack", "order": 1}, {"key": "google", "name": "Google", "order": 2},
   {"key": "github", "name": "GitHub", "order": 3}, {"key": "notion", "name": "Notion", "order": 4},
   {"key": "vaultwarden", "name": "Vaultwarden", "order": 5}]
  ```

### 8.3 Chapters: members, approvals, settings, roster

All need Google sign-in and a role in the chapter. An unknown chapter is `404` "No chapter {code}"; no role is `403`.

#### `GET /api/chapters/{code}`

- **Who:** viewer or chapter lead.
- **Returns:** `ChapterOverview`: the chapter, the caller's role, counts, and its public registration link.
  ```json
  {
    "id": "1f2e…", "code": "umd", "name": "Hack4Impact UMD", "status": "active", "role": "lead",
    "stats": {"activeMembers": 3, "alumni": 1, "waitingApplications": 0, "leads": 1},
    "registrationLink": "https://join.hack4impact.org/umd"
  }
  ```
  `activeMembers` counts `active` and `removal_requested`. `waitingApplications` counts applications waiting for a chapter lead.

#### `GET /api/chapters/{code}/members`

- **Who:** viewer or chapter lead.
- **Returns:** `MemberRow[]`: the chapter's members, people with a chapter role first, then active people, then by name.
  - `email` is the H4I address, else the school address. Personal emails are never returned.
  - `claimable: true` with `claimLinkSentAt` lets national offer "Send claim link" per row (`POST /api/national/claims/people/{personId}`).

#### `GET /api/chapters/{code}/registrations`

- **Who:** viewer or chapter lead.
- **Returns:** `RegistrationSummary[]`: applications waiting (`submitted`), then those approved or declined in the last 30 days. Claims aren't included. `answers` is keyed by question label; `matches` is filled only for waiting ones.

#### `POST /api/chapters/{code}/registrations/{id}/approve`

- **Who:** chapter lead.
- **Body:** `ApproveInput`:
  ```json
  {"personId": null, "secondAccount": false}
  ```
  - `personId`: approve as this matched record, which must be one of its `matches`. Leave it `null` (or send `{}`) to approve as a new person.
  - `secondAccount`: the lead confirmed a second H4I account (see [Approvals](#approvals-a-chapter-lead-decides)).
- **Returns:** the updated `RegistrationSummary[]`.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `400` "Approve as a new person or as one of the matches" | `personId` isn't one of the matches |
  | `404` "Not this chapter's" / "No such registration" | Wrong chapter or ID |
  | `409` "This application is approved" (or `declined`, `started`, …) | It isn't waiting |
  | `409` "Ada Lovelace already has an H4I account (ada@…). Approve as that record, or confirm that they should get a second account." | A match has an address, and `secondAccount` isn't `true` |

  To tell the two `409`s apart, reload the list: if the application is still `submitted`, it's the second-account one. Ask the lead and resend with `"secondAccount": true`.
- **Side effects:** [what approval does](#approvals-a-chapter-lead-decides). The applicant's session ends. Audited.

#### `POST /api/chapters/{code}/registrations/{id}/decline`

- **Who:** chapter lead.
- **Body:** `{"reason": "We're full this semester"}`.
- **Returns:** the updated `RegistrationSummary[]`.
- **Errors:** `400` "Say why: the applicant gets the reason by email (PRD)" for a blank reason; `404`; `409` when it isn't waiting.
- **Side effects:** emails the reason to the applicant's personal and school addresses; ends their session; audited.

#### `GET /api/chapters/{code}/registration-settings`

- **Who:** viewer or chapter lead.
- **Returns:** `RegistrationSettings`:
  ```json
  {
    "questions": [{"id": "9a…", "label": "Why do you want to join?", "kind": "long_text", "options": [], "required": true}],
    "googleDomain": "umd.hack4impact.org", "googleOrgUnit": "/Chapters/UMD",
    "applicationsOpen": true, "applicationUrl": null,
    "canEditQuestions": true, "canEditAccounts": false
  }
  ```
  - `canEditQuestions`: the caller is a chapter lead.
  - `canEditAccounts`: the caller is national (only national sets `googleDomain` and `googleOrgUnit`).
  - `googleDomain` defaults to `<code>.hack4impact.org`.

#### `PUT /api/chapters/{code}/registration-settings`

- **Who:** chapter lead.
- **Body:** `RegistrationSettingsInput`:
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
  - `googleDomain` and `googleOrgUnit` are applied only for national, and ignored for chapter leads. Blank clears them.
- **Returns:** `RegistrationSettings`.
- **Errors:** `400` "Question 2 needs a label"; "Question 2: unknown kind"; "Question 2: a choice needs at least two options"; "The application link should start with https://".
- **Side effects:** audited.

#### `GET /api/chapters/{code}/roster`

- **Who:** viewer or chapter lead.
- **Returns:** `RosterInviteView[]`, the latest 500 roster invites, newest first. `status` is in [Roster invite](#roster-invite).

#### `POST /api/chapters/{code}/roster`

- **Who:** chapter lead.
- **Body:** `{"csv": "<the file's text>"}`. The first line is the header; column names are case-insensitive, and spaces count as `_`.
  ```csv
  first_name,last_name,school_email,personal_email
  Ada,Lovelace,ada@umd.edu,ada@example.com
  Alan,Turing,alan@umd.edu,
  ```
  `personal_email` is optional. Quoted cells (`"Smith, Jr."`) are allowed. At most 500 rows.
- **Returns:** `RosterResult`:
  ```json
  {"invited": 1, "skipped": ["row 3: alan@umd.edu is already invited"], "invites": [ … all invites, as GET … ]}
  ```
  Rows are skipped, not failed: missing name or school email, a bad address, or an address with an open invite. An expired invite doesn't block a new one.
- **Errors:** `400` "The roster is empty"; `400` "The first line needs columns first_name, last_name, school_email (and optionally personal_email)"; `400` "At most 500 people per roster".
- **Side effects:** each invited row is emailed a personal link (after the request commits, in the background); audited.

### 8.4 Projects and sync

Projects, their teams and resources. The order of calls is in [Projects](#projects-a-chapter-lead-runs-a-project), the statuses in [Project](#project).

Rules for every endpoint here:

- **Every change** answers with the updated `ProjectDetail` and asks for a sync in the same transaction; the tools follow once it commits.
- **Every change emails** everyone whose projects or access it changed, one summary each ("Your UMD projects and access", linking to `/projects/<chapter>/<slug>`).
- **A closed project can't change:** `409` "This project is closed".

#### Pick lists for the project form

| Endpoint | Who | Returns |
| --- | --- | --- |
| `GET /api/project-roles` | Any signed-in member | `[{"id", "name", "isLead"}]`, lead roles first. Role names are the member database's; the frontend chooses labels |
| `GET /api/partners` | A chapter lead of any chapter | `[{"id", "name", "website"}]` |
| `GET /api/chapters/{code}/terms` | Chapter lead | `[{"id", "label"}]`: the school's semesters, newest first |
| `GET /api/chapters/{code}/past-projects` | Chapter lead | `[{"id", "name"}]`: the member database's projects with no engagement, read live, that no portal project links yet. `503` when the member database isn't connected or can't be reached |
| `GET /api/chapters/{code}/resources` | Chapter lead | `[{"id", "tool", "name", "state", "projects"}]`: the chapter's resources, to attach one to another project |

#### `POST /api/national/partners`

- **Who:** national.
- **Body:** `{"name", "website"?}`: a partner that's missing from the list.
- **Returns:** the partner.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `409` | The name exists already, in any letter case |
  | `409` "Restore it there…" | The name was deleted in the member database (the portal follows the member database) |
  | `422` | A blank name |

#### `GET /api/chapters/{code}/projects`

- **Who:** viewer or chapter lead.
- **Returns:** `ProjectSummary[]`, active first: `{"id", "name", "slug", "status", "type", "tags", "term", "partner", "members", "resources", "updatedAt"}`.

#### `POST /api/chapters/{code}/projects`

- **Who:** chapter lead.
- **Body:** `ProjectInput`: `name`, and optionally `slug`, `type`, `tags`, `termId`, `partnerId`, `pastProjectId`, `startsOn`, `endsOn`.
- **Returns:** `201` with the `ProjectDetail`: a `draft` with the standard resources (a Slack channel and Google group for the team; a GitHub team and Vaultwarden collection after the project agreement).
- **Errors:**

  | Error | When |
  | --- | --- |
  | `409` | The short name (`slug`) is taken |
  | `422` | An unknown or deleted `partnerId` |
  | `422` | A `pastProjectId` that has an engagement, isn't in the member database, or is linked already |
  | `400` | Other bad fields |

#### `GET /api/chapters/{code}/projects/{slug}`

- **Who:** viewer or chapter lead, or a project lead of this project.
- **Returns:** `ProjectDetail`: `id`, `name`, `slug`, `status`, `type`, `tags`, `term` and `partner` as in `ProjectSummary`, plus:

  | Field | Meaning |
  | --- | --- |
  | `termId`, `partnerId`, `pastProjectId`, `startsOn`, `endsOn` | What the form edits |
  | `members[]` | The team, see below |
  | `resources[]` | Its resources, see below |
  | `agreementsSigned` | How many members signed the project agreement (or were waived from it), out of `members` |
  | `preview` | Who would get access: `{"people", "grants", "awaitingAgreement", "asIfActive"}`. `asIfActive`: it's a draft, counted as if it were active |
  | `planned` | Each tool's latest run for its resources: what a dry run would change, or what an applying run did. Shaped as `changes` in [`ChapterSync`](#get-apichapterscodesync) |
  | `lastDryRunAt`, `plannedOutOfDate` | The latest run's time, and whether the project changed after it (so `planned` may be out of date; also `true` when there's been no run) |
  | `activity`, `activityMore` | The latest few [activity](#get-apichapterscodeprojectsslugactivity) items, and whether there are older ones |
  | `canEdit`, `canEditTeam` | What the caller may change: the project (chapter leads); its team (chapter leads and the project's own leads) |

  `members[]`, one per person on the team:

  | Field | Meaning |
  | --- | --- |
  | `personId`, `name`, `email` | Who |
  | `status` | Their status in H4I: `active`, `alumni`, … |
  | `roleId`, `role`, `isLead` | Their project role, and whether it's a lead role |
  | `agreementStatus` | `pending`, `signed` or `waived` |
  | `agreement` | Their latest project agreement, or `null`: `{"id", "status", "sentAt", "signedAt", "reminders", "hasPdf", "error", "declineReason"}`. `status` is in [Agreement](#agreement); `reminders` is how many reminders went out; `error` says in words why it couldn't be sent or read |

  `resources[]`, one per resource attached to the project:

  | Field | Meaning |
  | --- | --- |
  | `resourceId`, `tool`, `name` | Which resource |
  | `state` | `to_create`, `exists` or `archived` ([A project's resource](#a-projects-resource)) |
  | `managed` | `portal` (the portal made it), `adopted` (national linked an existing one) or `unmanaged` (the portal leaves it alone) |
  | `audience`, `roleId`, `role` | Who on the project gets it: `team`, `role` (one project role) or `leads` (the project's leads) |
  | `access` | `read`, `write` or `admin` |
  | `requiresAgreement` | They must sign the project agreement before they get it. On by default for the GitHub team and vault collection; chapter leads can switch it off |
  | `tags` | Free labels |
  | `sharedWith` | The names of other projects using the same resource |
  | `existsInTool` | The tool already has this name: national must link it, rename the portal's, or leave it ([resource linking](#resources-linking-existing-ones)) |
  | `getting` | How many people should have it |
  | `awaitingAgreement` | How many of them wait for the project agreement |
  | `url` | Where to open it in the tool, once it exists there |
  | `sync` | How the sync stands, counted by person: `{"inSync", "waitingOnMember", "manualTask", "pending", "failing"}`. `waitingOnMember`: invited, not accepted yet; `manualTask`: waiting on a task for national |

#### `PUT /api/chapters/{code}/projects/{slug}`

- **Who:** chapter lead.
- **Body:** the create body without `slug`. It replaces every field, so send them all.
- **Returns:** `ProjectDetail`.
- **Errors:** `422` changing `pastProjectId` after the draft; `422` removing the partner of a started project; `422` a partner that's unknown or deleted (checked only when it changes); `409` a closed project.

#### `POST /api/chapters/{code}/projects/{slug}/status`

- **Who:** chapter lead.
- **Body:** `{"status": "active"}`, or `"paused"` or `"closed"`. The allowed moves are in [Project](#project).
- **Returns:** `ProjectDetail`.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `409` | A move that isn't allowed |
  | `422` "Choose the project's partner before starting it" | Starting without a partner |
  | `422` | Starting with a partner deleted in the member database since it was picked |
- **Side effects:** starting (→ `active`) sets `startsOn` to today if it's empty, and sends the project agreement to the team.

#### Team

- **Who:** chapter lead, or a project lead of this project.
- **Returns:** each change returns `ProjectDetail`.

| Endpoint | Body | Does |
| --- | --- | --- |
| `GET /api/chapters/{code}/projects/{slug}/candidates` | | Returns `[{"personId", "name", "status"}]` instead: the chapter's active members and alumni not on the team |
| `POST /api/chapters/{code}/projects/{slug}/members` | `{"personId", "roleId"}` | Adds them. On a live project, the project agreement goes out; resources that need it wait for the signature |
| `PUT /api/chapters/{code}/projects/{slug}/members/{personId}` | `{"roleId"}` | Changes their role |
| `DELETE /api/chapters/{code}/projects/{slug}/members/{personId}` | | Takes them off; an unsigned project agreement is voided |

| Error | When |
| --- | --- |
| `400` "Choose the person's project role" | No `roleId` |
| `400` | Someone not in the chapter, or not active or alumni |
| `409` | Already on the team |
| `404` | Not on the team |

#### Resources of a project

- **Who:** chapter lead.
- **Returns:** each returns `ProjectDetail`.

**`POST /api/chapters/{code}/projects/{slug}/resources`** attaches a resource. Body `ResourceInput`, one of:

- `{"resourceId"}`: share one the chapter already has (from `GET /api/chapters/{code}/resources`).
- `{"tool", "name"}`: a new one.

Both take who gets it: `audience` (`team`, `role` with `roleId`, or `leads`), `access` (`read`, `write`, `admin`), `requiresAgreement` and `tags`.

**`PUT /api/chapters/{code}/projects/{slug}/resources/{resourceId}`** changes who gets it.

**`DELETE /api/chapters/{code}/projects/{slug}/resources/{resourceId}`** detaches it. One that was never created and that nothing else uses is dropped.

| Error | When |
| --- | --- |
| `400` | A tool the portal doesn't create resources in |
| `400` | A name the tool doesn't allow |
| `400` | For anyone but national: a new name that doesn't start with `<chapter code>-` |
| `409` | Already attached, or the chapter has that name already |

#### `GET /api/chapters/{code}/projects/{slug}/activity`

- **Who:** as reading the project.
- **Query:** `?before=&limit=20`.
- **Returns:** newest first:
  ```json
  {"items": [{"at": "…", "kind": "change", "text": "…", "actor": "Lena Test", "details": []}], "more": false}
  ```
  - `kind`: `change` (someone edited the project), `sync` (the tools were changed) or `agreement` (signed or declined).
  - `actor`: who did it; `null` for the sync and for people's own signatures.
  - `details`: more lines behind the item (a big sync, by person), to show when it's expanded.

#### `GET /api/chapters/{code}/members/{personId}/access`

- **Who:** chapter lead.
- **Returns:** the member's `access[]`, as on [`GET /api/me/home`](#get-apimehome), for this chapter's resources.
- **Errors:** `404` when they aren't a member of the chapter.

#### `GET /api/chapters/{code}/sync`

- **Who:** chapter lead.
- **Returns:** `ChapterSync`: the latest sync run of each tool, counted for this chapter's resources, and up to 200 of its changes.
  ```json
  {"tools": [{"tool": "slack", "status": "completed", "mode": "dry_run", "ranAt": "2026-11-01T14:00:00Z", "error": null,
              "adds": 2, "changes": 0, "removals": 0, "drift": 1, "unmatchedAccounts": 1, "missingResources": 0, "creates": 1, "existing": 0}],
   "changes": [{"kind": "add", "tool": "slack", "resource": "umd-rise-dc", "person": "Ada Lovelace", "accountId": null,
                "fromAccess": null, "toAccess": "write", "mode": "dry_run", "outcome": null}]}
  ```
  - `status`: `completed`, `paused` (a real run would stop at the removal limit, or the tool is switched off) or `failed`.
  - `mode`: `dry_run` (planned only) or `apply`.
  - `kind`: `add`, `change`, `remove`, `drift` (access the portal didn't give; never removed), `unmatched_account`, `missing_resource`, `create_resource`, `archive_resource`, `resource_exists` (the tool already has the name; national decides).
  - `outcome` (apply runs only): `applied`, `invited`, `waiting`, `queued` (a task for national), `held` (at the removal limit), `failed`, `dead_letter` (failed five times), `skipped`.

#### `POST /api/chapters/{code}/sync/run`

- **Who:** chapter lead.
- **Returns:** `202`. Plans every tool now without changing any, whatever national's switches say. Read the result with `GET …/sync`.

### 8.5 National

All need national; anyone else gets `403`.

#### Admin queue

Things the portal couldn't do itself, for a person to do by hand.

- **`GET /api/national/queue`** → `QueueTask[]`: open tasks, plus those finished in the last 7 days (up to 500). `runId` and `resourceId` are set on sync tasks. Show `description`, which says what to do.
- **`POST /api/national/queue/{id}`** with `{"action": "done"}`, or `{"action": "cancel", "reason": "Duplicate"}`. Returns the updated list. Audited.

| `action` of the task | What it is | `done` does | Closes itself |
| --- | --- | --- | --- |
| `create_account` | A Google account couldn't be created, e.g. the chapter has no org unit yet | Marks it made by hand | |
| `check_account` | Something to check, e.g. a GitHub account linked to two people | Marks it checked | |
| `confirm_removals` | A sync run held its removals at the limit | Lets the removals go ahead. Send the task's `runId` with it: `{"action": "done", "runId": "…"}` | |
| `create_resource` | Creating a channel, group or team kept failing | Tries again | |
| `add_member`, `remove_member` | A change the portal can't make in that tool | Records it as made | |
| `missing_resource` | A channel, group or team is gone from its tool; nobody is added to it until it's back | Closes it until the next run | When the resource is back |
| `resource_exists` | The tool already has the name the portal wanted | Not allowed (`409`): decide with the [resource endpoints](#resources-linking-existing-ones) | When national decides |

Removals that follow a chapter lead closing a project or taking someone off a team are never held for `confirm_removals`.

Every task moves from `open` to `done` (`action: done`, or closing itself) or `cancelled` (`action: cancel`, which needs a `reason`).

| Error | When |
| --- | --- |
| `400` "action must be done or cancel" | Another action |
| `404` "No such task" | |
| `409` "This task is already done" | It isn't open |
| `409` "Newer removals were held since this page loaded…" | A `confirm_removals` whose `runId` isn't the latest |
| `409` "Decide about the resource instead…" | A `resource_exists` |

#### Sync controls

The syncing tools are Slack, Google, GitHub and Vaultwarden. Each has three positions, and every tool starts in dry run:

| Position | `enabled` | `dryRun` | The tool |
| --- | --- | --- | --- |
| Dry run | `true` | `true` | Is planned, never changed |
| Applying | `true` | `false` | Is changed |
| Paused | `false` | | Is left alone; `pauseReason` says why |

- **`GET /api/national/tools`** → `ToolStatus[]` in list order: `{"tool", "enabled", "dryRun", "writable", "pauseReason", "maxRemovals", "lastRun": {"status", "mode", "ranAt", "applied", "removed", "error"} | null}`. `writable`: this deployment has the tool's write switch (`PORTAL_ADAPTERS_<TOOL>_WRITE`).
- **`PUT /api/national/tools/{tool}`** with `{"enabled": true, "dryRun": false, "reason": null}` → the list. Audited.
  - `404` a tool that doesn't sync.
  - `409` leaving dry run without the write switch.
  - `400` "Say why the tool is paused" when `enabled` is false without a `reason`.
- **`POST /api/national/sync/run`** → `202`: every tool, every chapter, as the switches say.
- **`GET /api/national/dead-letters`** → `DeadLetter[]`: `{"personId", "person", "resourceId", "tool", "resource", "attempts", "lastError", "lastAttemptAt"}`, changes that failed five times.
- **`POST /api/national/dead-letters/retry`** with `[{"personId", "resourceId"}]`, or `[]` for all → the list. The next run tries them again. Audited.

#### Resources (linking existing ones)

When a project's channel, group or team already exists, or for a chapter's own channels, national ties the portal's resource to the existing one. A linked resource only gains people: everyone already in it stays. All of these are audited.

The usual order: list, add the existing one if it isn't listed, preview the link, then link with the preview's count.

A resource's `managed` value moves like this:

| From | To | By |
| --- | --- | --- |
| — | `unmanaged` | `POST /api/national/chapters/{code}/resources` (registers an existing one) |
| `unmanaged`, or waiting with `existsInTool` | `adopted` | `POST /api/national/resources/{id}/link {"action": "link"}` |
| waiting with `existsInTool` | `portal` (under a new name) | `POST /api/national/resources/{id}/link {"action": "rename"}` |
| waiting with `existsInTool` | `unmanaged` | `POST /api/national/resources/{id}/link {"action": "unmanaged"}` |
| `adopted` | no longer any project's or chapter's | `DELETE /api/national/resources/{id}?mode=detach` |
| `adopted` | `unmanaged` | `DELETE /api/national/resources/{id}?mode=unmanaged` |

**`GET /api/national/chapters/{code}/resources`** → `NationalResource[]`: `{"id", "tool", "name", "externalId", "managed", "target", "projectSlug", "access", "existsInTool"}`.

- `managed`: `portal`, `adopted` (linked) or `unmanaged`.
- `target`: `project_team`, `chapter_members`, `chapter_leads` or `null`.
- `existsInTool`: the tool has the name and national hasn't decided.

**`POST /api/national/chapters/{code}/resources`** registers an existing one.

- **Body:** either `{"tool": "slack", "name": "#umd"}` or `{"tool": "slack", "externalId": "C0123"}`.
- **Returns:** the new `NationalResource`, `unmanaged` until linked.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `422` | Both or neither of `name` and `externalId` |
  | `404` | The tool doesn't list it (for Slack, the message says to invite the portal's app to a private channel) |
  | `409` | Already linked |
  | `503` | The portal isn't connected to the tool, or can't read it |

**`GET /api/national/resources/{id}/link?target=project_team&projectSlug=rise-dc&access=write`** previews a link, read live from the tool. `access` defaults to `write`.

```json
{"resource": {"tool": "slack", "name": "#umd-rise-dc", "externalId": "C0123"},
 "people": [{"personId": "6b0c…", "account": "U0456", "name": "Ada Lovelace", "verdict": "will_add"}],
 "willAdd": 1}
```

- `personId` and `name` are `null` for an account the portal can't match to a person.
- `verdict`: `will_add`, `already_there`, `kept` (in it but not in the target: they stay) or `unknown_account`.
- On a resource that needs the project agreement, people who haven't signed aren't in `will_add`.
- **Errors:** `404` the tool no longer lists it; `422` a target that doesn't fit (unknown, or a project not in the resource's chapter).

**`POST /api/national/resources/{id}/link`** decides. Returns the `NationalResource`, and the resource's `resource_exists` task closes. The body's `action` is one of:

| `action` | Body | Does | Errors |
| --- | --- | --- | --- |
| `link` | `{"action": "link", "target": "project_team", "projectSlug": "rise-dc", "access": "write", "confirmedAdds": 1}` | Ties the resource to the existing one. `confirmedAdds` is the preview's `willAdd`. A run of that tool starts after the request | `409` the count changed (preview again), or the existing one is linked to another resource; `422` a missing field, already linked, or a resource the portal created itself |
| `rename` | `{"action": "rename", "newName": "umd-rise-dc-2026"}` | The portal creates its own under the new name; the existing one is left alone | `422` a name the tool doesn't allow, or a resource the portal wasn't about to create |
| `unmanaged` | `{"action": "unmanaged"}` | The portal leaves it alone | `422` a resource the portal created itself (it isn't waiting for a decision) |

After `link`: a project's standard resource keeps its audience, level and agreement requirement; one national registered gets the team, and the agreement requirement on GitHub and Vaultwarden.

**`DELETE /api/national/resources/{id}?mode=detach|unmanaged`** → `204`.

- `detach`: it's no longer any project's or chapter's; the next run takes back what the portal added.
- `unmanaged`: the portal stops touching it and removes no one.
- **Errors:** `422` a resource that isn't linked or a chapter resource, or another mode.

#### Claim links

- **`GET /api/national/claims/preview?chapter=umd`** → `ClaimPreview`. Leave out `chapter` for every chapter.
  ```json
  {"eligible": 42, "alreadySent": 10, "withoutEmail": 3}
  ```
  - `eligible`: claimable, without an open link, and with an address.
  - `alreadySent`: they have an open link.
  - `withoutEmail`: no address on file, so a link can't be sent.
- **`POST /api/national/claims/send`** with `{"chapter": "umd", "limit": 200}`. Both are optional; `chapter: null` means every chapter. Sends links to up to `limit` people (kept between 1 and 1000) who don't have an open one. Returns the new `ClaimPreview`. Emails go out after commit; audited.
- **`POST /api/national/claims/people/{personId}`** sends one person a new link, ending their earlier one.
  - Returns `{"addresses": 2, "expiresAt": "…"}`: how many addresses it went to, and until when it works.
  - `404` "No such person".
  - `409`: "Ada already claimed their record", "Ada joined through the portal, so there's no imported record to claim", "Ada was removed", or "Ada has no email address on file".

Who can be claimed: imported from the member database, not claimed yet, not removed, in the chapter. The one-month claim deadline counts from a person's first link. The link's states are in [Claim link](#claim-link).

#### Member database

The national member database: the portal pulls from it on a schedule, writes back what people confirm and what changes on projects and chapter leadership, and reports fields changed on the other side ("drift").

It has the same three positions as a tool (dry run, writing, paused), set with `PUT /api/national/member-db`. Each queued write is `pending`, then `done`, or `failed` after its tries run out; a retry puts a `failed` one back to `pending`.

- **`GET /api/national/member-db`** → `MemberDbStatus`:
  - `configured`, `writeSwitch` and `scheduledPull`: server settings.
  - `dryRun` and `paused` (with `pauseReason`): switches national controls.
  - `projectsEnabled`: whether project and leadership writes are sent.
  - The last pull's time, status and error.
  - `waiting` and `failed` person writes (up to 500 each), and `drift`.
  - `projectWrites` and `leadershipWrites`: `[{"id", "name", "state", "requestedAt", "attempts", "nextTryAt", "lastError", "waitingOn"}]`, waiting or failed. `id` is the project's or the chapter role's; `waitingOn` names the people whose own records must be written first.
- **`PUT /api/national/member-db`** with `{"dryRun": true, "paused": false, "reason": null, "projectsEnabled": false}` → the status. Audited.
  - `projectsEnabled` left out keeps its value. While it's off, project and leadership writes are queued and previewable but not written; person writes go on. Turning it on the first time queues every open chapter lead and co-lead.
  - `409` "The write switch (PORTAL_MEMBERDB_WRITE) isn't set on this server, so it stays in dry run": leaving dry run without the server switch.
  - `400` "Say why it's paused": `paused: true` without a reason.
- **`POST /api/national/member-db/pull`** pulls now, even if nothing seems changed. Audited. Returns `PullResult`:
  ```json
  {"status": "completed", "counts": {"institution": 0, "term": 2, "chapter": 0, "person": 3, "chapter_membership": 3, "tool_account": 5}, "drift": 0, "error": null}
  ```
  `status` is `completed`, `unchanged` (nothing changed, so `counts` is empty) or `failed` (`error` says why). `counts` is rows inserted or updated per kind; `drift` is the number of drift reports. `409` when the member database isn't configured.
- **`GET /api/national/member-db/writes/{personId}/preview`** shows what would be written for one person. Returns `MemberDbPreview`:
  ```json
  {"newVolunteer": false, "fields": {"volunteers.email": "ada@example.com"}, "notes": ["…"]}
  ```
  `409` when not configured, or when the person can't be written (the reason is in `detail`).
- **`POST /api/national/member-db/writes/{personId}/retry`** puts a failed write back in line with a fresh attempt count. Returns the status. `404` "Nothing to write for them". Audited.
- **`GET /api/national/member-db/projects/{id}/preview`** and **`GET /api/national/member-db/leadership/{chapterRoleId}/preview`** → `{"fields", "notes", "waitingOn"}`: what a project's write (its `projects` row, engagement, team and, the first time, its partner) or a chapter lead's write would send now. A draft project, or a viewer's role, has a note and no fields. `waitingOn` names who must be written first. `404` unknown; `409` not configured, or a write that can't be made (the reason is in `detail`).
- **`POST /api/national/member-db/projects/{id}/retry`** and **`POST /api/national/member-db/leadership/{chapterRoleId}/retry`** → `204`: back in line with a fresh count. `404` "Nothing to write for that project" or "…that role". Audited.

#### Agreement templates

The Documenso templates agreements are made from: one active template per kind, and a new one becomes the next version. Kinds: `volunteer` and `code_of_conduct` (signed while applying) and `project` (sent when someone joins a live project, or when a project with them on it starts). Agreements go out only while `sending` is true, for every kind.

- **`GET /api/national/agreement-templates`** → `AgreementTemplates`: `templates[]` (newest version first per kind, with `sent` counts), `connected` (Documenso is reachable with a token) and `sending` (its write switch is on, so agreements actually go out).
- **`POST /api/national/agreement-templates`** with `{"kind": "volunteer", "name": "Volunteer agreement", "documensoTemplateId": "envelope_abc123"}`. Audited.
  - `documensoTemplateId` may be the ID or a link to the template.
  - The template is checked in Documenso: it must exist and have a signer.
  - The new template becomes the active one for its kind.
  - `400`: "Kind is volunteer, code_of_conduct or project", the name is missing, "Paste the template's ID or link from Documenso", or "Documenso doesn't have a usable template …".
- **`POST /api/national/agreement-templates/{id}/deactivate`** stops making agreements from it; signed ones stay valid. `404` "No such template". Audited.

### 8.6 Applicants (`/api/apply`)

These use the applicant session, not Google. The first five are public; the rest answer `401` without a session. All `POST` and `PUT` calls need the CSRF header. The application's statuses are in [Application](#application).

#### `GET /api/apply/chapters/{code}`

- **Who:** anyone.
- **Returns:** `ApplyChapter`, what the join page shows:
  ```json
  {"code": "umd", "name": "Hack4Impact UMD", "emailDomains": ["umd.edu"],
   "questions": [{"id": "9a…", "label": "Why do you want to join?", "kind": "long_text", "options": [], "required": true}],
   "applicationsOpen": true, "applicationUrl": null}
  ```
  - Only chapters people can join (status `active`, `supported` or `pending`); others are `404`.
  - `questions` is empty when `applicationsOpen` is `false`.

#### `POST /api/apply/chapters/{code}/code`

- **Who:** anyone.
- **Body:** `{"email": "ada@umd.edu"}`.
- **Returns:** `202` with no body, and emails a 6-digit code valid for 10 minutes.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `400` "That doesn't look like an email address" | |
  | `400` "Use your school address (@umd.edu)" | Not one of the chapter's domains |
  | `403` "Hack4Impact UMD runs its own application process: apply at https://…" | Applications are off |
  | `404` | No such chapter, or not open to joining |
  | `429` | 5 codes per address, or 30 per IP address, in an hour |

#### `POST /api/apply/chapters/{code}/verify`

- **Who:** anyone.
- **Body:** `{"email": "ada@umd.edu", "code": "123456"}`.
- **Returns:** `ApplicantView`, and sets `h4i_applicant`.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `400` "That code isn't right" | Counts as a wrong attempt |
  | `400` "Too many wrong codes. Ask for a new one." | 5 wrong attempts on this code |
  | `400` "That code has expired. Ask for a new one." | Older than 10 minutes, or none sent |
  | `403` / `404` | As for sending a code |
  | `429` | 60 tries per IP address in an hour |

#### `POST /api/apply/claim`

- **Who:** anyone with a claim link.
- **Body:** `{"token": "<from /claim/<token>>"}`.
- **Returns:** `ApplicantView` (`kind: "claim"`), and sets the session. Uses the link up.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `404` "This link isn't valid. Ask national for a new one." | |
  | `410` "This link was already used." / "This link has expired." / "This record is already claimed. Sign in with your Hack4Impact Google account." | |
  | `409` "This record has no chapter. Ask national." | |
  | `429` | 60 links per IP address in an hour |

#### `POST /api/apply/roster`

- **Who:** anyone with a roster invite.
- **Body:** `{"token": "<from ?invite=>"}`.
- **Returns:** `ApplicantView` (`kind: "roster"`), and sets the session. It can be opened again until the person finishes, and returns the same application.
- **Errors:**

  | Error | When |
  | --- | --- |
  | `404` "This invite isn't valid. Ask your chapter's leads." | |
  | `410` "You've already joined. Sign in with your Hack4Impact Google account." / "This invite has expired. Ask your chapter's leads for a new one." | |
  | `429` | As for claims |

#### `GET /api/apply/me`

- **Who:** applicant session.
- **Returns:** their `ApplicantView`.
- **Errors:** `401` once the session has ended, which includes every decided application (see [Applicant sessions](#applicant-sessions)).

#### `PUT /api/apply/me`

- **Who:** applicant session, while `status` is `started`. Save as often as you like.
- **Body:** `DetailsInput`:
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
- **Returns:** `ApplicantView`, with `missing` recomputed.
- **Errors:** `400` "That personal email doesn't look right"; "Graduation year should look like 2028" (outside 2000–2100); "Graduation season is spring, summer, fall or winter"; "Major and degree type are at most 150 characters". `409` "This application was already sent".

#### `GET /api/apply/github/start`

- **Who:** applicant session. **Navigate the browser here** (`window.location.assign`); don't `fetch` it.
- **Does:** redirects to GitHub (no scopes; it only reads who they are). GitHub comes back to `PORTAL_BASE_URL/api/apply/github/callback`, which records the account and redirects to `PORTAL_FRONTEND_URL/apply`, or `/apply?github=failed`.
- **Errors:** `503` if GitHub sign-in isn't configured.

For a claim, linking a GitHub account different from the one on record sets `githubChanged`, which chapter leads and national see.

`GET /api/apply/github/callback` is GitHub's return address. Don't call it yourself.

#### `POST /api/apply/me/agreements/{kind}`

- **Who:** applicant session. `kind` is `volunteer` or `code_of_conduct`, as listed in `ApplicantView.agreements`.
- **Returns:** where to sign, after preparing the document in Documenso: `{"url": "https://sign.hack4impact.org/sign/…"}`. Navigate there; Documenso returns to `PORTAL_FRONTEND_URL/apply?signed=<kind>`.
- **Errors:** `400` "Save your name first: it goes on the agreement"; `400` "No volunteer agreement to sign"; `409` "Already signed", "The agreement couldn't be prepared. Try again in a moment.", or "This application was already sent".

#### `POST /api/apply/me/submit`

- **Who:** applicant session.
- **Body:** none.
- **Returns:** `ApplicantView`:

  | Kind | Result `status` | Then |
  | --- | --- | --- |
  | `new` | `submitted` | Waits for a chapter lead |
  | `roster`, matching nobody | `approved` | Account created, email sent, session ended |
  | `roster`, with a match | `submitted` | Waits for a chapter lead |
  | `claim` | `completed` | Record confirmed, account created if needed, email sent, session ended |

  When the session ends, this response is the last one: show the outcome from it.
- **Errors:** `400` "Still needed: your GitHub account, your major, …"; `403` a new application to a chapter that just turned applications off; `409` "This application was already sent".

#### `POST /api/apply/signout`

- **Who:** applicant session.
- **Returns:** `204`. Ends every session of this application and clears the cookie.

### 8.7 Agreements and webhooks

#### `GET /api/agreements/{id}/pdf`

- **Who:** the signer, the chapter's leads, and national (Google sign-in). Link to it directly (`<a href>`); the session cookie goes along.
- **Returns:** the signed PDF (`application/pdf`, downloaded as `agreement-<First>-<Last>.pdf`).
- **Errors:** `403` "Not your chapter's agreement"; `404` "No such agreement" or "No signed PDF yet".
- **Side effects:** every download is audited.

#### `POST /api/webhooks/documenso`

For Documenso only, not the frontend. No sign-in and no CSRF; checked by the shared secret in `X-Documenso-Secret`. The payload is only a hint: the envelope is re-read from Documenso.

- `202` accepted.
- `401` wrong secret.
- `404` when no webhook secret is configured.

## 9. Types and values

The full schemas are in `openapi.json`. These are the fields whose meaning or values the spec doesn't spell out. Statuses that change over time are explained in [States](#7-states-and-what-changes-them).

### `ApplicantView`

| Field | Type | Meaning |
| --- | --- | --- |
| `kind` | string | `new`, `claim` or `roster` |
| `status` | string | `started` (editing), `submitted` (waiting for a chapter lead), `approved`, `declined`, `completed` (claim done) |
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

### `RegistrationSummary` (chapter leads)

| Field | Meaning |
| --- | --- |
| `kind` | `new` or `roster` |
| `status` | `submitted`, `approved` or `declined` |
| `name` | Preferred name if set, else first name, plus last name |
| `matchStatus` | `new` (nobody matched), `returning`, `alumni` (a record found), `duplicate` (only weak evidence, e.g. same name or an unverified email) |
| `matches[]` | `{personId, name, status, chapters, why[], account}` |
| `answers` | Question **label** → answer |
| `githubChanged` | A claim linked a different GitHub account than on record |

### All values

| Value | Values |
| --- | --- |
| Person `status` (`MemberRow`, `MatchView`, `MemberHome`, project members) | `applicant`, `active`, `alumni`, `removal_requested`, `removed` |
| Person `kind` (`MemberRow`) | `student`, `community` |
| Chapter `status` | `active`, `inactive`, `pending`, `supported`, `suspended` |
| Chapter role | `lead`, `co_lead`, `viewer` (and `national` in `/api/me` and `ChapterOverview`) |
| Account `state` (`AccountSummary`, `MyAccount`) | `unverified`, `invited`, `accepted`, `confirmed`, `suspended`, `removed` |
| Account `tool` | `slack`, `google`, `github`, `notion`, `vaultwarden` (see `GET /api/tools`) |
| Question `kind` | `text`, `long_text`, `choice`, `yes_no` |
| Graduation season | `spring`, `summer`, `fall`, `winter` (any case accepted; returned lower case) |
| Application `status` | `started`, `submitted`, `approved`, `declined`, `completed` ([Application](#application)) |
| Roster invite `status` | `sent`, `started`, `completed`, `expired` ([Roster invite](#roster-invite)) |
| Agreement `status` | `not_started`, `draft`, `sent`, `signed`, `declined`, `expired`, `voided` ([Agreement](#agreement)) |
| Project member `agreementStatus` | `pending`, `signed`, `waived` |
| Agreement template `kind` | `volunteer`, `code_of_conduct`, `project` |
| Admin task `status` | `open`, `done`, `cancelled` |
| Admin task `action` | `create_account`, `check_account`, `confirm_removals`, `create_resource`, `add_member`, `remove_member`, `missing_resource`, `resource_exists` ([Admin queue](#admin-queue)) |
| Member database pull | `completed`, `unchanged`, `failed` |
| Member database write `state` | `pending`, `failed`, `done` |
| Project `status` | `draft`, `active`, `paused`, `closed` ([Project](#project)) |
| Resource `state` | `to_create`, `exists`, `archived` ([A project's resource](#a-projects-resource)) |
| Resource `audience` | `team`, `role` (with `roleId`), `leads` |
| Resource `access` | `read`, `write`, `admin` |
| Resource `managed` | `portal`, `adopted` (linked to an existing one), `unmanaged` |
| Access `state` (`MemberHome.access`) | `ready`, `invited`, `setting_up`, `after_agreement`, `not_connected`, `agreement_not_set_up` ([member access](#a-members-access-to-a-resource)) |
| Sync run `status` | `completed`, `paused`, `failed` |
| Sync change `kind` and `outcome` | See [`GET /api/chapters/{code}/sync`](#get-apichapterscodesync) |

## 10. Limits and timings

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
| Lists | Members unlimited; roster invites, queue tasks, member-database rows: latest 500 |

Rate limits are kept in memory per app instance and reset when it restarts.

**The caller's IP address:** from `X-Forwarded-For`, but only when the request comes from a trusted proxy. By default that means a private or loopback address (Tomcat's internal proxy list: `10/8`, `172.16/12`, `192.168/16`, `100.64/10`, `127/8`, `169.254/16` and IPv6 loopback, link-local and `fc00::/7`). Any other caller is counted by its own address and the header is ignored. So in local development, where you call from `localhost`, any `X-Forwarded-For` you send is believed. The deployment should let the API be reached only through its proxy (see the README's `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`).
