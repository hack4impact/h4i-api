package org.hack4impact.portal.agreements

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.documenso.DocumensoClient
import org.hack4impact.portal.db.tables.records.AgreementRecord
import org.hack4impact.portal.db.tables.records.AgreementTemplateRecord
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_PDF
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.SyncRequests
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID

/** Reminder and escalation timing (PRD: unsigned agreements get reminders; the project lead is told after a set period). */
@ConfigurationProperties("portal.agreements")
data class AgreementProperties(
	val remindAfter: Duration = Duration.ofDays(3),
	val remindEvery: Duration = Duration.ofDays(3),
	val maxReminders: Int = 3,
	val notifyLeadAfter: Duration = Duration.ofDays(7),
	/** How many agreements one pass sends or checks, so a backlog can't flood Documenso. */
	val batch: Int = 100,
)

/** An applicant's view of a registration agreement: [signUrl] while it waits for their signature. */
data class ApplicantAgreement(val kind: String, val name: String, val status: String, val signUrl: String?)

/**
 * Agreements through Documenso. The volunteer agreement and code of conduct are signed during registration (stage 1,
 * Q52); the project agreement goes out when someone joins a live project (stage 2, from the full portal's step 11). A
 * signature marks the project member signed and asks for a sync, which releases the GitHub team and vault collection.
 *
 * Documenso's status is the truth: webhooks and the regular pass both just re-read the envelope ([refresh]). The send
 * gate is stage 1's for every kind: Documenso connected and its write switch on (no tool setting, unlike the full portal).
 */
@Component
class AgreementService(
	private val dsl: DSLContext,
	private val documenso: ObjectProvider<DocumensoClient>,
	private val adapters: AdapterProperties,
	private val properties: AgreementProperties,
	private val mailer: Mailer,
	private val syncs: SyncRequests,
	private val tools: org.hack4impact.portal.tools.ToolRegistry,
	private val locks: org.hack4impact.portal.sync.RunLocks,
	@Value("\${portal.frontend-url}") private val frontendUrl: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	companion object {
		val REGISTRATION_KINDS = listOf("volunteer", "code_of_conduct")
		private val LIVE_PROJECTS = listOf("active", "paused")
	}

	private fun client(): DocumensoClient? = documenso.ifAvailable

	/** Whether agreements may be sent: Documenso connected with its write switch on (stage 1's gate, for every kind). */
	fun canSend(): Boolean = client() != null && adapters.documenso.write

	/** Project agreements go out: a project template is active and Documenso can send (package 6 review R1). */
	fun projectAgreementsReady(): Boolean = canSend() && activeTemplate("project") != null

	/** Documenso is connected, so open agreements can be read and signed. */
	fun canRead(): Boolean = client() != null

	/** The signer's link for a sent agreement, or null when Documenso can't give one now. */
	fun signingUrl(agreement: AgreementRecord): String? {
		val client = client() ?: return null
		if (agreement.status != "sent" || agreement.documensoDocumentId == null) return null
		return try { client.envelope(agreement.documensoDocumentId!!, interactive = true).signers.firstOrNull()?.signingUrl } catch (e: AdapterException) { null }
	}

	fun activeTemplate(kind: String): AgreementTemplateRecord? =
		dsl.selectFrom(AGREEMENT_TEMPLATE).where(AGREEMENT_TEMPLATE.KIND.eq(kind), AGREEMENT_TEMPLATE.ACTIVE.isTrue).fetchOne()

	// ---- Project agreements ---------------------------------------------------------------------------------------

	/**
	 * Sends the project agreement to members of live projects who haven't signed one ([projectId]: just that
	 * project). Someone who signed this project's agreement before (they left and came back) counts as signed again.
	 */
	fun sendProjectAgreements(projectId: UUID? = null): Int =
		// One sender at a time: the listener and the regular pass could otherwise send one agreement twice (review M12).
		locks.exclusive(org.hack4impact.portal.sync.RunLocks.AGREEMENTS) { sendProjectAgreementsNow(projectId) }

	private fun sendProjectAgreementsNow(projectId: UUID?): Int {
		val template = activeTemplate("project") ?: return 0
		val waiting = dsl.select(PROJECT_MEMBER.PROJECT_ID, PROJECT_MEMBER.PERSON_ID, PROJECT.NAME, PROJECT_MEMBER.ADDED_AT).from(PROJECT_MEMBER)
			.join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID))
			.where(
				PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT_MEMBER.AGREEMENT_STATUS.eq("pending"), PROJECT.STATUS.`in`(LIVE_PROJECTS),
				if (projectId != null) PROJECT.ID.eq(projectId) else org.jooq.impl.DSL.noCondition(),
			)
			.limit(properties.batch).fetch()
		var sent = 0
		for (w in waiting) {
			val (project, person, projectName) = Triple(w.value1()!!, w.value2()!!, w.value3()!!)
			// Declined since they joined: not sent again (the leads were told). Removing and re-adding them starts over
			// (second review M1).
			if (dsl.fetchExists(AGREEMENT, AGREEMENT.PERSON_ID.eq(person), AGREEMENT.PROJECT_ID.eq(project), AGREEMENT.STATUS.eq("declined"), AGREEMENT.UPDATED_AT.ge(w.value4()))) continue
			val existing = dsl.selectFrom(AGREEMENT)
				.where(AGREEMENT.PERSON_ID.eq(person), AGREEMENT.PROJECT_ID.eq(project), AGREEMENT.STATUS.`in`("draft", "sent", "signed")).fetchOne()
			when {
				existing?.status == "signed" -> markMemberSigned(existing)
				existing?.status == "sent" -> Unit
				canSend() -> {
					val agreement = existing ?: draft(template, person = person, project = project)
					// Back to the frontend's page for this agreement after signing (wiki: staged-rollout-stage-2).
					if (sendOne(agreement, "${template.name}: $projectName", emailLink = true, redirectUrl = "${frontendUrl.trimEnd('/')}/agreements/${agreement.id}")) sent++
				}
			}
		}
		return sent
	}

	private fun draft(template: AgreementTemplateRecord, person: UUID? = null, project: UUID? = null, registration: UUID? = null): AgreementRecord =
		dsl.insertInto(AGREEMENT).set(AGREEMENT.TEMPLATE_ID, template.id).set(AGREEMENT.PERSON_ID, person).set(AGREEMENT.PROJECT_ID, project)
			.set(AGREEMENT.REGISTRATION_ID, registration).returning().fetchSingle()

	/** Sends a draft agreement through Documenso. A failure stays on the draft (in [AgreementRecord.error]) and is retried next pass. */
	private fun sendOne(agreement: AgreementRecord, title: String, emailLink: Boolean, redirectUrl: String?): Boolean {
		val client = client() ?: return false
		val template = dsl.selectFrom(AGREEMENT_TEMPLATE).where(AGREEMENT_TEMPLATE.ID.eq(agreement.templateId)).fetchSingle()
		val (email, name) = signer(agreement) ?: return fail(agreement, "no email address to send it to")
		return try {
			val envelope = client.send(template.documensoTemplateId!!, "agreement:${agreement.id}", email, name, title, emailLink, redirectUrl)
			dsl.update(AGREEMENT).set(AGREEMENT.DOCUMENSO_DOCUMENT_ID, envelope.id).set(AGREEMENT.STATUS, "sent").set(AGREEMENT.SENT_AT, OffsetDateTime.now())
				.setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
			true
		} catch (e: AdapterException) {
			fail(agreement, e.message)
		}
	}

	private fun fail(agreement: AgreementRecord, error: String?): Boolean {
		log.warn("Agreement {} not sent: {}", agreement.id, error)
		dsl.update(AGREEMENT).set(AGREEMENT.ERROR, error?.take(500)).where(AGREEMENT.ID.eq(agreement.id)).execute()
		return false
	}

	/** Who signs: the person's H4I address (else school, else personal), or the applicant's school address. */
	private fun signer(agreement: AgreementRecord): Pair<String, String>? {
		agreement.personId?.let { id ->
			val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(id)).fetchSingle()
			val email = p.orgEmail ?: p.schoolEmail ?: p.personalEmail ?: return null
			return email to "${p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName} ${p.lastName}"
		}
		val r = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(agreement.registrationId)).fetchSingle()
		val email = r.schoolEmail ?: r.personalEmail ?: return null
		return email to listOfNotNull(r.preferredName?.takeIf { it.isNotBlank() } ?: r.firstName, r.lastName).joinToString(" ").ifBlank { email }
	}

	// ---- Reading Documenso ----------------------------------------------------------------------------------------

	/** Re-reads the envelope behind a webhook. Unknown envelopes (not the portal's) are ignored. */
	fun refreshEnvelope(envelopeId: String): Boolean {
		val agreement = dsl.selectFrom(AGREEMENT).where(AGREEMENT.DOCUMENSO_DOCUMENT_ID.eq(envelopeId)).fetchOne() ?: return false
		refresh(agreement)
		return true
	}

	/** Brings one sent agreement up to date with Documenso: signed, declined or cancelled there. */
	fun refresh(agreement: AgreementRecord, interactive: Boolean = false) {
		val client = client() ?: return
		if (agreement.status != "sent" || agreement.documensoDocumentId == null) return
		// Checked now, whatever the answer: the pass takes the longest-unchecked first, so every one comes round (review M6).
		dsl.update(AGREEMENT).set(AGREEMENT.CHECKED_AT, OffsetDateTime.now()).where(AGREEMENT.ID.eq(agreement.id)).execute()
		val envelope = try {
			client.envelope(agreement.documensoDocumentId!!, interactive)
		} catch (e: AdapterException) {
			dsl.update(AGREEMENT).set(AGREEMENT.ERROR, e.message?.take(500)).where(AGREEMENT.ID.eq(agreement.id)).execute()
			return
		}
		when (envelope.status) {
			"COMPLETED" -> signed(agreement, envelope, client)
			"REJECTED" -> declined(agreement, envelope.signers.firstNotNullOfOrNull { it.rejectionReason })
			"CANCELLED" -> dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "voided").setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
			else -> if (agreement.error != null) dsl.update(AGREEMENT).setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
		}
	}

	private fun signed(agreement: AgreementRecord, envelope: DocumensoClient.Envelope, client: DocumensoClient) {
		val signedAt = envelope.completedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: OffsetDateTime.now()
		dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "signed").set(AGREEMENT.SIGNED_AT, signedAt).setNull(AGREEMENT.ERROR)
			.where(AGREEMENT.ID.eq(agreement.id)).execute()
		archive(agreement.id!!, envelope, client)
		markMemberSigned(agreement)
		log.info("Agreement {} signed", agreement.id)
	}

	/** Keeps the signed PDFs (decision 119). A failure leaves pdf_location empty for the next pass to retry. */
	private fun archive(agreementId: UUID, envelope: DocumensoClient.Envelope, client: DocumensoClient) {
		try {
			envelope.items.forEachIndexed { i, item ->
				val pdf = client.signedPdf(item)
				dsl.insertInto(AGREEMENT_PDF).set(AGREEMENT_PDF.AGREEMENT_ID, agreementId).set(AGREEMENT_PDF.POSITION, i).set(AGREEMENT_PDF.CONTENT, pdf)
					.set(AGREEMENT_PDF.SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
					.onConflictDoNothing().execute()
			}
			dsl.update(AGREEMENT).set(AGREEMENT.PDF_LOCATION, "database").where(AGREEMENT.ID.eq(agreementId)).execute()
		} catch (e: AdapterException) {
			log.warn("Signed PDF for agreement {} not archived yet: {}", agreementId, e.message)
		}
	}

	/** A project agreement signed: the member's gated resources can follow. */
	private fun markMemberSigned(agreement: AgreementRecord) {
		val project = agreement.projectId ?: return
		val updated = dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed")
			.where(PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.PERSON_ID.eq(agreement.personId), PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT_MEMBER.AGREEMENT_STATUS.eq("pending"))
			.execute()
		if (updated > 0) syncs.request("agreement", tools.sensitive) // what an agreement holds back: repositories and vaults
	}

	private fun declined(agreement: AgreementRecord, reason: String?) {
		dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "declined").set(AGREEMENT.DECLINE_REASON, reason?.take(1000)).setNull(AGREEMENT.ERROR)
			.where(AGREEMENT.ID.eq(agreement.id)).execute()
		val project = agreement.projectId ?: return
		val (person, projectName) = names(agreement.personId!!, project)
		leads(project).forEach { (lead, email) -> mailer.send(Emails.agreementDeclined(lead, person, projectName, reason), email, person = agreement.personId) }
	}

	// ---- The regular pass -----------------------------------------------------------------------------------------

	/** Everything that keeps agreements moving; run every few minutes ([AgreementSchedule]) and by the sandbox suite. */
	fun pass(now: OffsetDateTime = OffsetDateTime.now()): PassReport {
		if (client() == null) return PassReport()
		val sent = sendProjectAgreements()
		val open = dsl.selectFrom(AGREEMENT).where(AGREEMENT.STATUS.eq("sent")).orderBy(AGREEMENT.CHECKED_AT.asc().nullsFirst()).limit(properties.batch).fetch()
		open.forEach { refresh(it) }
		val voided = voidLeft()
		val reminded = remind(now)
		val notified = notifyLeads(now)
		val archived = archiveMissing()
		return PassReport(sent, open.size, reminded, notified, voided, archived)
	}

	data class PassReport(val sent: Int = 0, val checked: Int = 0, val reminded: Int = 0, val leadsNotified: Int = 0, val voided: Int = 0, val archived: Int = 0)

	/** Unsigned project agreements of people who left the project, or whose project closed: cancelled in Documenso. */
	private fun voidLeft(): Int {
		val stale = dsl.select(AGREEMENT.asterisk()).from(AGREEMENT).join(PROJECT).on(PROJECT.ID.eq(AGREEMENT.PROJECT_ID))
			.where(
				AGREEMENT.STATUS.`in`("draft", "sent"),
				PROJECT.STATUS.notIn(LIVE_PROJECTS).or(
					org.jooq.impl.DSL.notExists(
						dsl.selectOne().from(PROJECT_MEMBER).where(PROJECT_MEMBER.PROJECT_ID.eq(AGREEMENT.PROJECT_ID), PROJECT_MEMBER.PERSON_ID.eq(AGREEMENT.PERSON_ID), PROJECT_MEMBER.REMOVED_AT.isNull),
					),
				),
			).fetchInto(AGREEMENT)
		// Registration agreements of applications declined, or left unfinished for a month: they'd hold their place in
		// the pass's batch for ever (review M6).
		val abandoned = dsl.select(AGREEMENT.asterisk()).from(AGREEMENT).join(REGISTRATION).on(REGISTRATION.ID.eq(AGREEMENT.REGISTRATION_ID))
			.where(
				AGREEMENT.STATUS.`in`("draft", "sent"), AGREEMENT.PERSON_ID.isNull,
				REGISTRATION.STATUS.eq("declined").or(REGISTRATION.STATUS.eq("started").and(REGISTRATION.CREATED_AT.lt(OffsetDateTime.now().minusDays(30)))),
			).fetchInto(AGREEMENT)
		var voided = 0
		for (a in stale + abandoned) {
			if (a.documensoDocumentId != null) {
				if (!canSend()) continue
				try { client()!!.cancel(a.documensoDocumentId!!, if (a.projectId != null) "No longer on the project" else "The application was closed") } catch (e: AdapterException) { fail(a, e.message); continue }
			}
			dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "voided").where(AGREEMENT.ID.eq(a.id)).execute()
			voided++
		}
		return voided
	}

	/** Documenso emails the link again after [AgreementProperties.remindAfter], then every remindEvery, up to maxReminders. */
	private fun remind(now: OffsetDateTime): Int {
		if (!canSend()) return 0
		val due = dsl.selectFrom(AGREEMENT)
			.where(AGREEMENT.STATUS.eq("sent"), AGREEMENT.PROJECT_ID.isNotNull, AGREEMENT.REMINDERS_SENT.lt(properties.maxReminders))
			.fetch().filter { a ->
				val since = a.lastReminderAt ?: a.sentAt ?: return@filter false
				!since.plus(if (a.remindersSent == 0) properties.remindAfter else properties.remindEvery).isAfter(now)
			}
		var reminded = 0
		for (a in due) {
			try {
				val client = client()!!
				val waiting = client.envelope(a.documensoDocumentId!!).signers.filter { it.status == "NOT_SIGNED" }.map { it.recipientId }
				if (waiting.isNotEmpty()) client.remind(a.documensoDocumentId!!, waiting)
				dsl.update(AGREEMENT).set(AGREEMENT.REMINDERS_SENT, a.remindersSent!! + 1).set(AGREEMENT.LAST_REMINDER_AT, now).where(AGREEMENT.ID.eq(a.id)).execute()
				reminded++
			} catch (e: AdapterException) {
				fail(a, e.message)
			}
		}
		return reminded
	}

	/** PRD: the project lead is notified when an agreement stays unsigned (once per agreement). */
	private fun notifyLeads(now: OffsetDateTime): Int {
		val overdue = dsl.selectFrom(AGREEMENT)
			.where(AGREEMENT.STATUS.eq("sent"), AGREEMENT.PROJECT_ID.isNotNull, AGREEMENT.LEAD_NOTIFIED_AT.isNull, AGREEMENT.SENT_AT.le(now.minus(properties.notifyLeadAfter)))
			.fetch()
		for (a in overdue) {
			val (person, project) = names(a.personId!!, a.projectId!!)
			val days = Duration.between(a.sentAt, now).toDays()
			leads(a.projectId!!).forEach { (lead, email) -> mailer.send(Emails.agreementOverdue(lead, person, project, days), email, person = a.personId) }
			dsl.update(AGREEMENT).set(AGREEMENT.LEAD_NOTIFIED_AT, now).where(AGREEMENT.ID.eq(a.id)).execute()
		}
		return overdue.size
	}

	/** Signed agreements whose PDFs couldn't be fetched at the time. */
	private fun archiveMissing(): Int {
		val client = client() ?: return 0
		val missing = dsl.selectFrom(AGREEMENT).where(AGREEMENT.STATUS.eq("signed"), AGREEMENT.PDF_LOCATION.isNull, AGREEMENT.DOCUMENSO_DOCUMENT_ID.isNotNull)
			.limit(properties.batch).fetch()
		missing.forEach { a -> try { archive(a.id!!, client.envelope(a.documensoDocumentId!!), client) } catch (e: AdapterException) { log.warn("Agreement {} PDF: {}", a.id, e.message) } }
		return missing.size
	}

	/** The project's leads (a lead project role), else the chapter's leads and co-leads: (first name, address). */
	private fun leads(project: UUID): List<Pair<String, String>> {
		val projectLeads = dsl.select(PERSON.FIRST_NAME, PERSON.PREFERRED_NAME, PERSON.ORG_EMAIL).from(PROJECT_MEMBER)
			.join(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID)).join(PERSON).on(PERSON.ID.eq(PROJECT_MEMBER.PERSON_ID))
			.where(PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT_ROLE.IS_LEAD.isTrue, PERSON.ORG_EMAIL.isNotNull)
			.fetch()
		val rows = projectLeads.ifEmpty {
			dsl.select(PERSON.FIRST_NAME, PERSON.PREFERRED_NAME, PERSON.ORG_EMAIL).from(CHAPTER_ROLE).join(PERSON).on(PERSON.ID.eq(CHAPTER_ROLE.PERSON_ID))
				.join(PROJECT).on(PROJECT.CHAPTER_ID.eq(CHAPTER_ROLE.CHAPTER_ID))
				.where(PROJECT.ID.eq(project), CHAPTER_ROLE.ROLE.`in`("lead", "co_lead"), CHAPTER_ROLE.ENDS_AT.isNull, PERSON.ORG_EMAIL.isNotNull).fetch()
		}
		return rows.map { (it.value2()?.takeIf { n -> n.isNotBlank() } ?: it.value1()!!) to it.value3()!! }.distinctBy { it.second }
	}

	private fun names(person: UUID, project: UUID): Pair<String, String> {
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(person)).fetchSingle()
		val projectName = dsl.select(PROJECT.NAME).from(PROJECT).where(PROJECT.ID.eq(project)).fetchSingle().value1()!!
		return "${p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName} ${p.lastName}" to projectName
	}

	// ---- Registration agreements ----------------------------------------------------------------------------------

	private fun registrationTemplates(): List<AgreementTemplateRecord> = REGISTRATION_KINDS.mapNotNull { activeTemplate(it) }

	/** Whether applicants must sign before sending: only when Documenso can send and national set the templates up. */
	fun registrationRequired(): Boolean = canSend() && registrationTemplates().isNotEmpty()

	/** The applicant's agreements, re-read from Documenso when they come back from signing. */
	fun applicantAgreements(registration: UUID): List<ApplicantAgreement> {
		if (!registrationRequired()) return emptyList()
		return registrationTemplates().map { t ->
			var a = registrationAgreement(registration, t)
			if (a?.status == "sent") {
				refresh(a, interactive = true)
				a = registrationAgreement(registration, t)
			}
			val url = if (a?.status == "sent") runCatching { client()!!.envelope(a.documensoDocumentId!!, interactive = true).signers.firstOrNull()?.signingUrl }.getOrNull() else null
			ApplicantAgreement(t.kind!!, t.name!!, a?.status ?: "not_started", url)
		}
	}

	/** Starts (or resumes) signing [kind]: the Documenso page to send the applicant to. */
	fun startRegistrationAgreement(registration: UUID, kind: String): String {
		val template = registrationTemplates().firstOrNull { it.kind == kind } ?: throw IllegalArgumentException("No $kind agreement to sign")
		val existing = registrationAgreement(registration, template)
		if (existing?.status == "signed") throw IllegalStateException("Already signed")
		val agreement = existing ?: draft(template, registration = registration)
		if (agreement.status == "draft" && !sendOne(agreement, template.name!!, emailLink = false, redirectUrl = "${frontendUrl.trimEnd('/')}/apply?signed=$kind")) {
			throw IllegalStateException("The agreement couldn't be prepared. Try again in a moment.")
		}
		val sent = dsl.selectFrom(AGREEMENT).where(AGREEMENT.ID.eq(agreement.id)).fetchSingle()
		return client()!!.envelope(sent.documensoDocumentId!!, interactive = true).signers.firstOrNull()?.signingUrl ?: throw IllegalStateException("The agreement has no signer")
	}

	/** What's still unsigned, in words, for the applicant's checklist. */
	fun missingFor(registration: UUID): List<String> {
		if (!registrationRequired()) return emptyList()
		return registrationTemplates().filter { registrationAgreement(registration, it)?.status != "signed" }.map { "the ${it.name}" }
	}

	/** On approval or claim: the registration's agreements become the person's. */
	fun attach(registration: UUID, person: UUID) {
		dsl.update(AGREEMENT).set(AGREEMENT.PERSON_ID, person).where(AGREEMENT.REGISTRATION_ID.eq(registration)).execute()
	}

	private fun registrationAgreement(registration: UUID, template: AgreementTemplateRecord): AgreementRecord? =
		dsl.selectFrom(AGREEMENT).where(AGREEMENT.REGISTRATION_ID.eq(registration), AGREEMENT.TEMPLATE_ID.eq(template.id), AGREEMENT.STATUS.`in`("draft", "sent", "signed"))
			.fetchOne()

	/** The chapter an agreement belongs to (through its project or registration), for who may see its PDF. */
	fun chapterOf(agreement: AgreementRecord): UUID? =
		agreement.projectId?.let { dsl.select(PROJECT.CHAPTER_ID).from(PROJECT).where(PROJECT.ID.eq(it)).fetchOne()?.value1() }
			?: agreement.registrationId?.let { dsl.select(REGISTRATION.CHAPTER_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(it)).fetchOne()?.value1() }
}
