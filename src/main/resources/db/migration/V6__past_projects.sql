-- Stage 2, package 5 (wiki: staged-rollout-stage-2, stage-2-migrations): the member database's projects row a portal
-- project is written to. A lead may link a new project to a past one (decision 168: project names are unique there),
-- while it's a draft; package 7's writes stamp the row they create. member_db_linked: a past row the lead picked, which
-- other chapters' engagements may share, so the portal writes only its own engagement to it.
ALTER TABLE project
	ADD COLUMN member_db_project_id uuid,
	ADD COLUMN member_db_linked     boolean NOT NULL DEFAULT false;

-- One portal project per past row a lead links.
CREATE UNIQUE INDEX project_member_db_linked_idx ON project (member_db_project_id) WHERE member_db_linked;
