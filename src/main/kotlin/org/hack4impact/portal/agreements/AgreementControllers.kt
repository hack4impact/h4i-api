package org.hack4impact.portal.agreements

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.documenso.DocumensoClient
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_PDF
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

data class AgreementTemplateView(
	val id: UUID,
	/** volunteer, code_of_conduct (signed during registration) or project (stage 2). */
	val kind: String,
	val name: String,
	/** The template envelope's ID in Documenso. */
	val documensoTemplateId: String,
	val version: Int,
	val active: Boolean,
	val createdAt: OffsetDateTime,
	/** How many agreements were made from it. */
	val sent: Int,
)

data class AgreementTemplates(
	val templates: List<AgreementTemplateView>,
	/** Documenso is connected (read). */
	val connected: Boolean,
	/** Agreements actually go out (Documenso's write switch is on). */
	val sending: Boolean,
)

data class AgreementTemplateInput(val kind: String, val name: String, val documensoTemplateId: String)

/** Documenso's webhook, verified by the shared secret it sends in X-Documenso-Secret (PRD: webhooks verified). */
data class DocumensoEnvelopeChanged(val envelopeId: String)

@RestController
@RequestMapping("/api/webhooks/documenso")
class DocumensoWebhookController(private val adapters: AdapterProperties, private val refreshes: EnvelopeRefreshes) {
	@PostMapping
	fun receive(@RequestHeader("X-Documenso-Secret", required = false) secret: String?, @RequestBody body: JsonNode): ResponseEntity<Void> {
		val expected = adapters.documenso.webhookSecret
		if (expected.isBlank()) return ResponseEntity.notFound().build()
		if (secret == null || !MessageDigest.isEqual(secret.toByteArray(), expected.toByteArray())) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
		// Only a hint: the envelope is re-read from Documenso, so the payload's contents are never trusted.
		val envelope = body.path("payload").path("envelopeId").takeIf { it.isString }?.asString()
		envelope?.let { refreshes.request(it) }
		return ResponseEntity.accepted().build()
	}
}

@Component
class EnvelopeRefreshes(private val events: org.springframework.context.ApplicationEventPublisher, private val agreements: AgreementService) {
	@Transactional
	fun request(envelopeId: String) = events.publishEvent(DocumensoEnvelopeChanged(envelopeId))

	/** Outside any transaction, like the other listeners that talk to a tool (second review H2). */
	@ApplicationModuleListener(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	fun on(event: DocumensoEnvelopeChanged) {
		agreements.refreshEnvelope(event.envelopeId)
	}
}

/** National: which Documenso templates agreements are made from (one active per kind; a new one is the next version). */
@RestController
@RequestMapping("/api/national/agreement-templates")
class AgreementTemplateController(
	private val viewers: Viewers,
	private val dsl: DSLContext,
	private val agreements: AgreementService,
	private val documenso: ObjectProvider<DocumensoClient>,
) {
	private val json = JsonMapper.builder().build()

	private fun national(user: OidcUser): Viewer = viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping
	fun list(@AuthenticationPrincipal user: OidcUser): AgreementTemplates {
		national(user)
		val counts = dsl.select(AGREEMENT.TEMPLATE_ID, DSL.count()).from(AGREEMENT).groupBy(AGREEMENT.TEMPLATE_ID).fetch().associate { it.value1() to it.value2() }
		val templates = dsl.selectFrom(AGREEMENT_TEMPLATE).orderBy(AGREEMENT_TEMPLATE.KIND, AGREEMENT_TEMPLATE.VERSION.desc()).fetch {
			AgreementTemplateView(it.id!!, it.kind!!, it.name!!, it.documensoTemplateId!!, it.version!!, it.active!!, it.createdAt!!, counts[it.id] ?: 0)
		}
		return AgreementTemplates(templates, documenso.ifAvailable != null, agreements.canSend())
	}

	/** Checks the template in Documenso (it must exist and have a signer), then makes it the active one for its kind. */
	@PostMapping
	@Transactional
	fun add(@AuthenticationPrincipal user: OidcUser, @RequestBody input: AgreementTemplateInput): AgreementTemplates {
		val viewer = national(user)
		if (input.kind !in AgreementService.REGISTRATION_KINDS + "project") throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Kind is volunteer, code_of_conduct or project")
		if (input.name.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give it a name people will recognize, e.g. “Volunteer agreement”")
		// An ID, or a link to the template: Documenso's envelope IDs look like envelope_…
		val id = Regex("envelope_[A-Za-z0-9]+").find(input.documensoTemplateId)?.value ?: input.documensoTemplateId.trim().trimEnd('/').substringAfterLast('/')
		if (id.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the template's ID or link from Documenso")
		documenso.ifAvailable?.let { client ->
			try { client.templateSigner(id) } catch (e: AdapterException) {
				throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Documenso doesn't have a usable template $id: ${e.message}")
			}
		}
		val version = (dsl.select(DSL.max(AGREEMENT_TEMPLATE.VERSION)).from(AGREEMENT_TEMPLATE).where(AGREEMENT_TEMPLATE.KIND.eq(input.kind)).fetchOne()?.value1() ?: 0) + 1
		dsl.update(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.ACTIVE, false).where(AGREEMENT_TEMPLATE.KIND.eq(input.kind)).execute()
		dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, input.kind).set(AGREEMENT_TEMPLATE.NAME, input.name.trim())
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, id).set(AGREEMENT_TEMPLATE.VERSION, version).execute()
		audit(viewer, "agreement_template.add", mapOf("kind" to input.kind, "name" to input.name.trim(), "documensoTemplateId" to id, "version" to version))
		return list(user)
	}

	/** Stops making agreements of that kind (signed ones stay valid). */
	@PostMapping("/{id}/deactivate")
	@Transactional
	fun deactivate(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID): AgreementTemplates {
		val viewer = national(user)
		val updated = dsl.update(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.ACTIVE, false).where(AGREEMENT_TEMPLATE.ID.eq(id)).execute()
		if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such template")
		audit(viewer, "agreement_template.deactivate", mapOf("id" to id.toString()))
		return list(user)
	}

	private fun audit(viewer: Viewer, action: String, after: Map<String, Any?>) {
		dsl.insertInto(AUDIT_EVENT).set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "agreement_template").set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(after)))
			.execute()
	}
}

/** A signed agreement's PDF: national, the chapter's leads and co-leads, and the signer. Every download is audited. */
@RestController
@RequestMapping("/api/agreements")
class AgreementPdfController(private val viewers: Viewers, private val dsl: DSLContext, private val agreements: AgreementService) {
	@GetMapping("/{id}/pdf")
	@Transactional
	fun pdf(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID): ResponseEntity<ByteArray> {
		val viewer = viewers.of(user)
		val agreement = dsl.selectFrom(AGREEMENT).where(AGREEMENT.ID.eq(id)).fetchOne() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such agreement")
		val chapter = agreements.chapterOf(agreement)
		val own = agreement.personId != null && agreement.personId == viewer.personId
		if (!viewer.nationalAdmin && !own && (chapter == null || !viewer.canManage(chapter))) throw AccessDeniedException("Not your chapter's agreement")
		val pdf = dsl.select(AGREEMENT_PDF.CONTENT).from(AGREEMENT_PDF).where(AGREEMENT_PDF.AGREEMENT_ID.eq(id)).orderBy(AGREEMENT_PDF.POSITION).limit(1).fetchOne()?.value1()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No signed PDF yet")
		dsl.insertInto(AUDIT_EVENT).set(AUDIT_EVENT.ACTOR_TYPE, "person").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId).set(AUDIT_EVENT.ACTION, "agreement.download")
			.set(AUDIT_EVENT.TARGET_TYPE, "agreement").set(AUDIT_EVENT.TARGET_ID, id).set(AUDIT_EVENT.CHAPTER_ID, chapter).execute()
		val who = agreement.personId?.let { p -> dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME).from(PERSON).where(PERSON.ID.eq(p)).fetchOne()?.let { "${it.value1()}-${it.value2()}" } } ?: "applicant"
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("agreement-$who.pdf".replace(Regex("[^A-Za-z0-9._-]"), "_")).build().toString())
			.body(pdf)
	}
}
