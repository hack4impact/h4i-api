package org.hack4impact.portal.adapters.documenso

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import java.util.concurrent.ConcurrentHashMap

/**
 * Documenso's envelope API (v2; its older document and template endpoints are deprecated). Agreements are envelopes
 * made from a template envelope with one signer (build plan step 11). [baseUrl] is the instance, e.g.
 * `https://sign.hack4impact.org`; signing links are `<baseUrl>/sign/<token>`.
 */
class DocumensoClient(
	private val baseUrl: String,
	private val token: String,
	private val http: HttpJson = HttpJson(Tool.DOCUMENSO),
	/** For reads while someone waits on a page: fails at once on a rate limit instead of waiting it out (review M11). */
	private val quick: HttpJson = HttpJson(Tool.DOCUMENSO),
) {
	/** [status]: NOT_SIGNED, SIGNED or REJECTED. */
	data class Signer(val recipientId: Int, val email: String, val signingUrl: String, val status: String, val rejectionReason: String?)

	/** [status]: DRAFT, PENDING, COMPLETED, REJECTED or CANCELLED. [items]: the envelope's PDFs, in order. */
	/** [type]: TEMPLATE or DOCUMENT. */
	data class Envelope(val id: String, val type: String, val status: String, val externalId: String?, val signers: List<Signer>, val items: List<String>, val completedAt: String?)

	private val api = "${baseUrl.trimEnd('/')}/api/v2"
	private val templateSigners = ConcurrentHashMap<String, Int>()

	/** The template's signer placeholder, which each agreement fills with the real person. */
	fun templateSigner(templateId: String): Int = templateSigners.getOrPut(templateId) {
		val template = envelope(templateId)
		// A document made from the template has an envelope ID too; only the template itself can make new agreements.
		if (template.type != "TEMPLATE") throw Rejected(Tool.DOCUMENSO, "$templateId is a document, not a template: use the template's ID")
		template.signers.firstOrNull()?.recipientId ?: throw Rejected(Tool.DOCUMENSO, "template $templateId has no signer")
	}

	/**
	 * Makes an envelope from [templateId] for one signer and sends it. With [email] false Documenso doesn't email the
	 * link (the portal sends the person straight to it, e.g. an applicant); [redirectUrl] is where signing returns to.
	 */
	fun send(templateId: String, externalId: String, email: String, name: String, title: String, emailLink: Boolean, redirectUrl: String?): Envelope {
		val payload = mapOf(
			"envelopeId" to templateId,
			"externalId" to externalId,
			"recipients" to listOf(mapOf("id" to templateSigner(templateId), "email" to email, "name" to name)),
			"distributeDocument" to true,
			"override" to buildMap {
				put("title", title)
				put("distributionMethod", if (emailLink) "EMAIL" else "NONE")
				redirectUrl?.let { put("redirectUrl", it) }
			},
		)
		val created = http.postMultipart("$api/envelope/use", mapOf("payload" to HttpJson.MAPPER.writeValueAsString(payload)), headers()).body
		return envelope(created.text("id") ?: throw Rejected(Tool.DOCUMENSO, "envelope/use returned no id"))
	}

	/** [interactive]: someone is waiting on the answer (a page load), so don't wait out a rate limit. */
	fun envelope(id: String, interactive: Boolean = false): Envelope {
		val e = (if (interactive) quick else http).get("$api/envelope/${HttpJson.encode(id)}", headers()).body
		return Envelope(
			e.text("id")!!, e.text("type") ?: "DOCUMENT", e.text("status") ?: "DRAFT", e.text("externalId"),
			e.path("recipients").toList().filter { it.text("role") == "SIGNER" }.map {
				Signer(it.path("id").asInt(), it.text("email") ?: "", "${baseUrl.trimEnd('/')}/sign/${it.text("token")}", it.text("signingStatus") ?: "NOT_SIGNED", it.text("rejectionReason"))
			},
			e.path("envelopeItems").toList().sortedBy { it.path("order").asInt() }.mapNotNull { it.text("id") },
			e.text("completedAt"),
		)
	}

	/** A signed PDF of one envelope item. */
	fun signedPdf(itemId: String): ByteArray = http.download("$api/envelope/item/${HttpJson.encode(itemId)}/download?version=signed", headers())

	/** Emails the signing link again (Documenso's own reminder) to signers who haven't signed. */
	fun remind(envelopeId: String, recipientIds: List<Int>) {
		http.postJson("$api/envelope/redistribute", mapOf("envelopeId" to envelopeId, "recipients" to recipientIds), headers())
	}

	/** Cancels a pending envelope (someone left the project before signing). */
	fun cancel(envelopeId: String, reason: String) {
		http.postJson("$api/envelope/cancel", mapOf("envelopeId" to envelopeId, "reason" to reason), headers())
	}

	/** Deletes an envelope (the sandbox suite's cleanup). */
	fun delete(envelopeId: String) {
		http.postJson("$api/envelope/delete", mapOf("envelopeId" to envelopeId), headers())
	}

	private fun headers() = mapOf("Authorization" to token)
}
