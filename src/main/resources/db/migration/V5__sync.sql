-- Stage 2, package 4 (wiki: staged-rollout-stage-2, stage-2-migrations): the sync engine's runs, records and changes,
-- chapter resources, the admin queue's sync tasks, and what the portal knows about a resource's name already existing
-- in its tool. From the full portal's V1, V3, V6, V7, V14 and V16, plus the new columns for linking by hand.

-- One run of one tool: a dry run (planned only) or an apply run (changes made).
CREATE TABLE sync_run (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool            tool_name, -- null = every tool
	mode            text NOT NULL CHECK (mode IN ('dry_run', 'apply')),
	-- change: a project or link change; agreement: a signature; recovery: after the app stopped mid-run.
	-- release and rule stay allowed for stage 3 (drafts and automations).
	trigger         text NOT NULL CHECK (trigger IN ('schedule', 'release', 'manual', 'agreement', 'rule', 'change', 'recovery')),
	status          text NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'paused', 'failed')),
	started_at      timestamptz NOT NULL DEFAULT now(),
	finished_at     timestamptz,
	planned_adds    integer NOT NULL DEFAULT 0,
	planned_removes integer NOT NULL DEFAULT 0,
	applied_adds    integer NOT NULL DEFAULT 0,
	applied_removes integer NOT NULL DEFAULT 0,
	confirmed_by    uuid REFERENCES person (id), -- national confirmation after a blast-radius pause
	error           text
);
CREATE INDEX sync_run_started_idx ON sync_run (started_at DESC);

-- Per person and resource: what should be, what is, why, and whether the portal applied it. Only applied grants can
-- ever be removed (decision 52): the portal never takes away access it didn't give, including from people already in
-- a resource national linked (decision 170).
CREATE TABLE sync_record (
	person_id            uuid NOT NULL REFERENCES person (id),
	resource_id          uuid NOT NULL REFERENCES resource (id),
	desired              text NOT NULL CHECK (desired IN ('present', 'absent')),
	desired_access_level text,
	reasons              jsonb NOT NULL DEFAULT '[]', -- why the resolver wants it: project member, chapter member, chapter lead
	actual               text NOT NULL DEFAULT 'unknown' CHECK (actual IN ('present', 'absent', 'unknown')),
	status               text NOT NULL DEFAULT 'pending'
		CHECK (status IN ('in_sync', 'pending', 'waiting_on_member', 'manual_task', 'failed', 'dead_letter')),
	attempts             integer NOT NULL DEFAULT 0,
	last_attempt_at      timestamptz,
	next_retry_at        timestamptz,
	last_error           text,
	last_run_id          uuid REFERENCES sync_run (id),
	applied_at           timestamptz,  -- set when an apply run gave it; null = not the portal's to remove
	applied_access_level text,         -- the level the portal last gave: it never lowers access it didn't give (decision 144)
	updated_at           timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (person_id, resource_id)
);
CREATE INDEX sync_record_status_idx ON sync_record (status) WHERE status <> 'in_sync';
CREATE TRIGGER sync_record_updated_at BEFORE UPDATE ON sync_record FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Every planned change and finding of a run, with what happened to it when the run applied it.
--   outcome: applied | invited (the person still has to accept) | waiting (no account in the tool yet) | queued (on the
--   admin queue) | held (over the removal limit, waiting for national) | failed (retried later) | dead_letter (failed
--   too often) | skipped (not due yet, or the run stopped). Null in dry runs.
--   resource_exists: a resource the portal was about to create whose name the tool already has (national decides).
CREATE TABLE sync_change (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	run_id      uuid NOT NULL REFERENCES sync_run (id),
	kind        text NOT NULL CHECK (kind IN ('add', 'change', 'remove', 'drift', 'unmatched_account', 'missing_resource', 'create_resource', 'archive_resource', 'resource_exists')),
	person_id   uuid REFERENCES person (id),
	resource_id uuid REFERENCES resource (id),
	account_id  text, -- the tool's account ID, for unmatched accounts and drift
	from_access text,
	to_access   text,
	reasons     jsonb NOT NULL DEFAULT '[]', -- why the portal wants it (adds, changes) or wanted it (removals)
	outcome     text CHECK (outcome IN ('applied', 'invited', 'waiting', 'queued', 'held', 'failed', 'dead_letter', 'skipped')),
	error       text,
	created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sync_change_run_idx ON sync_change (run_id, kind);

-- A chapter's own channels, groups and teams, linked by national (decision 170): every active member, or the leads.
CREATE TABLE chapter_resource (
	chapter_id   uuid NOT NULL REFERENCES chapter (id),
	resource_id  uuid NOT NULL REFERENCES resource (id),
	audience     text NOT NULL CHECK (audience IN ('members', 'leads')),
	access_level text NOT NULL CHECK (access_level IN ('read', 'write', 'admin')),
	created_at   timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (chapter_id, resource_id)
);

-- The admin queue's sync tasks: one person's access to one resource, a run's held removals, a resource that couldn't be
-- created or already exists. At most one open member task per person, resource and action.
ALTER TABLE admin_task
	ADD COLUMN resource_id uuid REFERENCES resource (id),
	ADD COLUMN run_id      uuid REFERENCES sync_run (id);
CREATE UNIQUE INDEX admin_task_open_member_idx ON admin_task (tool, action, person_id, resource_id) WHERE status = 'open' AND person_id IS NOT NULL;

-- A portal-made resource whose creation fails is retried with backoff, and after five failures waits on the admin queue.
-- exists_in_tool_at: the tool already has its name (look before creating), so the portal stops and national links,
-- renames or leaves it; found_external_id: the existing one's ID, when the tool's listing showed it.
ALTER TABLE resource
	ADD COLUMN create_attempts   integer NOT NULL DEFAULT 0,
	ADD COLUMN create_retry_at   timestamptz,
	ADD COLUMN exists_in_tool_at timestamptz,
	ADD COLUMN found_external_id text;
