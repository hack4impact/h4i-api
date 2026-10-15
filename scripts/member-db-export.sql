-- Member DB export for the portal importer. Run in pgAdmin's Query Tool (needs only SELECT), then
-- "Save results to file" as CSV, and load it with scripts/load-member-db-export.sh.
-- One row per source row, as JSON, with only the columns the importer reads (no notes, addresses or contacts).
-- The result contains personal data: keep the CSV outside the repo and delete it after importing.
SELECT 'academic_institutions' AS t, row_to_json(x)::text AS j FROM (SELECT id, name FROM academic_institutions) x
UNION ALL SELECT 'academic_terms', row_to_json(x)::text FROM (SELECT id, academic_institution_id, season, year, code, start_date, end_date FROM academic_terms) x
UNION ALL SELECT 'chapters', row_to_json(x)::text FROM (SELECT id, name, code, academic_institution_id, status, website, email, deleted_at FROM chapters) x
UNION ALL SELECT 'partners', row_to_json(x)::text FROM (SELECT id, name, website, deleted_at FROM partners) x
UNION ALL SELECT 'volunteers', row_to_json(x)::text FROM (SELECT id, first_name, last_name, preferred_name, email, org_email, chapter_id, status, type, deleted_at FROM volunteers) x
UNION ALL SELECT 'academic_profiles', row_to_json(x)::text FROM (SELECT volunteer_id, email, graduation_term_id, updated_at FROM academic_profiles) x
UNION ALL SELECT 'volunteer_accounts', row_to_json(x)::text FROM (SELECT id, volunteer_id, provider, external_id FROM volunteer_accounts) x
UNION ALL SELECT 'project_roles', row_to_json(x)::text FROM (SELECT id, name, is_lead FROM project_roles) x
UNION ALL SELECT 'projects', row_to_json(x)::text FROM (SELECT id, name, deleted_at FROM projects) x
UNION ALL SELECT 'project_engagements', row_to_json(x)::text FROM (SELECT id, chapter_id, partner_id, project_id, status, start_date, end_date FROM project_engagements) x
UNION ALL SELECT 'project_assignments', row_to_json(x)::text FROM (SELECT id, volunteer_id, project_engagement_id, project_role_id, start_date, end_date FROM project_assignments) x
UNION ALL SELECT 'leadership_assignments', row_to_json(x)::text FROM (SELECT id FROM leadership_assignments) x;
