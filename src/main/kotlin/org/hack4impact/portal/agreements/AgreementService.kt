package org.hack4impact.portal.agreements

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.documenso.DocumensoClient
import org.hack4impact.portal.db.tables.records.AgreementRecord
import org.hack4impact.portal.db.tables.records.AgreementTemplateRecord
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_PDF
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID

/** [batch]: how many agreements one pass checks, so a backlog can't flood Documenso. */
@ConfigurationProperties("portal.agreements")
data class AgreementProperties(val batch: Int = 100)

/** An applicant's view of a registration agreement: [signUrl] while it waits for their signature. */
data class ApplicantAgreement(val kind: String, val name: String, val status: String, val signUrl: String?)

/**
 * The document applicants sign through Documenso during registration (Q52; the full portal's step 11, registration
 * agreements only). Required once national sets up a template and Documenso can send; it becomes the member's on
 * approval or claim. Documenso's status is the truth: the webhook and the regular pass both re-read the envelope.
 */
@Component
class AgreementService(
	private val dsl: DSLContext,
	private val documenso: ObjectProvider<DocumensoClient>,
	private val adapters: AdapterProperties,
	private val properties: AgreementProperties,
	@Value("\${portal.base-url}") private val baseUrl: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	companion object {
		val REGISTRATION_KINDS = listOf("volunteer", "code_of_conduct")
	}

	private fun client(): DocumensoClient? = documenso.ifAvailable

	/** Whether agreements may be sent: Documenso connected with its write switch on. */
	fun canSend(): Boolean = client() != null && adapters.documenso.write

	fun activeTemplate(kind: String): AgreementTemplateRecord? =
		dsl.selectFrom(AGREEMENT_TEMPLATE).where(AGREEMENT_TEMPLATE.KIND.eq(kind), AGREEMENT_TEMPLATE.ACTIVE.isTrue).fetchOne()

	private fun registrationTemplates(): List<AgreementTemplateRecord> = REGISTRATION_KINDS.mapNotNull { activeTemplate(it) }

	/** Whether applicants must sign before sending: only when Documenso can send and national set a template up. */
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
		val agreement = existing ?: dsl.insertInto(AGREEMENT).set(AGREEMENT.TEMPLATE_ID, template.id).set(AGREEMENT.REGISTRATION_ID, registration).returning().fetchSingle()
		if (agreement.status == "draft" && !send(agreement, template)) throw IllegalStateException("The agreement couldn't be prepared. Try again in a moment.")
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

	/** Sends a draft through Documenso, signed on the page (no email), back to /apply afterwards. A failure stays on the draft. */
	private fun send(agreement: AgreementRecord, template: AgreementTemplateRecord): Boolean {
		val client = client() ?: return false
		val r = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(agreement.registrationId)).fetchSingle()
		val email = r.schoolEmail ?: r.personalEmail ?: return fail(agreement, "no email address to send it to")
		val name = listOfNotNull(r.preferredName?.takeIf { it.isNotBlank() } ?: r.firstName, r.lastName).joinToString(" ").ifBlank { email }
		return try {
			val envelope = client.send(template.documensoTemplateId!!, "agreement:${agreement.id}", email, name, template.name!!, false, "${baseUrl.trimEnd('/')}/apply?signed=${template.kind}")
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
		dsl.update(AGREEMENT).set(AGREEMENT.CHECKED_AT, OffsetDateTime.now()).where(AGREEMENT.ID.eq(agreement.id)).execute()
		val envelope = try {
			client.envelope(agreement.documensoDocumentId!!, interactive)
		} catch (e: AdapterException) {
			dsl.update(AGREEMENT).set(AGREEMENT.ERROR, e.message?.take(500)).where(AGREEMENT.ID.eq(agreement.id)).execute()
			return
		}
		when (envelope.status) {
			"COMPLETED" -> {
				val signedAt = envelope.completedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: OffsetDateTime.now()
				dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "signed").set(AGREEMENT.SIGNED_AT, signedAt).setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
				archive(agreement.id!!, envelope, client)
				log.info("Agreement {} signed", agreement.id)
			}
			"REJECTED" -> dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "declined").set(AGREEMENT.DECLINE_REASON, envelope.signers.firstNotNullOfOrNull { it.rejectionReason }?.take(1000))
				.setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
			"CANCELLED" -> dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "voided").setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
			else -> if (agreement.error != null) dsl.update(AGREEMENT).setNull(AGREEMENT.ERROR).where(AGREEMENT.ID.eq(agreement.id)).execute()
		}
	}

	/** Keeps the signed PDFs. A failure leaves pdf_location empty for the next pass to retry. */
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

	/**
	 * Every few minutes: checks open agreements (longest unchecked first; the webhook usually got there first),
	 * cancels those of applications declined or left unfinished for a month, and fetches PDFs that failed before.
	 */
	@Scheduled(fixedDelayString = "\${portal.agreements.pass-every:PT5M}", initialDelayString = "PT1M")
	fun pass(): Int {
		val client = client() ?: return 0
		val open = dsl.selectFrom(AGREEMENT).where(AGREEMENT.STATUS.eq("sent")).orderBy(AGREEMENT.CHECKED_AT.asc().nullsFirst()).limit(properties.batch).fetch()
		open.forEach { refresh(it) }
		val abandoned = dsl.select(AGREEMENT.asterisk()).from(AGREEMENT).join(REGISTRATION).on(REGISTRATION.ID.eq(AGREEMENT.REGISTRATION_ID))
			.where(
				AGREEMENT.STATUS.`in`("draft", "sent"), AGREEMENT.PERSON_ID.isNull,
				REGISTRATION.STATUS.eq("declined").or(REGISTRATION.STATUS.eq("started").and(REGISTRATION.CREATED_AT.lt(OffsetDateTime.now().minusDays(30)))),
			).fetchInto(AGREEMENT)
		for (a in abandoned) {
			if (a.documensoDocumentId != null) {
				if (!canSend()) continue
				try { client.cancel(a.documensoDocumentId!!, "The application was closed") } catch (e: AdapterException) { fail(a, e.message); continue }
			}
			dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "voided").where(AGREEMENT.ID.eq(a.id)).execute()
		}
		val missing = dsl.selectFrom(AGREEMENT).where(AGREEMENT.STATUS.eq("signed"), AGREEMENT.PDF_LOCATION.isNull, AGREEMENT.DOCUMENSO_DOCUMENT_ID.isNotNull)
			.limit(properties.batch).fetch()
		missing.forEach { a -> try { archive(a.id!!, client.envelope(a.documensoDocumentId!!), client) } catch (e: AdapterException) { log.warn("Agreement {} PDF: {}", a.id, e.message) } }
		return open.size
	}

	/** The chapter an agreement belongs to (through its registration), for who may see its PDF. */
	fun chapterOf(agreement: AgreementRecord): UUID? =
		agreement.registrationId?.let { dsl.select(REGISTRATION.CHAPTER_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(it)).fetchOne()?.value1() }
}
