-- Stage 2, package 1 (wiki: staged-rollout-stage-2, stage-2-migrations): partners, project roles, projects and their
-- teams, the resources access is given to, the standard resources a new project gets, and each tool's sync switches.
-- From the full portal's V1, V5 and V7, without imported projects, Notion pages or national projects.

-- Partners: imported from the member database every pull, or added by national. Picked from a list, never typed in.
CREATE TABLE partner (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	name        text NOT NULL UNIQUE,
	website     text,
	source_id   uuid UNIQUE, -- its partners.id in the member database, imported or stamped after the portal writes it
	imported_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now(),
	deleted_at  timestamptz
);
CREATE TRIGGER partner_updated_at BEFORE UPDATE ON partner FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Project roles, imported from the member database (Designer, Developer; Design Lead, Project Manager, Tech Lead lead).
CREATE TABLE project_role (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	name        text NOT NULL UNIQUE,
	is_lead     boolean NOT NULL DEFAULT false,
	source_id   uuid UNIQUE,
	imported_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER project_role_updated_at BEFORE UPDATE ON project_role FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A project is one chapter's work for one partner over a period (an engagement in the member database). Made in the
-- portal: the member database's projects aren't imported (decision 168). A draft becomes active when it's started,
-- which needs a partner.
CREATE TABLE project (
	id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id uuid NOT NULL REFERENCES chapter (id),
	partner_id uuid REFERENCES partner (id),
	name       text NOT NULL,
	slug       text NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]*$'), -- with the chapter code: umd-rise-dc
	term_id    uuid REFERENCES term (id),
	type       text,
	tags       text[] NOT NULL DEFAULT '{}',
	status     text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'paused', 'closed')),
	starts_on  date,
	ends_on    date,
	closed_at  timestamptz,
	created_at timestamptz NOT NULL DEFAULT now(),
	updated_at timestamptz NOT NULL DEFAULT now(),
	UNIQUE (chapter_id, slug)
);
CREATE TRIGGER project_updated_at BEFORE UPDATE ON project FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A project's team. Every member has a role: the member database's project_assignments requires one.
CREATE TABLE project_member (
	id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	project_id       uuid NOT NULL REFERENCES project (id),
	person_id        uuid NOT NULL REFERENCES person (id),
	project_role_id  uuid NOT NULL REFERENCES project_role (id),
	agreement_status text NOT NULL DEFAULT 'pending' CHECK (agreement_status IN ('pending', 'signed', 'waived')),
	added_by         uuid REFERENCES person (id),
	added_at         timestamptz NOT NULL DEFAULT now(),
	removed_at       timestamptz,
	created_at       timestamptz NOT NULL DEFAULT now(),
	updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_member_active_idx ON project_member (project_id, person_id) WHERE removed_at IS NULL;
CREATE INDEX project_member_person_idx ON project_member (person_id) WHERE removed_at IS NULL;
CREATE TRIGGER project_member_updated_at BEFORE UPDATE ON project_member FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Anything access is given to: a Slack channel, Google group, GitHub team or vault collection.
-- managed: portal (the portal made it), adopted (national linked an existing one), unmanaged (left alone).
CREATE TABLE resource (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool        tool_name NOT NULL,
	external_id text, -- channel ID, group email, team slug, collection ID; null until created
	name        text NOT NULL,
	chapter_id  uuid REFERENCES chapter (id),
	tags        text[] NOT NULL DEFAULT '{}',
	managed     text NOT NULL DEFAULT 'portal' CHECK (managed IN ('portal', 'adopted', 'unmanaged')),
	archived_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now(),
	UNIQUE (tool, external_id)
);
CREATE TRIGGER resource_updated_at BEFORE UPDATE ON resource FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Who on a project gets a resource: the whole team, one role, or the project's leads; gated ones wait for the
-- project agreement.
CREATE TABLE project_resource (
	project_id         uuid NOT NULL REFERENCES project (id),
	resource_id        uuid NOT NULL REFERENCES resource (id),
	audience           text NOT NULL CHECK (audience IN ('team', 'role', 'leads')),
	audience_role_id   uuid REFERENCES project_role (id),
	access_level       text NOT NULL,
	requires_agreement boolean NOT NULL DEFAULT false,
	created_at         timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (project_id, resource_id),
	CHECK ((audience = 'role') = (audience_role_id IS NOT NULL))
);

-- The standard resources a new project gets. chapter_id null = the national template, used by every chapter without
-- rows of its own. name_pattern placeholders: {chapter} (chapter code) and {project} (project slug).
CREATE TABLE resource_template (
	id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id         uuid REFERENCES chapter (id),
	position           integer NOT NULL DEFAULT 0,
	tool               tool_name NOT NULL,
	name_pattern       text NOT NULL,
	audience           text NOT NULL DEFAULT 'team' CHECK (audience IN ('team', 'leads')),
	access_level       text NOT NULL CHECK (access_level IN ('read', 'write', 'admin')),
	requires_agreement boolean NOT NULL DEFAULT false,
	tags               text[] NOT NULL DEFAULT '{}',
	created_at         timestamptz NOT NULL DEFAULT now()
);

-- National template (decisions 82, 169): a channel and a Google group for the team; the GitHub team and the vault
-- collection only after the project agreement is signed.
INSERT INTO resource_template (position, tool, name_pattern, access_level, requires_agreement, tags) VALUES
	(1, 'slack', '{chapter}-{project}', 'write', false, '{main}'),
	(2, 'google', '{chapter}-{project}@hack4impact.org', 'write', false, '{main}'),
	(3, 'github', '{chapter}-{project}', 'write', true, '{eng}'),
	(4, 'vaultwarden', '{chapter}-{project}', 'write', true, '{eng}');

-- Each syncing tool's switches. No rows here: at startup the app adds one per syncing tool in its registry, in dry run.
CREATE TABLE tool_setting (
	tool              tool_name PRIMARY KEY,
	enabled           boolean NOT NULL DEFAULT true,  -- kill switch
	dry_run           boolean NOT NULL DEFAULT true,  -- every tool starts in dry run
	max_removals      integer NOT NULL DEFAULT 25,    -- removals above this in one run wait for national (decision 36)
	max_removal_ratio numeric(4, 3) NOT NULL DEFAULT 0.2,
	paused_by         uuid REFERENCES person (id),
	pause_reason      text,
	updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER tool_setting_updated_at BEFORE UPDATE ON tool_setting FOR EACH ROW EXECUTE FUNCTION set_updated_at();
