package org.hack4impact.portal.sync

import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import java.util.UUID

enum class MatchedBy { ID, LOGIN, EMAIL }

data class PersonMatch(val personId: UUID, val by: MatchedBy)

/**
 * Finds the portal person behind a tool account: first by the account ID the portal has on file, then by
 * login, then by email. A login or email shared by two people matches nobody, so one person's access is
 * never attributed to another (as with the importer, nothing is merged automatically).
 */
class AccountMatcher(
	private val byId: Map<String, UUID>,
	private val byLogin: Map<String, Set<UUID>>,
	private val byEmail: Map<String, Set<UUID>>,
) {
	fun match(accountId: String, login: String?, email: String?): PersonMatch? {
		byId[accountId]?.let { return PersonMatch(it, MatchedBy.ID) }
		// Memberships the portal records itself (the admin queue) name the person directly.
		if (accountId.startsWith(PERSON_PREFIX)) return runCatching { UUID.fromString(accountId.removePrefix(PERSON_PREFIX)) }.getOrNull()?.let { PersonMatch(it, MatchedBy.ID) }
		login?.lowercase()?.let { byLogin[it] }?.singleOrNull()?.let { return PersonMatch(it, MatchedBy.LOGIN) }
		val emails = listOfNotNull(email, login?.takeIf { '@' in it }).map { it.lowercase() }.distinct()
		return emails.firstNotNullOfOrNull { byEmail[it]?.singleOrNull() }?.let { PersonMatch(it, MatchedBy.EMAIL) }
	}

	companion object {
		/** Account IDs of the form `person:<uuid>` are portal people (queue-based tools such as Notion). */
		const val PERSON_PREFIX = "person:"

		fun load(dsl: DSLContext, tool: Tool): AccountMatcher {
			val name = tool.name.lowercase()
			val accounts = dsl.select(TOOL_ACCOUNT.TOOL, TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.EXTERNAL_LOGIN, TOOL_ACCOUNT.PERSON_ID)
				.from(TOOL_ACCOUNT).join(PERSON).on(PERSON.ID.eq(TOOL_ACCOUNT.PERSON_ID))
				.where(PERSON.DELETED_AT.isNull, TOOL_ACCOUNT.STATE.ne("removed"))
				.fetch()
			val ofTool = accounts.filter { it.value1() == name }
			val byId = ofTool.filter { it.value2() != null }.associate { it.value2()!! to it.value4()!! }
			val byLogin = ofTool.filter { it.value3() != null }.groupBy({ it.value3()!!.lowercase() }, { it.value4()!! }).mapValues { it.value.toSet() }
			// Emails: the person's own addresses, plus Google logins (which are addresses).
			val personEmails = dsl.select(PERSON.ID, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL, PERSON.PERSONAL_EMAIL).from(PERSON).where(PERSON.DELETED_AT.isNull)
				.fetch().flatMap { r -> listOfNotNull(r.value2(), r.value3(), r.value4()).map { it to r.value1()!! } }
			val googleLogins = accounts.filter { it.value1() == "google" && it.value3()?.contains('@') == true }.map { it.value3()!! to it.value4()!! }
			val byEmail = (personEmails + googleLogins).groupBy({ it.first.trim().lowercase() }, { it.second }).mapValues { it.value.toSet() }
			return AccountMatcher(byId, byLogin, byEmail)
		}
	}
}
