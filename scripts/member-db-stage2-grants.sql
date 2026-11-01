-- Stage 2's grants for the portal's login (wiki: staged-rollout-stage-2, stage-2-migrations). For Khoa: run once, after
-- the stage 2 part of member-db-changes.sql. Safe to run twice. Roles are cluster-wide, so the tests don't run this.
-- Set the password before running (or ALTER ROLE afterwards); it goes in the portal's MEMBER_DB_PASSWORD.

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'portal_sync') THEN
		CREATE ROLE portal_sync LOGIN PASSWORD 'change-me';
	END IF;
END $$;

GRANT USAGE ON SCHEMA public TO portal_sync;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO portal_sync;

-- Stage 1: people.
GRANT INSERT, UPDATE ON volunteers, academic_profiles, volunteer_accounts TO portal_sync;

-- Stage 2: projects, engagements, team members, partners and chapter leads. No DELETE: the portal ends rows, it never
-- removes them.
GRANT INSERT, UPDATE ON projects, project_engagements, project_assignments, partners, chapter_partners, leadership_assignments TO portal_sync;
