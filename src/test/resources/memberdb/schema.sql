-- Structure of the national member database, recreated from Docs/raw/2026-10-08-member-db-schema.csv
-- (public schema only). Used as a fake import source in tests and local development.
-- Constraints are copied as they are, including NOT VALID ones and the projects.status_valid bug.

CREATE TABLE academic_institutions (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	name character varying(150) NOT NULL,
	address_id uuid
);

CREATE TABLE academic_profiles (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	volunteer_id uuid NOT NULL,
	graduation_term_id uuid,
	major character varying(150),
	degree_type character varying(150),
	email character varying(150),
	is_legacy boolean NOT NULL DEFAULT false
);

CREATE TABLE academic_terms (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	start_date timestamp with time zone NOT NULL,
	end_date timestamp with time zone NOT NULL,
	academic_institution_id uuid NOT NULL,
	season character(10) NOT NULL,
	year smallint NOT NULL,
	code character varying(4) NOT NULL
);

CREATE TABLE addresses (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	country_code character varying(2) NOT NULL DEFAULT 'US'::character varying,
	region_code character varying(3) NOT NULL,
	postal_code character varying(50) NOT NULL,
	line1 character varying(150),
	line2 character varying(150),
	city character varying(150),
	lat point,
	lng point,
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	name character varying(150)
);

CREATE TABLE chapter_partners (
	chapter_id uuid NOT NULL,
	partner_id uuid NOT NULL,
	created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE chapters (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	deleted_at timestamp with time zone,
	name character varying(150) NOT NULL,
	website character varying(150),
	email character varying(150),
	status character varying(50) NOT NULL DEFAULT 'active'::character varying,
	founded_on date,
	notes text,
	code character(20) NOT NULL,
	academic_institution_id uuid NOT NULL
);

CREATE TABLE leadership_assignments (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	start_date timestamp with time zone NOT NULL,
	end_date timestamp with time zone,
	leadership_role_id uuid NOT NULL,
	is_legacy boolean NOT NULL DEFAULT false
);

CREATE TABLE leadership_roles (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	name character varying(150) NOT NULL
);

CREATE TABLE partner_contacts (
	contact_id uuid NOT NULL DEFAULT gen_random_uuid(),
	partner_id uuid NOT NULL,
	first_name character varying(150) NOT NULL,
	last_name character varying(150) NOT NULL,
	title character varying(150) NOT NULL,
	email character varying(150),
	phone character varying(30),
	is_primary boolean NOT NULL DEFAULT false,
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE partners (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	deleted_at timestamp with time zone,
	name character varying(150) NOT NULL,
	description text,
	website character varying(150),
	email character varying(150),
	ein character varying(20),
	status character varying(50) NOT NULL DEFAULT 'inactive'::character varying,
	notes text,
	address_id uuid,
	is_legacy boolean NOT NULL DEFAULT false
);

CREATE TABLE professional_profiles (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	volunteer_id uuid NOT NULL,
	employer character varying(150) NOT NULL,
	job_title character varying(150) NOT NULL
);

CREATE TABLE project_artifacts (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	project_id uuid NOT NULL,
	link character varying NOT NULL,
	type character varying NOT NULL
);

CREATE TABLE project_assignments (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	volunteer_id uuid NOT NULL,
	project_engagement_id uuid NOT NULL,
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	project_role_id uuid NOT NULL,
	start_date timestamp with time zone NOT NULL,
	end_date timestamp with time zone
);

CREATE TABLE project_engagements (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	chapter_id uuid NOT NULL,
	partner_id uuid NOT NULL,
	project_id uuid NOT NULL,
	status character varying(50) NOT NULL DEFAULT 'active'::character varying,
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	start_date timestamp with time zone NOT NULL,
	end_date timestamp with time zone,
	is_legacy boolean NOT NULL DEFAULT false
);

CREATE TABLE project_roles (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	name character varying(150) NOT NULL,
	is_lead boolean NOT NULL DEFAULT false
);

CREATE TABLE projects (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	deleted_at timestamp with time zone,
	name character varying(150) NOT NULL,
	status character varying(50) NOT NULL DEFAULT 'draft'::character varying,
	description text,
	notes text,
	is_legacy boolean NOT NULL DEFAULT false
);

CREATE TABLE volunteer_accounts (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	provider character varying(150) NOT NULL,
	volunteer_id uuid NOT NULL,
	external_id character varying NOT NULL
);

CREATE TABLE volunteers (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	created_at timestamp with time zone NOT NULL DEFAULT now(),
	updated_at timestamp with time zone NOT NULL DEFAULT now(),
	deleted_at timestamp with time zone,
	first_name character varying(150) NOT NULL,
	last_name character varying(150) NOT NULL,
	email character varying(150),
	org_email character varying(150),
	chapter_id uuid,
	status character varying(50) NOT NULL DEFAULT 'active'::character varying,
	notes text,
	type character varying(50) NOT NULL DEFAULT 'student'::character varying,
	is_legacy boolean NOT NULL DEFAULT false,
	preferred_name character varying(150)
);

ALTER TABLE academic_institutions ADD CONSTRAINT institutions_pkey PRIMARY KEY (id);
ALTER TABLE academic_profiles ADD CONSTRAINT academic_profile_pkey PRIMARY KEY (id);
ALTER TABLE academic_terms ADD CONSTRAINT academic_terms_pkey PRIMARY KEY (id);
ALTER TABLE addresses ADD CONSTRAINT addresses_pkey PRIMARY KEY (id);
ALTER TABLE chapter_partners ADD CONSTRAINT chapter_partners_pkey PRIMARY KEY (chapter_id, partner_id);
ALTER TABLE chapters ADD CONSTRAINT chapters_pkey PRIMARY KEY (id);
ALTER TABLE leadership_assignments ADD CONSTRAINT leadership_assignments_pkey PRIMARY KEY (id);
ALTER TABLE leadership_roles ADD CONSTRAINT leadership_roles_pkey PRIMARY KEY (id);
ALTER TABLE partner_contacts ADD CONSTRAINT partner_contacts_pkey PRIMARY KEY (contact_id);
ALTER TABLE partners ADD CONSTRAINT partners_pkey PRIMARY KEY (id);
ALTER TABLE professional_profiles ADD CONSTRAINT professional_profiles_pkey PRIMARY KEY (id);
ALTER TABLE project_artifacts ADD CONSTRAINT project_artifacts_pkey PRIMARY KEY (id);
ALTER TABLE project_assignments ADD CONSTRAINT project_assignments_pkey PRIMARY KEY (id);
ALTER TABLE project_engagements ADD CONSTRAINT project_engagements_pkey PRIMARY KEY (id);
ALTER TABLE project_roles ADD CONSTRAINT project_roles_pkey PRIMARY KEY (id);
ALTER TABLE projects ADD CONSTRAINT projects_pkey PRIMARY KEY (id);
ALTER TABLE volunteers ADD CONSTRAINT volunteers_pkey PRIMARY KEY (id);
ALTER TABLE academic_institutions ADD CONSTRAINT unique_academic_institutions UNIQUE (name);
ALTER TABLE chapters ADD CONSTRAINT unique_chapters UNIQUE (name);
ALTER TABLE chapters ADD CONSTRAINT unique_codes UNIQUE (code);
ALTER TABLE partners ADD CONSTRAINT unique_partners UNIQUE (name);
ALTER TABLE projects ADD CONSTRAINT unique_projects UNIQUE (name);
ALTER TABLE academic_profiles ADD CONSTRAINT is_legacy CHECK ((((degree_type IS NOT NULL) AND (major IS NOT NULL)) OR is_legacy)) NOT VALID;
ALTER TABLE addresses ADD CONSTRAINT country_upper CHECK (((country_code)::text = upper((country_code)::text))) NOT VALID;
ALTER TABLE chapters ADD CONSTRAINT code_lower CHECK (((code)::text = lower((code)::text))) NOT VALID;
ALTER TABLE chapters ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['active'::text, 'inactive'::text, 'pending'::text, 'supported'::text, 'suspended'::text]))) NOT VALID;
ALTER TABLE partner_contacts ADD CONSTRAINT partner_contacts_reachable CHECK (((email IS NOT NULL) OR (phone IS NOT NULL))) NOT VALID;
ALTER TABLE partners ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['active'::text, 'inactive'::text, 'suspended'::text]))) NOT VALID;
ALTER TABLE project_artifacts ADD CONSTRAINT type_valid CHECK (((type)::text = ANY (ARRAY['code'::text, 'design'::text, 'prd'::text, 'live'::text, 'other'::text]))) NOT VALID;
ALTER TABLE project_engagements ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['active'::text, 'inactive'::text, 'completed'::text, 'paused'::text, 'cancelled'::text]))) NOT VALID;
ALTER TABLE projects ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['draft::text'::text, 'approved::text'::text, 'active'::text, 'inactive'::text, 'suspended'::text, 'completed'::text]))) NOT VALID;
ALTER TABLE volunteer_accounts ADD CONSTRAINT provider_valid CHECK (((provider)::text = ANY (ARRAY['slack'::text, 'google'::text, 'notion'::text, 'github'::text, 'linkedin'::text, 'website'::text])));
ALTER TABLE volunteers ADD CONSTRAINT is_legacy CHECK (((email IS NOT NULL) OR is_legacy)) NOT VALID;
ALTER TABLE volunteers ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['active'::text, 'inactive'::text, 'hiatus'::text, 'suspended'::text])));
ALTER TABLE volunteers ADD CONSTRAINT type_valid CHECK (((type)::text = ANY (ARRAY['student'::text, 'alumni'::text, 'community'::text]))) NOT VALID;
ALTER TABLE academic_institutions ADD CONSTRAINT address_fk FOREIGN KEY (address_id) REFERENCES addresses(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE academic_profiles ADD CONSTRAINT graduation_term_fkey FOREIGN KEY (graduation_term_id) REFERENCES academic_terms(id);
ALTER TABLE academic_profiles ADD CONSTRAINT volunteer_fkey FOREIGN KEY (volunteer_id) REFERENCES volunteers(id) ON DELETE CASCADE;
ALTER TABLE academic_terms ADD CONSTRAINT academic_institution_fk FOREIGN KEY (academic_institution_id) REFERENCES academic_institutions(id) ON DELETE CASCADE;
ALTER TABLE chapter_partners ADD CONSTRAINT chapters_fk FOREIGN KEY (chapter_id) REFERENCES chapters(id) ON DELETE CASCADE;
ALTER TABLE chapter_partners ADD CONSTRAINT partners_fk FOREIGN KEY (partner_id) REFERENCES partners(id) ON DELETE CASCADE;
ALTER TABLE chapters ADD CONSTRAINT academic_institution_fkey FOREIGN KEY (academic_institution_id) REFERENCES academic_institutions(id) NOT VALID;
ALTER TABLE leadership_assignments ADD CONSTRAINT leadership_role_fk FOREIGN KEY (leadership_role_id) REFERENCES leadership_roles(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE partner_contacts ADD CONSTRAINT partners_fkey FOREIGN KEY (partner_id) REFERENCES partners(id) ON DELETE CASCADE;
ALTER TABLE partners ADD CONSTRAINT address_fk FOREIGN KEY (address_id) REFERENCES addresses(id) NOT VALID;
ALTER TABLE professional_profiles ADD CONSTRAINT volunteer_fkey FOREIGN KEY (volunteer_id) REFERENCES volunteers(id) ON DELETE CASCADE;
ALTER TABLE project_artifacts ADD CONSTRAINT project_fkey FOREIGN KEY (project_id) REFERENCES projects(id) NOT VALID;
ALTER TABLE project_assignments ADD CONSTRAINT project_engagement_fkey FOREIGN KEY (project_engagement_id) REFERENCES project_engagements(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE project_assignments ADD CONSTRAINT project_role_fkey FOREIGN KEY (project_role_id) REFERENCES project_roles(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE project_assignments ADD CONSTRAINT volunteer_fkey FOREIGN KEY (volunteer_id) REFERENCES volunteers(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE project_engagements ADD CONSTRAINT chapter_fkey FOREIGN KEY (chapter_id) REFERENCES chapters(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE project_engagements ADD CONSTRAINT partner_fkey FOREIGN KEY (partner_id) REFERENCES partners(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE project_engagements ADD CONSTRAINT project_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE volunteer_accounts ADD CONSTRAINT volunteer_fkey FOREIGN KEY (volunteer_id) REFERENCES volunteers(id);
ALTER TABLE volunteers ADD CONSTRAINT chapter_fkey FOREIGN KEY (chapter_id) REFERENCES chapters(id);
