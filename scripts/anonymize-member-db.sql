-- Anonymizes a COPY of the national member database so the importer can be rehearsed without real
-- personal data (build plan step 2). It overwrites data, so it refuses to run unless the database
-- name contains "anon" or "copy".
--
-- Make the copy first (as a superuser or the DB owner), e.g.:
--   createdb -T <member_db> member_db_copy     (needs no other connections to <member_db>)
--   or: pg_dump <member_db> | psql member_db_copy
-- Then: psql -d member_db_copy -f scripts/anonymize-member-db.sql
--
-- Emails are replaced by a hash of their lower-cased value, so duplicate emails stay duplicates and
-- the importer's duplicate report still means something. Numeric GitHub IDs stay numeric. Every hash
-- is salted with a random value made for this run and never stored, so a list of real addresses
-- can't be matched back to the copy (code review L16).

\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
	IF current_database() !~ '(anon|copy)' THEN
		RAISE EXCEPTION 'Refusing to anonymize "%": run this only on a copy whose name contains anon or copy', current_database();
	END IF;
END
$$;

SELECT set_config('anon.salt', gen_random_uuid()::text, true);

CREATE FUNCTION pg_temp.salted(value text) RETURNS text
LANGUAGE sql STABLE AS $$ SELECT md5(current_setting('anon.salt') || value) $$;

CREATE FUNCTION pg_temp.fake_email(email text, domain text) RETURNS text
LANGUAGE sql STABLE AS $$
	SELECT CASE WHEN nullif(trim(email), '') IS NULL THEN email
	            ELSE left(pg_temp.salted(lower(trim(email))), 16) || '@' || domain END
$$;

UPDATE volunteers SET
	first_name     = 'First' || left(md5(id::text || 'f'), 6),
	last_name      = 'Last' || left(md5(id::text || 'l'), 6),
	preferred_name = CASE WHEN preferred_name IS NULL THEN NULL ELSE 'Pref' || left(md5(id::text || 'p'), 6) END,
	email          = pg_temp.fake_email(email, 'example.invalid'),
	org_email      = pg_temp.fake_email(org_email, 'hack4impact.invalid'),
	notes          = NULL;

UPDATE academic_profiles SET email = pg_temp.fake_email(email, 'school.invalid');

UPDATE volunteer_accounts SET external_id = CASE
	WHEN external_id ~ '^[0-9]+$' THEN (abs(hashtext(current_setting('anon.salt') || external_id)) + 1)::text
	WHEN external_id LIKE '%@%' THEN pg_temp.fake_email(external_id, 'example.invalid')
	WHEN provider IN ('linkedin', 'website') THEN 'https://example.invalid/' || left(pg_temp.salted(external_id), 12)
	ELSE 'acct-' || left(pg_temp.salted(external_id), 12)
END;

UPDATE partners SET email = NULL, ein = NULL, notes = NULL;
UPDATE projects SET notes = NULL;
UPDATE chapters SET notes = NULL;
UPDATE addresses SET line1 = NULL, line2 = NULL, lat = NULL, lng = NULL;
UPDATE professional_profiles SET employer = 'Employer', job_title = 'Job title';
DELETE FROM partner_contacts;

-- Raw per-chapter import batches: personal data the importer doesn't read.
DO $$
DECLARE t record;
BEGIN
	FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'staging' LOOP
		EXECUTE format('TRUNCATE staging.%I', t.tablename);
	END LOOP;
END
$$;

COMMIT;
