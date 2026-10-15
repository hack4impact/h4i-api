package org.hack4impact.portal.registration

import org.hack4impact.portal.db.tables.records.RegistrationRecord
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

data class MatchResult(val status: String, val people: Map<UUID, List<String>>)

/**
 * Matches a submitted registration against everyone the portal knows, imported members included (PRD: new,
 * returning, alumni record found, or possible duplicate). Strong evidence is what the applicant proved: their school
 * address (a code was sent there) and the GitHub account they signed in with, matched by its numeric ID. What they
 * only typed (a personal email), an imported GitHub login (unverified, and logins change hands) and a same-name person
 * in the chapter are weak evidence: alone they make a possible duplicate, never "returning". Never merges anyone: the
 * lead decides.
 */
@Component
class RegistrationMatcher(private val dsl: DSLContext) {
	fun match(r: RegistrationRecord): MatchResult {
		val why = mutableMapOf<UUID, MutableList<String>>()
		val strong = mutableSetOf<UUID>()
		fun people(condition: org.jooq.Condition) = dsl.select(PERSON.ID).from(PERSON).where(PERSON.DELETED_AT.isNull, condition).fetch().map { it.value1()!! }
		val anyEmail = listOf(PERSON.SCHOOL_EMAIL, PERSON.PERSONAL_EMAIL, PERSON.ORG_EMAIL)
		// The school address is proven (verified by a code, or the roster link went there).
		r.schoolEmail?.takeIf { r.emailVerifiedAt != null }?.trim()?.lowercase()?.let { school ->
			people(DSL.or(anyEmail.map { DSL.lower(it).eq(school) })).forEach { why.getOrPut(it) { mutableListOf() } += "same school email"; strong += it }
		}
		// A personal email is only typed in: weak.
		r.personalEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != r.schoolEmail?.trim()?.lowercase() }?.let { personal ->
			people(DSL.or(anyEmail.map { DSL.lower(it).eq(personal) })).forEach { why.getOrPut(it) { mutableListOf() } += "same personal email (not verified)" }
		}
		// The GitHub account they signed in with, by its ID: strong. An imported login that matches theirs: weak.
		if (r.githubId != null) {
			dsl.select(TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.eq(r.githubId))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same GitHub account"; strong += it.value1()!! }
		}
		if (r.githubLogin != null) {
			dsl.select(TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT)
				.where(TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.isNull, DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(r.githubLogin!!.lowercase()))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same GitHub login on an imported record (not verified)" }
		}
		if (r.firstName != null && r.lastName != null) {
			dsl.select(PERSON.ID).from(PERSON).join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
				.where(PERSON.DELETED_AT.isNull, CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(r.chapterId),
					DSL.lower(PERSON.FIRST_NAME).eq(r.firstName!!.trim().lowercase()), DSL.lower(PERSON.LAST_NAME).eq(r.lastName!!.trim().lowercase()))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same name in this chapter" }
		}
		r.personId?.let { why.remove(it); strong.remove(it) } // a claim is its own record
		val status = when {
			why.isEmpty() -> "new"
			strong.size == 1 && why.size == 1 -> {
				val s = dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ID.eq(strong.single())).fetchSingle().value1()
				if (s == "alumni") "alumni" else "returning"
			}
			else -> "duplicate"
		}
		return MatchResult(status, why.mapValues { it.value.distinct() })
	}
}
