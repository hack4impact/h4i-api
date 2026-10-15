-- Fake member-DB rows for tests and local development. No real people.
-- Each row exercises one case the importer must handle; the comment says which.
-- UUID prefixes: 1 institution, 2 chapter, 3 term, 4 partner, 5 volunteer, 6 academic profile,
-- 7 account, 8 project role, 9 project, a engagement, b assignment.

INSERT INTO academic_institutions (id, name) VALUES
	('10000000-0000-0000-0000-000000000001', 'University of Maryland'),
	('10000000-0000-0000-0000-000000000002', 'Georgia Institute of Technology');

INSERT INTO academic_terms (id, academic_institution_id, season, year, code, start_date, end_date) VALUES
	('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Fall', 2025, 'F25', '2025-08-25', '2025-12-20'),
	('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Spring', 2026, 'S26', '2026-01-20', '2026-05-15'),
	-- unknown season: skipped and reported
	('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 'Autumn', 2025, 'A25', '2025-08-18', '2025-12-12');

INSERT INTO chapters (id, name, code, academic_institution_id, status, deleted_at) VALUES
	-- code is char(20): stored padded with spaces
	('20000000-0000-0000-0000-000000000001', 'Hack4Impact UMD', 'umd', '10000000-0000-0000-0000-000000000001', 'active', NULL),
	('20000000-0000-0000-0000-000000000002', 'Hack4Impact Georgia Tech', 'gt', '10000000-0000-0000-0000-000000000002', 'active', NULL),
	-- soft-deleted: not imported
	('20000000-0000-0000-0000-000000000003', 'Hack4Impact Old', 'old', '10000000-0000-0000-0000-000000000002', 'inactive', '2024-01-01'),
	-- code that isn't a valid slug: skipped and reported
	('20000000-0000-0000-0000-000000000004', 'Hack4Impact Bad Code', 'bad code!', '10000000-0000-0000-0000-000000000002', 'pending', NULL);

INSERT INTO partners (id, name, status, deleted_at) VALUES
	('40000000-0000-0000-0000-000000000001', 'RISE DC', 'active', NULL),
	('40000000-0000-0000-0000-000000000002', 'Food Bank', 'inactive', NULL),
	('40000000-0000-0000-0000-000000000003', 'Gone Partner', 'inactive', '2023-01-01');

INSERT INTO volunteers (id, first_name, last_name, preferred_name, email, org_email, chapter_id, status, type, is_legacy, deleted_at) VALUES
	('50000000-0000-0000-0000-000000000001', 'Ada', 'Active', 'Ada', ' Ada@Personal.test ', 'ada@hack4impact.org', '20000000-0000-0000-0000-000000000001', 'active', 'student', false, NULL),
	-- type alumni -> alumni
	('50000000-0000-0000-0000-000000000002', 'Alan', 'Alumnus', NULL, 'alan@personal.test', NULL, '20000000-0000-0000-0000-000000000001', 'active', 'alumni', false, NULL),
	-- inactive and hiatus -> alumni (Q17)
	('50000000-0000-0000-0000-000000000003', 'Grace', 'Inactive', NULL, 'grace@personal.test', NULL, '20000000-0000-0000-0000-000000000001', 'inactive', 'student', false, NULL),
	('50000000-0000-0000-0000-000000000004', 'Hal', 'Hiatus', NULL, 'hal@personal.test', NULL, '20000000-0000-0000-0000-000000000002', 'hiatus', 'student', false, NULL),
	-- suspended -> removed
	('50000000-0000-0000-0000-000000000005', 'Sam', 'Suspended', NULL, 'sam@personal.test', NULL, '20000000-0000-0000-0000-000000000002', 'suspended', 'student', false, NULL),
	-- community -> alumni for now (wiki decision 40), kind community
	('50000000-0000-0000-0000-000000000006', 'Cora', 'Community', NULL, 'cora@personal.test', NULL, '20000000-0000-0000-0000-000000000002', 'active', 'community', false, NULL),
	-- soft-deleted: not imported
	('50000000-0000-0000-0000-000000000007', 'Del', 'Deleted', NULL, 'del@personal.test', NULL, '20000000-0000-0000-0000-000000000001', 'active', 'student', false, '2025-01-01'),
	-- legacy row without email: imported, reported (can't receive a claim link)
	('50000000-0000-0000-0000-000000000008', 'Lee', 'Legacy', NULL, NULL, NULL, '20000000-0000-0000-0000-000000000001', 'inactive', 'alumni', true, NULL),
	-- same email, different case: both imported, reported as a possible duplicate
	('50000000-0000-0000-0000-000000000009', 'Dana', 'Double', NULL, 'Same@Personal.test', NULL, '20000000-0000-0000-0000-000000000002', 'active', 'student', false, NULL),
	('50000000-0000-0000-0000-000000000010', 'Dana', 'Double', NULL, 'same@personal.test', NULL, '20000000-0000-0000-0000-000000000002', 'active', 'student', false, NULL),
	-- chapter was soft-deleted: imported without a chapter membership, reported
	('50000000-0000-0000-0000-000000000011', 'Otto', 'Oldchapter', NULL, 'otto@personal.test', NULL, '20000000-0000-0000-0000-000000000003', 'active', 'student', false, NULL),
	-- no chapter at all
	('50000000-0000-0000-0000-000000000012', 'Nia', 'Nochapter', NULL, 'nia@personal.test', NULL, NULL, 'active', 'student', false, NULL);

INSERT INTO academic_profiles (id, volunteer_id, graduation_term_id, major, degree_type, email, updated_at) VALUES
	('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Computer Science', 'BS', 'ada.old@umd.edu', '2024-01-01'),
	-- second profile for the same person: the most recently updated one wins, reported
	('60000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', 'Computer Science', 'BS', 'Ada@UMD.edu', '2025-06-01'),
	('60000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000002', NULL, 'Mathematics', 'BS', 'alan@umd.edu', '2025-01-01');

-- volunteer_accounts has no primary key in the member DB, so duplicates are possible.
INSERT INTO volunteer_accounts (id, volunteer_id, provider, external_id) VALUES
	-- numeric GitHub ID -> external_id
	('70000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'github', '1234567'),
	-- exact duplicate row of the one above: counted once
	('70000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'github', '1234567'),
	-- GitHub profile URL (the usual shape in real data) -> username as external_login, unverified
	('70000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000002', 'github', 'https://github.com/alan-dev/'),
	('70000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000001', 'slack', 'U0ADA'),
	-- same Slack ID claimed by another person: skipped, reported
	('70000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000003', 'slack', 'U0ADA'),
	-- provider the portal doesn't manage: skipped, counted
	('70000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000001', 'linkedin', 'https://www.linkedin.com/in/ada'),
	-- second GitHub account for the same person: kept (wiki decision 45)
	('70000000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000001', 'github', '7654321'),
	-- belongs to a soft-deleted volunteer: skipped
	('70000000-0000-0000-0000-000000000007', '50000000-0000-0000-0000-000000000007', 'google', 'del@hack4impact.org'),
	('70000000-0000-0000-0000-000000000008', '50000000-0000-0000-0000-000000000006', 'google', 'cora@hack4impact.org'),
	-- username with https:// stuck in front (46 of these in real data) -> username
	('70000000-0000-0000-0000-000000000009', '50000000-0000-0000-0000-000000000003', 'github', 'https://grace-gh'),
	-- not a GitHub profile at all: skipped, reported
	('70000000-0000-0000-0000-000000000010', '50000000-0000-0000-0000-000000000004', 'github', 'https://www.linkedin.com/in/hal'),
	-- second Slack ID for the same person (another workspace): kept
	('70000000-0000-0000-0000-000000000011', '50000000-0000-0000-0000-000000000001', 'slack', 'U0ADA2');

INSERT INTO project_roles (id, name, is_lead) VALUES
	('80000000-0000-0000-0000-000000000001', 'Engineer', false),
	('80000000-0000-0000-0000-000000000002', 'Tech Lead', true);

-- projects.status_valid rejects 'draft' and 'approved' (bug), so only other statuses can be inserted.
INSERT INTO projects (id, name, status, deleted_at) VALUES
	('90000000-0000-0000-0000-000000000001', 'RISE DC Portal', 'active', NULL),
	('90000000-0000-0000-0000-000000000002', 'Food Bank App', 'completed', NULL),
	-- no engagement, so no chapter: reported
	('90000000-0000-0000-0000-000000000003', 'Orphan Project', 'inactive', NULL),
	('90000000-0000-0000-0000-000000000004', 'Deleted Project', 'inactive', '2024-01-01');

INSERT INTO project_engagements (id, chapter_id, partner_id, project_id, status, start_date, end_date) VALUES
	('a0000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', 'active', '2025-09-01', NULL),
	('a0000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000002', 'completed', '2025-01-15', '2025-05-10'),
	-- chapter soft-deleted: skipped, reported
	('a0000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', 'completed', '2023-01-15', '2023-05-10'),
	-- same chapter and partner again: slug gets a suffix
	('a0000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', 'paused', '2026-01-25', NULL);

INSERT INTO project_assignments (id, volunteer_id, project_engagement_id, project_role_id, start_date, end_date) VALUES
	('b0000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000002', '2025-09-01', NULL),
	-- ended assignment -> removed_at set
	('b0000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001', '2025-09-01', '2025-12-01'),
	-- soft-deleted volunteer: skipped
	('b0000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001', '2025-09-01', NULL),
	('b0000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000001', '2025-01-15', '2025-05-10');
