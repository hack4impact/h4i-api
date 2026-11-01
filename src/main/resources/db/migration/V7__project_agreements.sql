-- Stage 2, package 6 (wiki: staged-rollout-stage-2, stage-2-migrations): the project agreement, sent when someone joins
-- a live project; until they sign it, the project's GitHub team and vault collection wait. From the full portal's V1 and
-- V10. The send gate stays stage 1's (Documenso connected and PORTAL_ADAPTERS_DOCUMENSO_WRITE): no tool_setting row.
ALTER TABLE agreement_template DROP CONSTRAINT agreement_template_kind_check;
ALTER TABLE agreement_template ADD CONSTRAINT agreement_template_kind_check CHECK (kind IN ('volunteer', 'code_of_conduct', 'project'));

ALTER TABLE agreement
	ADD COLUMN project_id       uuid REFERENCES project (id), -- null for registration agreements
	ADD COLUMN reminders_sent   integer NOT NULL DEFAULT 0,
	ADD COLUMN last_reminder_at timestamptz,
	ADD COLUMN lead_notified_at timestamptz; -- when the project's leads were told it's still unsigned

-- One live agreement per person and project; a signed one counts again if the person rejoins the project.
CREATE UNIQUE INDEX agreement_project_idx ON agreement (person_id, project_id)
	WHERE project_id IS NOT NULL AND status IN ('draft', 'sent', 'signed');
