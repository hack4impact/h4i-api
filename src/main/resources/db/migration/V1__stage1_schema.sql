-- H4I member portal, stage 1 (Members, Approvals, Claim, Join): the whole schema in one migration.
-- Made 2026-10-13 from Membership-Project-Portal's V1-V17, keeping only what stage 1 uses (wiki: staged-rollout).
-- Later stages add their tables in new migrations.

CREATE DOMAIN tool_name AS text
	CONSTRAINT tool_name_check CHECK ((VALUE ~ '^[a-z][a-z0-9_]*$'::text));

CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
	NEW.updated_at = now();
	RETURN NEW;
END
$$;

-- Things for national to do by hand (e.g. create a Google account when a chapter has no org unit yet).
CREATE TABLE admin_task (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tool tool_name NOT NULL,
    action text NOT NULL,
    description text NOT NULL,
    person_id uuid,
    status text DEFAULT 'open'::text NOT NULL,
    reminders_sent integer DEFAULT 0 NOT NULL,
    done_by uuid,
    done_at timestamp with time zone,
    cancelled_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT admin_task_status_check CHECK ((status = ANY (ARRAY['open'::text, 'done'::text, 'cancelled'::text])))
);

-- An applicant's session (before they have an H4I account): an httpOnly cookie tied to one registration; token hashed.
CREATE TABLE applicant_session (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    registration_id uuid NOT NULL,
    token_hash text NOT NULL,
    github_state text,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- Who did what, append-only.
CREATE TABLE audit_event (
    id bigint NOT NULL,
    at timestamp with time zone DEFAULT now() NOT NULL,
    actor_type text NOT NULL,
    actor_person_id uuid,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id uuid,
    chapter_id uuid,
    before jsonb,
    after jsonb,
    CONSTRAINT audit_event_actor_type_check CHECK ((actor_type = ANY (ARRAY['person'::text, 'sync'::text, 'system'::text, 'import'::text]))),
    CONSTRAINT audit_event_check CHECK (((actor_type = 'person'::text) = (actor_person_id IS NOT NULL)))
);

ALTER TABLE audit_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- Chapters (imported from the member database). google_domain/google_org_unit: where new members' accounts go; applications_open/application_url: how people apply.
CREATE TABLE chapter (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    institution_id uuid,
    status text DEFAULT 'active'::text NOT NULL,
    website text,
    email text,
    source_id uuid,
    imported_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    google_domain text,
    google_org_unit text,
    applications_open boolean DEFAULT true NOT NULL,
    application_url text,
    CONSTRAINT chapter_code_check CHECK ((code ~ '^[a-z0-9][a-z0-9_-]*$'::text)),
    CONSTRAINT chapter_status_check CHECK ((status = ANY (ARRAY['active'::text, 'inactive'::text, 'pending'::text, 'supported'::text, 'suspended'::text])))
);

-- Which chapters a person belongs to (the member database holds one chapter per person).
CREATE TABLE chapter_membership (
    person_id uuid NOT NULL,
    chapter_id uuid NOT NULL,
    joined_on date,
    left_on date,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- A chapter's own questions on its join page.
CREATE TABLE chapter_question (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    chapter_id uuid NOT NULL,
    "position" integer NOT NULL,
    label text NOT NULL,
    kind text DEFAULT 'text'::text NOT NULL,
    options text[] DEFAULT '{}'::text[] NOT NULL,
    required boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chapter_question_kind_check CHECK ((kind = ANY (ARRAY['text'::text, 'long_text'::text, 'choice'::text, 'yes_no'::text])))
);

-- Chapter leads, co-leads and viewers (the member database can't record them yet).
CREATE TABLE chapter_role (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    person_id uuid NOT NULL,
    chapter_id uuid NOT NULL,
    role text NOT NULL,
    title text,
    starts_at timestamp with time zone DEFAULT now() NOT NULL,
    ends_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chapter_role_role_check CHECK ((role = ANY (ARRAY['lead'::text, 'co_lead'::text, 'viewer'::text])))
);

-- Single-use claim links for imported members (14 days); only the hash of the token sent by email is kept.
CREATE TABLE claim_link (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    person_id uuid NOT NULL,
    token_hash text NOT NULL,
    sent_to text[] NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    emailed_at timestamp with time zone
);

-- One-time codes sent to school addresses (10 minutes, a few tries).
CREATE TABLE email_code (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    chapter_id uuid NOT NULL,
    email text NOT NULL,
    code_hash text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- Every email sent, without its body (bodies carry codes and links).
CREATE TABLE email_message (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    to_address text NOT NULL,
    template text NOT NULL,
    subject text NOT NULL,
    status text NOT NULL,
    error text,
    registration_id uuid,
    person_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT email_message_status_check CHECK ((status = ANY (ARRAY['sent'::text, 'failed'::text])))
);

-- Spring Modulith's outbox: events are kept until their listeners finish, and retried at startup.
CREATE TABLE event_publication (
    id uuid NOT NULL,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamp with time zone NOT NULL,
    completion_date timestamp with time zone,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamp with time zone
);

-- Runs of the member database importer, with counts and issues.
CREATE TABLE import_run (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source text DEFAULT 'member_db'::text NOT NULL,
    status text DEFAULT 'running'::text NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    counts jsonb,
    issues jsonb,
    error text,
    CONSTRAINT import_run_status_check CHECK ((status = ANY (ARRAY['running'::text, 'completed'::text, 'failed'::text])))
);

-- Schools; email_domains decide which school addresses a chapter's join page accepts.
CREATE TABLE institution (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    email_domains text[] DEFAULT '{}'::text[] NOT NULL,
    source_id uuid,
    imported_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- National admins.
CREATE TABLE national_admin (
    person_id uuid NOT NULL,
    granted_by uuid,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);

-- People. source_id: their volunteer ID in the member database; claimed_at: when they confirmed an imported record.
CREATE TABLE person (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    first_name text NOT NULL,
    last_name text NOT NULL,
    preferred_name text,
    school_email text,
    personal_email text,
    org_email text,
    phone text,
    graduation_term_id uuid,
    status text DEFAULT 'applicant'::text NOT NULL,
    kind text DEFAULT 'student'::text NOT NULL,
    claimed_at timestamp with time zone,
    source_id uuid,
    source_status text,
    imported_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT person_kind_check CHECK ((kind = ANY (ARRAY['student'::text, 'community'::text]))),
    CONSTRAINT person_status_check CHECK ((status = ANY (ARRAY['applicant'::text, 'active'::text, 'alumni'::text, 'removal_requested'::text, 'removed'::text])))
);

-- One application: a new student (new), an imported member claiming their record (claim), or a roster invite (roster).
CREATE TABLE registration (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    chapter_id uuid NOT NULL,
    kind text NOT NULL,
    status text DEFAULT 'started'::text NOT NULL,
    person_id uuid,
    roster_invite_id uuid,
    school_email text,
    email_verified_at timestamp with time zone,
    first_name text,
    last_name text,
    preferred_name text,
    personal_email text,
    graduation_year integer,
    role_interest text,
    still_active boolean,
    answers jsonb DEFAULT '{}'::jsonb NOT NULL,
    github_id text,
    github_login text,
    github_linked_at timestamp with time zone,
    github_changed boolean DEFAULT false NOT NULL,
    match_status text,
    matched_person_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    submitted_at timestamp with time zone,
    decided_by uuid,
    decided_at timestamp with time zone,
    decline_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT registration_kind_check CHECK ((kind = ANY (ARRAY['new'::text, 'claim'::text, 'roster'::text]))),
    CONSTRAINT registration_match_status_check CHECK ((match_status = ANY (ARRAY['new'::text, 'returning'::text, 'alumni'::text, 'duplicate'::text]))),
    CONSTRAINT registration_status_check CHECK ((status = ANY (ARRAY['started'::text, 'submitted'::text, 'approved'::text, 'declined'::text, 'completed'::text])))
);

-- Accepted members a lead uploaded; each gets a personal, pre-filled join link.
CREATE TABLE roster_invite (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    chapter_id uuid NOT NULL,
    uploaded_by uuid,
    first_name text NOT NULL,
    last_name text NOT NULL,
    school_email text NOT NULL,
    personal_email text,
    token_hash text NOT NULL,
    status text DEFAULT 'sent'::text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    emailed_at timestamp with time zone,
    CONSTRAINT roster_invite_status_check CHECK ((status = ANY (ARRAY['sent'::text, 'started'::text, 'completed'::text, 'expired'::text])))
);

-- Semesters per school (graduation terms).
CREATE TABLE term (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    institution_id uuid NOT NULL,
    season text NOT NULL,
    year smallint NOT NULL,
    code text,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    source_id uuid,
    imported_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT term_check CHECK ((ends_on > starts_on)),
    CONSTRAINT term_season_check CHECK ((season = ANY (ARRAY['spring'::text, 'summer'::text, 'fall'::text, 'winter'::text])))
);

-- A person's accounts in other tools (Google, GitHub, and the Slack and Notion IDs imported from the member database).
CREATE TABLE tool_account (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    person_id uuid NOT NULL,
    tool tool_name NOT NULL,
    external_id text,
    external_login text,
    state text DEFAULT 'unverified'::text NOT NULL,
    verified_at timestamp with time zone,
    source_id uuid,
    imported_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tool_account_check CHECK (((external_id IS NOT NULL) OR (external_login IS NOT NULL))),
    CONSTRAINT tool_account_state_check CHECK ((state = ANY (ARRAY['unverified'::text, 'invited'::text, 'accepted'::text, 'confirmed'::text, 'suspended'::text, 'removed'::text])))
);

ALTER TABLE ONLY admin_task
    ADD CONSTRAINT admin_task_pkey PRIMARY KEY (id);

ALTER TABLE ONLY applicant_session
    ADD CONSTRAINT applicant_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY applicant_session
    ADD CONSTRAINT applicant_session_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY chapter
    ADD CONSTRAINT chapter_code_key UNIQUE (code);

ALTER TABLE ONLY chapter_membership
    ADD CONSTRAINT chapter_membership_pkey PRIMARY KEY (person_id, chapter_id);

ALTER TABLE ONLY chapter
    ADD CONSTRAINT chapter_name_key UNIQUE (name);

ALTER TABLE ONLY chapter
    ADD CONSTRAINT chapter_pkey PRIMARY KEY (id);

ALTER TABLE ONLY chapter_question
    ADD CONSTRAINT chapter_question_chapter_id_position_key UNIQUE (chapter_id, "position");

ALTER TABLE ONLY chapter_question
    ADD CONSTRAINT chapter_question_pkey PRIMARY KEY (id);

ALTER TABLE ONLY chapter_role
    ADD CONSTRAINT chapter_role_pkey PRIMARY KEY (id);

ALTER TABLE ONLY chapter
    ADD CONSTRAINT chapter_source_id_key UNIQUE (source_id);

ALTER TABLE ONLY claim_link
    ADD CONSTRAINT claim_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY claim_link
    ADD CONSTRAINT claim_link_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY email_code
    ADD CONSTRAINT email_code_pkey PRIMARY KEY (id);

ALTER TABLE ONLY email_message
    ADD CONSTRAINT email_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY import_run
    ADD CONSTRAINT import_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY institution
    ADD CONSTRAINT institution_name_key UNIQUE (name);

ALTER TABLE ONLY institution
    ADD CONSTRAINT institution_pkey PRIMARY KEY (id);

ALTER TABLE ONLY institution
    ADD CONSTRAINT institution_source_id_key UNIQUE (source_id);

ALTER TABLE ONLY national_admin
    ADD CONSTRAINT national_admin_pkey PRIMARY KEY (person_id);

ALTER TABLE ONLY person
    ADD CONSTRAINT person_pkey PRIMARY KEY (id);

ALTER TABLE ONLY person
    ADD CONSTRAINT person_source_id_key UNIQUE (source_id);

ALTER TABLE ONLY registration
    ADD CONSTRAINT registration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY roster_invite
    ADD CONSTRAINT roster_invite_pkey PRIMARY KEY (id);

ALTER TABLE ONLY roster_invite
    ADD CONSTRAINT roster_invite_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY term
    ADD CONSTRAINT term_institution_id_season_year_key UNIQUE (institution_id, season, year);

ALTER TABLE ONLY term
    ADD CONSTRAINT term_pkey PRIMARY KEY (id);

ALTER TABLE ONLY term
    ADD CONSTRAINT term_source_id_key UNIQUE (source_id);

ALTER TABLE ONLY tool_account
    ADD CONSTRAINT tool_account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tool_account
    ADD CONSTRAINT tool_account_source_id_key UNIQUE (source_id);

ALTER TABLE ONLY tool_account
    ADD CONSTRAINT tool_account_tool_external_id_key UNIQUE (tool, external_id);

CREATE INDEX admin_task_open_idx ON admin_task USING btree (tool) WHERE (status = 'open'::text);

CREATE INDEX audit_event_chapter_idx ON audit_event USING btree (chapter_id, at);

CREATE INDEX audit_event_target_idx ON audit_event USING btree (target_type, target_id);

CREATE INDEX chapter_role_chapter_idx ON chapter_role USING btree (chapter_id) WHERE (ends_at IS NULL);

CREATE INDEX chapter_role_person_idx ON chapter_role USING btree (person_id) WHERE (ends_at IS NULL);

CREATE INDEX claim_link_person_idx ON claim_link USING btree (person_id);

CREATE INDEX email_code_email_idx ON email_code USING btree (lower(email), created_at DESC);

CREATE INDEX event_publication_by_completion_date_idx ON event_publication USING btree (completion_date);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);

CREATE INDEX person_org_email_idx ON person USING btree (lower(org_email));

CREATE INDEX person_personal_email_idx ON person USING btree (lower(personal_email));

CREATE INDEX person_school_email_idx ON person USING btree (lower(school_email));

CREATE INDEX registration_chapter_status_idx ON registration USING btree (chapter_id, status);

CREATE INDEX tool_account_person_tool_idx ON tool_account USING btree (person_id, tool);

CREATE TRIGGER admin_task_updated_at BEFORE UPDATE ON admin_task FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER chapter_role_updated_at BEFORE UPDATE ON chapter_role FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER chapter_updated_at BEFORE UPDATE ON chapter FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER institution_updated_at BEFORE UPDATE ON institution FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER person_updated_at BEFORE UPDATE ON person FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER term_updated_at BEFORE UPDATE ON term FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER tool_account_updated_at BEFORE UPDATE ON tool_account FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY admin_task
    ADD CONSTRAINT admin_task_done_by_fkey FOREIGN KEY (done_by) REFERENCES person(id);

ALTER TABLE ONLY admin_task
    ADD CONSTRAINT admin_task_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY applicant_session
    ADD CONSTRAINT applicant_session_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES registration(id);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_actor_person_id_fkey FOREIGN KEY (actor_person_id) REFERENCES person(id);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY chapter
    ADD CONSTRAINT chapter_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES institution(id);

ALTER TABLE ONLY chapter_membership
    ADD CONSTRAINT chapter_membership_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY chapter_membership
    ADD CONSTRAINT chapter_membership_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY chapter_question
    ADD CONSTRAINT chapter_question_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY chapter_role
    ADD CONSTRAINT chapter_role_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY chapter_role
    ADD CONSTRAINT chapter_role_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY claim_link
    ADD CONSTRAINT claim_link_created_by_fkey FOREIGN KEY (created_by) REFERENCES person(id);

ALTER TABLE ONLY claim_link
    ADD CONSTRAINT claim_link_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY email_code
    ADD CONSTRAINT email_code_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY email_message
    ADD CONSTRAINT email_message_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY email_message
    ADD CONSTRAINT email_message_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES registration(id);

ALTER TABLE ONLY national_admin
    ADD CONSTRAINT national_admin_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES person(id);

ALTER TABLE ONLY national_admin
    ADD CONSTRAINT national_admin_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY person
    ADD CONSTRAINT person_graduation_term_id_fkey FOREIGN KEY (graduation_term_id) REFERENCES term(id);

ALTER TABLE ONLY registration
    ADD CONSTRAINT registration_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY registration
    ADD CONSTRAINT registration_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES person(id);

ALTER TABLE ONLY registration
    ADD CONSTRAINT registration_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY registration
    ADD CONSTRAINT registration_roster_invite_id_fkey FOREIGN KEY (roster_invite_id) REFERENCES roster_invite(id);

ALTER TABLE ONLY roster_invite
    ADD CONSTRAINT roster_invite_chapter_id_fkey FOREIGN KEY (chapter_id) REFERENCES chapter(id);

ALTER TABLE ONLY roster_invite
    ADD CONSTRAINT roster_invite_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES person(id);

ALTER TABLE ONLY term
    ADD CONSTRAINT term_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES institution(id);

ALTER TABLE ONLY tool_account
    ADD CONSTRAINT tool_account_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);
