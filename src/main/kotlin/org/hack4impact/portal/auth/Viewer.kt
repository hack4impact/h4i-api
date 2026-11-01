package org.hack4impact.portal.auth

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** The signed-in person and what they may see. Chapter roles come from the portal's own tables (decisions 35, 41). */
data class Viewer(
	val personId: UUID?,
	val email: String,
	val name: String?,
	val nationalAdmin: Boolean,
	/** Chapter ID → role: "lead", "co_lead" or "viewer". */
	val chapterRoles: Map<UUID, String>,
	/** Projects (not closed) where they hold a lead project role: they manage those teams (decision 133, stage 2). */
	val ledProjects: Set<UUID> = emptySet(),
) {
	fun canSee(chapterId: UUID) = nationalAdmin || chapterId in chapterRoles

	/** A project's team: its chapter's leads, co-leads and national, and the project's own leads. */
	fun canManageTeam(chapterId: UUID, projectId: UUID) = canManage(chapterId) || projectId in ledProjects

	/** Leads and co-leads change their chapter's setup; viewers only read (Q9). */
	fun canManage(chapterId: UUID) = nationalAdmin || chapterRoles[chapterId] in setOf("lead", "co_lead")
}

/**
 * Finds the portal person behind a Google sign-in: first by the Google user ID linked on an earlier sign-in,
 * then by their @hack4impact.org address (org email, or a Google account imported from the member DB).
 */
@Component
class Viewers(private val dsl: DSLContext) {
	private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

	fun of(user: OidcUser): Viewer {
		val email = user.email.orEmpty().lowercase()
		val personId = user.subject?.let(::byGoogleId) ?: email.takeIf { it.isNotEmpty() }?.let { byEmail(it, user.subject) }
		if (personId == null) return Viewer(null, email, user.fullName, nationalAdmin = false, chapterRoles = emptyMap())
		// Removed people and applicants hold no role, whatever was left unended (review M4).
		val status = dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ID.eq(personId)).fetchOne()?.value1()
		if (status in setOf("removed", "applicant")) return Viewer(personId, email, user.fullName, nationalAdmin = false, chapterRoles = emptyMap())
		val nationalAdmin = dsl.fetchExists(NATIONAL_ADMIN, NATIONAL_ADMIN.PERSON_ID.eq(personId))
		val roles = dsl.select(CHAPTER_ROLE.CHAPTER_ID, CHAPTER_ROLE.ROLE)
			.from(CHAPTER_ROLE).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_ROLE.CHAPTER_ID))
			.where(CHAPTER_ROLE.PERSON_ID.eq(personId), CHAPTER_ROLE.ENDS_AT.isNull, CHAPTER.DELETED_AT.isNull)
			.fetch()
			// A person with two roles in one chapter gets the stronger one.
			.groupBy({ it.value1()!! }, { it.value2()!! })
			.mapValues { (_, roles) -> ROLE_ORDER.first { it in roles } }
		val led = dsl.select(PROJECT_MEMBER.PROJECT_ID).from(PROJECT_MEMBER)
			.join(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID)).join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID))
			.where(PROJECT_MEMBER.PERSON_ID.eq(personId), PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT_ROLE.IS_LEAD.isTrue, PROJECT.STATUS.ne("closed"))
			.fetch().map { it.value1()!! }.toSet()
		return Viewer(personId, email, user.fullName, nationalAdmin, roles, led)
	}

	/** Remembers the Google user ID for a matched person, so later sign-ins don't depend on the email. */
	fun linkGoogleAccount(user: OidcUser) {
		val email = user.email?.lowercase() ?: return
		val subject = user.subject ?: return
		if (byGoogleId(subject) != null) return
		val personId = byEmail(email, subject) ?: return
		val now = OffsetDateTime.now()
		// Only fills in an address's missing ID: never replaces the Google account already linked (second review H4).
		val updated = dsl.update(TOOL_ACCOUNT)
			.set(TOOL_ACCOUNT.EXTERNAL_ID, subject)
			.set(TOOL_ACCOUNT.STATE, "confirmed")
			.set(TOOL_ACCOUNT.VERIFIED_AT, now)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(personId), TOOL_ACCOUNT.TOOL.eq("google"), DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(email), TOOL_ACCOUNT.EXTERNAL_ID.isNull)
			.execute()
		if (updated == 0) {
			dsl.insertInto(TOOL_ACCOUNT)
				.set(TOOL_ACCOUNT.PERSON_ID, personId).set(TOOL_ACCOUNT.TOOL, "google")
				.set(TOOL_ACCOUNT.EXTERNAL_ID, subject).set(TOOL_ACCOUNT.EXTERNAL_LOGIN, email)
				.set(TOOL_ACCOUNT.STATE, "confirmed").set(TOOL_ACCOUNT.VERIFIED_AT, now)
				.execute()
		}
	}

	private fun byGoogleId(subject: String): UUID? =
		dsl.select(TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT).join(PERSON).on(PERSON.ID.eq(TOOL_ACCOUNT.PERSON_ID))
			.where(TOOL_ACCOUNT.TOOL.eq("google"), TOOL_ACCOUNT.EXTERNAL_ID.eq(subject), PERSON.DELETED_AT.isNull)
			.fetchOne(TOOL_ACCOUNT.PERSON_ID)

	/**
	 * The person behind an address, unless they already have a different Google account linked: an address can be
	 * reused after an account is deleted, and the new holder mustn't sign in as the old one (second review H4).
	 */
	private fun byEmail(email: String, subject: String?): UUID? {
		val person = byAddress(email) ?: return null
		val linked = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq("google"), TOOL_ACCOUNT.EXTERNAL_ID.isNotNull).fetch().map { it.value1() }
		if (subject != null && linked.isNotEmpty() && subject !in linked) {
			log.warn("Sign-in as {} refused a match: that person has a different Google account linked (a reused address?)", email)
			return null
		}
		return person
	}

	private fun byAddress(email: String): UUID? =
		dsl.select(PERSON.ID).from(PERSON)
			.where(PERSON.DELETED_AT.isNull)
			.and(
				DSL.lower(PERSON.ORG_EMAIL).eq(email).or(
					DSL.exists(
						DSL.selectOne().from(TOOL_ACCOUNT).where(
							TOOL_ACCOUNT.PERSON_ID.eq(PERSON.ID), TOOL_ACCOUNT.TOOL.eq("google"), DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(email),
						),
					),
				),
			)
			.orderBy(PERSON.CREATED_AT)
			.limit(1)
			.fetchOne(PERSON.ID)

	companion object {
		private val ROLE_ORDER = listOf("lead", "co_lead", "viewer")
	}
}

/** Thrown into a 403 when a viewer asks for a chapter they have no role in. */
fun Viewer.requireChapter(chapterId: UUID) {
	if (!canSee(chapterId)) throw AccessDeniedException("No role in this chapter")
}
