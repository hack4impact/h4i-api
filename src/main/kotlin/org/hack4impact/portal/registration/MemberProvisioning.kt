package org.hack4impact.portal.registration

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.hack4impact.portal.db.tables.records.RegistrationRecord
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.ROSTER_INVITE
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.hack4impact.portal.mail.NewAccount
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Turns an approved registration or a completed claim into a member (PRD steps 7–8 and claim step 4):
 *
 *  1. the person: the matched or claimed record, or a new one; status active (a claim saying they've left: alumni);
 *  2. their chapter membership and the GitHub account they signed in with;
 *  3. their Google account, if they don't have one: first@<chapter domain> in the chapter's org unit (decision 108);
 *     if that isn't possible, national gets an admin task instead of the approval failing;
 *  4. an email with their account, and the applicant session ends.
 */
@Component
class MemberProvisioning(
	private val dsl: DSLContext,
	private val accounts: ObjectProvider<GoogleAccounts>,
	private val mailer: Mailer,
	private val sessions: ApplicantSessions,
	private val transactions: TransactionTemplate,
	private val memberDb: org.hack4impact.portal.memberdb.MemberDbWrites,
	private val agreements: org.hack4impact.portal.agreements.AgreementService,
	@Value("\${portal.frontend-url}") private val frontendUrl: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Returns the member's person ID. [decidedBy] is null when no lead decided (claims, roster invites). */
	fun provision(registration: UUID, target: UUID?, decidedBy: UUID?): UUID {
		val (r, person) = transactions.execute {
			val r = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(registration)).forUpdate().fetchSingle()
			check(r.status in setOf("started", "submitted")) { "registration is already ${r.status}" }
			val claim = r.kind == "claim"
			val person = (if (claim) r.personId else target) ?: newPerson(r)
			updatePerson(person, r, claim)
			// The member database gets the record in the same commit as the change (wiki: staged-rollout).
			memberDb.request(person)
			dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, person).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, r.chapterId)
				.onConflictDoNothing().execute()
			dsl.update(CHAPTER_MEMBERSHIP).setNull(CHAPTER_MEMBERSHIP.LEFT_ON)
				.where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(person), CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(r.chapterId)).execute()
			linkGitHub(person, r)
			dsl.update(REGISTRATION)
				.set(REGISTRATION.STATUS, if (claim) "completed" else "approved").set(REGISTRATION.PERSON_ID, person)
				.set(REGISTRATION.DECIDED_BY, decidedBy).set(REGISTRATION.DECIDED_AT, OffsetDateTime.now()).set(REGISTRATION.UPDATED_AT, OffsetDateTime.now())
				.where(REGISTRATION.ID.eq(registration)).execute()
			agreements.attach(registration, person) // the document they signed is now theirs
			r.rosterInviteId?.let { dsl.update(ROSTER_INVITE).set(ROSTER_INVITE.STATUS, "completed").where(ROSTER_INVITE.ID.eq(it)).execute() }
			audit(decidedBy, if (claim) "registration.claim" else "registration.approve", registration, r.chapterId!!, person)
			r to person
		}
		sessions.revoke(registration)

		// Outside the transaction: the account is created in Google, and an email goes out.
		val account = googleAccount(person, r)
		val name = r.preferredName?.takeIf { it.isNotBlank() } ?: r.firstName ?: "there"
		val chapterName = dsl.select(CHAPTER.NAME).from(CHAPTER).where(CHAPTER.ID.eq(r.chapterId)).fetchSingle().value1()!!
		val email = if (r.kind == "claim") Emails.claimed(name, account, frontendUrl) else Emails.approved(name, chapterName, account, frontendUrl)
		listOfNotNull(r.personalEmail, r.schoolEmail).distinct().forEach { mailer.send(email, it, registration, person) }
		return person
	}

	private fun newPerson(r: RegistrationRecord): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, r.firstName ?: "?").set(PERSON.LAST_NAME, r.lastName ?: "?").set(PERSON.STATUS, "active")
			.returningResult(PERSON.ID).fetchSingle().value1()!!

	/** What the person told us wins over imported data (decision 23); empty answers change nothing. */
	private fun updatePerson(person: UUID, r: RegistrationRecord, claim: Boolean) {
		val status = if (claim && r.stillActive == false) "alumni" else "active"
		var update = dsl.update(PERSON).set(PERSON.STATUS, status).set(PERSON.UPDATED_AT, OffsetDateTime.now())
		r.firstName?.takeIf { it.isNotBlank() }?.let { update = update.set(PERSON.FIRST_NAME, it.trim()) }
		r.lastName?.takeIf { it.isNotBlank() }?.let { update = update.set(PERSON.LAST_NAME, it.trim()) }
		r.preferredName?.let { update = update.set(PERSON.PREFERRED_NAME, it.trim().ifEmpty { null }) }
		r.schoolEmail?.let { update = update.set(PERSON.SCHOOL_EMAIL, it.trim().lowercase()) }
		r.personalEmail?.takeIf { it.isNotBlank() }?.let { update = update.set(PERSON.PERSONAL_EMAIL, it.trim().lowercase()) }
		r.major?.takeIf { it.isNotBlank() }?.let { update = update.set(PERSON.MAJOR, it.trim()) }
		r.degreeType?.takeIf { it.isNotBlank() }?.let { update = update.set(PERSON.DEGREE_TYPE, it.trim()) }
		if (r.graduationSeason != null && r.graduationYear != null) {
			update = update.set(PERSON.GRADUATION_SEASON, r.graduationSeason).set(PERSON.GRADUATION_YEAR, r.graduationYear!!.toShort())
				.set(PERSON.GRADUATION_TERM_ID, graduationTerm(r.chapterId!!, r.graduationSeason!!, r.graduationYear!!))
		}
		// They confirmed their details (a claim, an approval or a roster invite): from now on the portal's version wins,
		// and an imported record they were matched to no longer waits for a claim.
		update = update.set(PERSON.CLAIMED_AT, DSL.coalesce(PERSON.CLAIMED_AT, DSL.currentOffsetDateTime()))
		update.where(PERSON.ID.eq(person)).execute()
	}

	/** The term at the chapter's school for that season and year, if national has seeded it (Q51); else none yet. */
	private fun graduationTerm(chapter: UUID, season: String, year: Int): UUID? =
		dsl.select(TERM.ID).from(TERM).join(CHAPTER).on(CHAPTER.INSTITUTION_ID.eq(TERM.INSTITUTION_ID))
			.where(CHAPTER.ID.eq(chapter), TERM.SEASON.eq(season), TERM.YEAR.eq(year.toShort())).limit(1).fetchOne()?.value1()

	/**
	 * Records the GitHub account they signed in with (verified by OAuth). An imported login-only row becomes the
	 * verified account; an account already linked to someone else is left alone and flagged for national.
	 */
	private fun linkGitHub(person: UUID, r: RegistrationRecord) {
		val id = r.githubId ?: return
		val owner = dsl.select(TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.eq(id)).fetchOne()?.value1()
		if (owner != null && owner != person) {
			task("github", "check_account", person, "GitHub account ${r.githubLogin} is linked to two people: check which is right")
			return
		}
		if (owner == person) return
		val loginOnly = dsl.select(TOOL_ACCOUNT.ID).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.isNull).limit(1).fetchOne()?.value1()
		if (loginOnly != null) {
			dsl.update(TOOL_ACCOUNT).set(TOOL_ACCOUNT.EXTERNAL_ID, id).set(TOOL_ACCOUNT.EXTERNAL_LOGIN, r.githubLogin).set(TOOL_ACCOUNT.STATE, "confirmed")
				.set(TOOL_ACCOUNT.VERIFIED_AT, OffsetDateTime.now()).where(TOOL_ACCOUNT.ID.eq(loginOnly)).execute()
		} else {
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_ID, id)
				.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, r.githubLogin).set(TOOL_ACCOUNT.STATE, "confirmed").set(TOOL_ACCOUNT.VERIFIED_AT, OffsetDateTime.now()).execute()
		}
	}

	/** The member's new Google account, or null if they have one already or it couldn't be made (then a task). */
	private fun googleAccount(person: UUID, r: RegistrationRecord): NewAccount? {
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(person)).fetchSingle()
		if (p.orgEmail != null) return null
		val chapter = dsl.selectFrom(CHAPTER).where(CHAPTER.ID.eq(r.chapterId)).fetchSingle()
		val domain = chapter.googleDomain ?: "${chapter.code}.hack4impact.org"
		val google = accounts.ifAvailable
		val unit = chapter.googleOrgUnit
		val name = "${p.firstName} ${p.lastName}"
		if (google == null || unit == null) {
			task("google", "create_account", person, "Create a Google account for $name in ${unit ?: "the chapter's org unit (not set yet)"} at $domain")
			return null
		}
		return try {
			val account = google.create(p.firstName!!, p.lastName!!, domain, unit, p.personalEmail ?: p.schoolEmail) { address ->
				dsl.fetchExists(PERSON, org.jooq.impl.DSL.lower(PERSON.ORG_EMAIL).eq(address.lowercase())) ||
					dsl.fetchExists(TOOL_ACCOUNT, TOOL_ACCOUNT.TOOL.eq("google"), org.jooq.impl.DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(address.lowercase()))
			}
			dsl.update(PERSON).set(PERSON.ORG_EMAIL, account.email).where(PERSON.ID.eq(person)).execute()
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "google").set(TOOL_ACCOUNT.EXTERNAL_ID, account.userId)
				.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, account.email).set(TOOL_ACCOUNT.STATE, "confirmed").set(TOOL_ACCOUNT.VERIFIED_AT, OffsetDateTime.now()).execute()
			memberDb.request(person) // again, now with their H4I address
			account
		} catch (e: AdapterException) {
			log.warn("Google account for {} not created: {}", name, e.message)
			task("google", "create_account", person, "Create a Google account for $name in $unit at $domain (the portal couldn't: ${e.message})")
			null
		}
	}

	private fun task(tool: String, action: String, person: UUID, description: String) {
		dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, tool).set(ADMIN_TASK.ACTION, action).set(ADMIN_TASK.PERSON_ID, person)
			.set(ADMIN_TASK.DESCRIPTION, description).execute()
	}

	private fun audit(actor: UUID?, action: String, registration: UUID, chapter: UUID, person: UUID) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (actor != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, actor)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "registration").set(AUDIT_EVENT.TARGET_ID, registration)
			.set(AUDIT_EVENT.CHAPTER_ID, chapter).set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"person":"$person","on":"${LocalDate.now()}"}"""))
			.execute()
	}
}
