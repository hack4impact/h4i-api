-- The member database as an integration (wiki: staged-rollout, decision 162): what applicants now tell us for their
-- school profile, writes waiting for the member database, and edits there to fields the portal owns.

-- Join and Claim ask major, degree type and graduation season and year (the member database requires major and degree
-- on a new school profile; the graduation term is a row national seeds in academic_terms).
ALTER TABLE registration
	ADD COLUMN major text,
	ADD COLUMN degree_type text,
	ADD COLUMN graduation_season text CHECK (graduation_season IN ('spring', 'summer', 'fall', 'winter'));
ALTER TABLE person
	ADD COLUMN major text,
	ADD COLUMN degree_type text,
	ADD COLUMN graduation_season text CHECK (graduation_season IN ('spring', 'summer', 'fall', 'winter')),
	ADD COLUMN graduation_year smallint;

-- One row per person whose record the member database should get: added in the same transaction as the change
-- (approval, claim, roster), written by a worker with retries. [written] is what was written last: the pull compares
-- it with the member database to find edits made there (drift).
CREATE TABLE member_db_write (
	person_id    uuid PRIMARY KEY REFERENCES person (id),
	state        text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'failed', 'done')),
	requested_at timestamptz NOT NULL DEFAULT now(),
	attempts     integer NOT NULL DEFAULT 0,
	next_try_at  timestamptz,
	last_error   text,
	written      jsonb,
	written_at   timestamptz
);
CREATE INDEX member_db_write_due_idx ON member_db_write (next_try_at) WHERE state = 'pending';

-- A field the portal owns that someone changed in the member database (Q49: reported, not taken). Cleared when the
-- two agree again.
CREATE TABLE member_db_drift (
	person_id       uuid NOT NULL REFERENCES person (id),
	field           text NOT NULL,
	portal_value    text,
	member_db_value text,
	first_seen_at   timestamptz NOT NULL DEFAULT now(),
	last_seen_at    timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (person_id, field)
);

-- The integration's switches and last pull (one row). It starts in dry run: writes wait until national turns them on.
CREATE TABLE member_db_state (
	id               boolean PRIMARY KEY DEFAULT true CHECK (id),
	dry_run          boolean NOT NULL DEFAULT true,
	paused           boolean NOT NULL DEFAULT false,
	pause_reason     text,
	last_pull_at     timestamptz,
	last_pull_status text CHECK (last_pull_status IN ('completed', 'unchanged', 'failed')),
	last_pull_error  text,
	last_pull_counts jsonb,
	-- Each member-DB table's row count and newest change at the last pull: the same signature, nothing to pull.
	signature        text,
	updated_at       timestamptz NOT NULL DEFAULT now()
);
INSERT INTO member_db_state DEFAULT VALUES;
CREATE TRIGGER member_db_state_updated_at BEFORE UPDATE ON member_db_state FOR EACH ROW EXECUTE FUNCTION set_updated_at();
