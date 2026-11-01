-- Stage 2, package 7 (wiki: staged-rollout-stage-2, member database writes): projects, their teams and chapter leads
-- written to the member database, modelled on stage 1's member_db_write. project.member_db_project_id and
-- member_db_linked came earlier, with past projects (V6).

-- One row per project whose projects row, engagement and assignments the member database should get: requested in the
-- same transaction as any change to the project or its team; written by a worker in one member-database transaction.
-- [waiting_on]: the people whose own person write has to land first (their volunteer rows).
CREATE TABLE member_db_project_write (
	project_id   uuid PRIMARY KEY REFERENCES project (id),
	state        text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'failed', 'done')),
	requested_at timestamptz NOT NULL DEFAULT now(),
	attempts     integer NOT NULL DEFAULT 0,
	next_try_at  timestamptz,
	last_error   text,
	written      jsonb,
	written_at   timestamptz,
	waiting_on   jsonb
);
CREATE INDEX member_db_project_write_due_idx ON member_db_project_write (next_try_at) WHERE state = 'pending';

-- One row per chapter lead or co-lead role (decision 171): a leadership_assignments row whose ID is the chapter_role's.
CREATE TABLE member_db_leadership_write (
	chapter_role_id uuid PRIMARY KEY REFERENCES chapter_role (id),
	state           text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'failed', 'done')),
	requested_at    timestamptz NOT NULL DEFAULT now(),
	attempts        integer NOT NULL DEFAULT 0,
	next_try_at     timestamptz,
	last_error      text,
	written         jsonb,
	written_at      timestamptz,
	waiting_on      jsonb
);
CREATE INDEX member_db_leadership_write_due_idx ON member_db_leadership_write (next_try_at) WHERE state = 'pending';

-- The member's open project_assignments row: a role change ends it and opens another, so the member database keeps
-- who held which role when. Null until first written (the project_member's own ID is used then).
ALTER TABLE project_member ADD COLUMN member_db_assignment_id uuid;

-- Project and leadership writes stay off, though queued and previewable, until national has checked the previews.
-- Person writes don't depend on it.
ALTER TABLE member_db_state ADD COLUMN projects_enabled boolean NOT NULL DEFAULT false;
