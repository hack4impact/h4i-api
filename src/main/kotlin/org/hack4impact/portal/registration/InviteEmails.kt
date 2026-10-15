package org.hack4impact.portal.registration

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CLAIM_LINK
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.ROSTER_INVITE
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import java.time.OffsetDateTime
import java.util.UUID

/** Roster invites a lead just uploaded; emailed after the upload commits. */
data class RosterInvited(val invites: List<UUID>)

/** Claim links national just created; emailed after they commit. */
data class ClaimLinksCreated(val links: List<UUID>)

/**
 * Emails roster invites and claim links from the outbox, in the background (second review M4): a 500-row roster no
 * longer sends 500 emails inside the lead's request, and if the app stops partway, the ones not emailed yet go out
 * when it starts again. Each email carries a new token, and only its hash is stored, so the outbox never holds a
 * usable link. If any email fails, the event stays unfinished and is retried on the next start, for those only.
 */
@Component
class InviteEmails(private val dsl: DSLContext, private val mailer: Mailer, @Value("\${portal.base-url}") private val baseUrl: String) {
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	fun on(event: RosterInvited) {
		val invites = dsl.select(ROSTER_INVITE.ID, ROSTER_INVITE.FIRST_NAME, ROSTER_INVITE.SCHOOL_EMAIL, CHAPTER.NAME, CHAPTER.CODE).from(ROSTER_INVITE)
			.join(CHAPTER).on(CHAPTER.ID.eq(ROSTER_INVITE.CHAPTER_ID))
			.where(ROSTER_INVITE.ID.`in`(event.invites), ROSTER_INVITE.EMAILED_AT.isNull, ROSTER_INVITE.STATUS.eq("sent"))
			.fetch()
		var failed = 0
		for ((id, first, school, chapter, code) in invites) {
			val token = Tokens.new()
			dsl.update(ROSTER_INVITE).set(ROSTER_INVITE.TOKEN_HASH, Tokens.hash(token)).where(ROSTER_INVITE.ID.eq(id)).execute()
			if (mailer.send(Emails.rosterInvite(first!!, chapter!!, "${baseUrl.trimEnd('/')}/join/$code?invite=$token"), school!!)) {
				dsl.update(ROSTER_INVITE).set(ROSTER_INVITE.EMAILED_AT, OffsetDateTime.now()).where(ROSTER_INVITE.ID.eq(id)).execute()
			} else {
				failed++
			}
		}
		check(failed == 0) { "$failed of ${invites.size} roster invites weren't emailed; they're tried again when the app restarts" }
	}

	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	fun on(event: ClaimLinksCreated) {
		val links = dsl.select(CLAIM_LINK.ID, CLAIM_LINK.PERSON_ID, CLAIM_LINK.SENT_TO, PERSON.FIRST_NAME).from(CLAIM_LINK)
			.join(PERSON).on(PERSON.ID.eq(CLAIM_LINK.PERSON_ID))
			.where(CLAIM_LINK.ID.`in`(event.links), CLAIM_LINK.EMAILED_AT.isNull, CLAIM_LINK.USED_AT.isNull)
			.fetch()
		var failed = 0
		for ((id, person, addresses, first) in links) {
			val token = Tokens.new()
			dsl.update(CLAIM_LINK).set(CLAIM_LINK.TOKEN_HASH, Tokens.hash(token)).where(CLAIM_LINK.ID.eq(id)).execute()
			val email = Emails.claim(first ?: "there", "${baseUrl.trimEnd('/')}/claim/$token", RegistrationService.LINK_LIFETIME.toDays().toInt())
			// Every address on file gets it (PRD); one that arrives is enough to count it as sent.
			if (addresses!!.map { mailer.send(email, it!!, person = person) }.any { it }) {
				dsl.update(CLAIM_LINK).set(CLAIM_LINK.EMAILED_AT, OffsetDateTime.now()).where(CLAIM_LINK.ID.eq(id)).execute()
			} else {
				failed++
			}
		}
		check(failed == 0) { "$failed of ${links.size} claim links weren't emailed; they're tried again when the app restarts" }
	}
}
