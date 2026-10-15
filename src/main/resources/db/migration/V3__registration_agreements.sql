-- The document applicants sign through Documenso during registration (Q52, decision 162; the full portal's step 11,
-- registration agreements only). National sets up which Documenso template each kind uses; versions are kept, one
-- active per kind. Project agreements come with stage 2.
CREATE TABLE agreement_template (
	id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	kind                  text NOT NULL CHECK (kind IN ('volunteer', 'code_of_conduct')),
	name                  text NOT NULL,
	documenso_template_id text NOT NULL,
	version               integer NOT NULL,
	active                boolean NOT NULL DEFAULT true,
	created_at            timestamptz NOT NULL DEFAULT now(),
	UNIQUE (documenso_template_id, version)
);
CREATE UNIQUE INDEX agreement_template_active_idx ON agreement_template (kind) WHERE active;

-- One applicant's (later, the member's) copy of a template. Documenso's status is the truth: the webhook and a regular
-- pass re-read the envelope.
CREATE TABLE agreement (
	id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	template_id           uuid NOT NULL REFERENCES agreement_template (id),
	registration_id       uuid REFERENCES registration (id),
	person_id             uuid REFERENCES person (id), -- set on approval or claim
	documenso_document_id text UNIQUE,
	status                text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'sent', 'signed', 'declined', 'expired', 'voided')),
	sent_at               timestamptz,
	signed_at             timestamptz,
	pdf_location          text, -- 'database' once the signed PDF is kept in agreement_pdf
	decline_reason        text,
	error                 text,
	checked_at            timestamptz,
	created_at            timestamptz NOT NULL DEFAULT now(),
	updated_at            timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT agreement_owner CHECK (person_id IS NOT NULL OR registration_id IS NOT NULL)
);
CREATE UNIQUE INDEX agreement_registration_idx ON agreement (registration_id, template_id)
	WHERE registration_id IS NOT NULL AND status IN ('draft', 'sent', 'signed');
CREATE INDEX agreement_open_checked_idx ON agreement (checked_at NULLS FIRST) WHERE status = 'sent';
CREATE TRIGGER agreement_updated_at BEFORE UPDATE ON agreement FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The signed PDFs, kept (the full portal's decision 119): one row per document in the envelope.
CREATE TABLE agreement_pdf (
	agreement_id uuid NOT NULL REFERENCES agreement (id),
	position     integer NOT NULL,
	content      bytea NOT NULL,
	sha256       text NOT NULL,
	created_at   timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (agreement_id, position)
);
