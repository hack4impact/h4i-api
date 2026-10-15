-- Changes to the national member database so the portal can write to it (wiki: staged-rollout, decision 162).
-- For Khoa. Run on a copy first, then production. Safe to run twice.
--
-- Agreed 2026-10-14: a primary key on volunteer_accounts.id and one row per (provider, external_id). volunteers.id is
-- already the primary key, and every table already has updated_at triggers (part 3 only matters on a copy that lacks
-- them, like the portal's test database).

BEGIN;

-- 1. Duplicates must be resolved by hand first: the script stops and lists them.
--    The 2026-10-09 checks found 2 duplicate (provider, external_id) pairs.
DO $$
DECLARE
	same_id integer;
	same_account integer;
BEGIN
	SELECT count(*) INTO same_id FROM (SELECT id FROM volunteer_accounts GROUP BY id HAVING count(*) > 1) d;
	SELECT count(*) INTO same_account FROM (SELECT provider, external_id FROM volunteer_accounts GROUP BY 1, 2 HAVING count(*) > 1) d;
	IF same_id > 0 OR same_account > 0 THEN
		RAISE EXCEPTION 'volunteer_accounts has % repeated ids and % accounts linked more than once: resolve them first (query below)', same_id, same_account;
	END IF;
END $$;
-- To list them:
--   SELECT provider, external_id, array_agg(volunteer_id) FROM volunteer_accounts GROUP BY 1, 2 HAVING count(*) > 1;
--   SELECT id, count(*) FROM volunteer_accounts GROUP BY id HAVING count(*) > 1;

-- 2. Agreed: a primary key, and one row per account.
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'volunteer_accounts'::regclass AND contype = 'p') THEN
		ALTER TABLE volunteer_accounts ADD CONSTRAINT volunteer_accounts_pkey PRIMARY KEY (id);
	END IF;
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'volunteer_accounts_provider_external_id_key') THEN
		ALTER TABLE volunteer_accounts ADD CONSTRAINT volunteer_accounts_provider_external_id_key UNIQUE (provider, external_id);
	END IF;
END $$;

-- 3. updated_at kept current on every change (already true in production, per Khoa).
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	NEW.updated_at = now();
	RETURN NEW;
END $$;
DO $$
DECLARE
	t text;
BEGIN
	FOR t IN SELECT c.table_name FROM information_schema.columns c
		JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name AND tb.table_type = 'BASE TABLE'
		WHERE c.table_schema = 'public' AND c.column_name = 'updated_at'
	LOOP
		IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = format('public.%I', t)::regclass AND NOT tgisinternal
			AND pg_get_triggerdef(oid) ILIKE '%BEFORE UPDATE%' AND pg_get_triggerdef(oid) ILIKE '%updated_at%') THEN
			EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION set_updated_at()', t || '_updated_at', t);
		END IF;
	END LOOP;
END $$;

COMMIT;

-- 4. Suggested, not required (each its own decision for Khoa):
--  a. The portal's login, with no DELETE:
--       CREATE ROLE portal_sync LOGIN PASSWORD '<generated>';
--       GRANT CONNECT ON DATABASE <member_db> TO portal_sync;
--       GRANT USAGE ON SCHEMA public TO portal_sync;
--       GRANT SELECT ON ALL TABLES IN SCHEMA public TO portal_sync;
--       GRANT INSERT, UPDATE ON volunteers, academic_profiles, volunteer_accounts TO portal_sync;
--  b. chapters.code and academic_terms.season are char(n), padded with spaces: varchar avoids trim() everywhere.
--       ALTER TABLE chapters ALTER COLUMN code TYPE varchar(20) USING trim(code);
--       ALTER TABLE academic_terms ALTER COLUMN season TYPE varchar(10) USING trim(season);
--  c. One person per address, once the one shared address is resolved:
--       CREATE UNIQUE INDEX volunteers_email_key ON volunteers (lower(email)) WHERE deleted_at IS NULL;
--       CREATE UNIQUE INDEX volunteers_org_email_key ON volunteers (lower(org_email)) WHERE deleted_at IS NULL;
--  d. academic_terms has no updated_at (so the portal also counts its rows to notice changes). Adding one, with the
--     trigger from part 3, would make it like the other tables.
--  e. Later stages: leadership_assignments needs volunteer_id and chapter_id; the projects status check allows the
--     literal strings 'draft::text' and 'approved::text' (wiki: member-database, problems 1 and 2); the providers
--     check needs 'vaultwarden' and 'documenso' when those accounts are written.
--  f. Validate the NOT VALID constraints once the rows they'd reject are fixed (wiki: member-database, problem 4).
