package org.hack4impact.portal.adapters.google

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.mail.NewAccount
import org.hack4impact.portal.resolver.Tool
import java.security.SecureRandom
import java.text.Normalizer

/**
 * Creates Google Workspace accounts for approved members and claims (build plan step 10, decisions 108 and 161): at
 * the chapter's domain, the first free of `ada@`, `ada.lovelace@`, `a.lovelace@`, then `ada2@`, `ada3@`, …, in the
 * chapter's org unit, with a temporary password to change at first sign-in. Needs the user scope
 * ([GoogleServiceAccountToken.USER_SCOPES]).
 *
 * With [orgUnitScope] set, accounts are only created in, and deleted from, org units under it (the sandbox shares
 * the real Workspace, like groups do, decision 69).
 */
class GoogleAccounts(
	private val baseUrl: String,
	private val token: () -> String,
	orgUnitScope: String? = null,
	private val http: HttpJson = HttpJson(Tool.GOOGLE),
) {
	private val orgUnitScope = orgUnitScope?.takeIf { it.isNotBlank() }?.let(::orgUnitPath)
	private val users get() = "$baseUrl/admin/directory/v1/users"
	private val random = SecureRandom()

	/** [taken]: addresses the portal still holds for someone (a deleted account's old address isn't reused, second review H4). */
	fun create(firstName: String, lastName: String, domain: String, orgUnit: String, recoveryEmail: String?, taken: (String) -> Boolean = { false }): NewAccount =
		createAt(freeAddress(candidates(firstName, lastName), domain.lowercase(), taken), firstName, lastName, orgUnit, recoveryEmail)

	/** An account at exactly [email] (the staging demo's persona accounts), inside the org-unit scope. */
	fun createAt(email: String, firstName: String, lastName: String, orgUnit: String, recoveryEmail: String?): NewAccount {
		val orgUnit = orgUnitPath(orgUnit)
		inScope(orgUnit)
		val password = (1..16).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)] }.joinToString("")
		val body = buildMap {
			put("primaryEmail", email)
			put("name", mapOf("givenName" to firstName, "familyName" to lastName))
			put("password", password)
			put("changePasswordAtNextLogin", true)
			put("orgUnitPath", orgUnit)
			recoveryEmail?.let { put("recoveryEmail", it) }
		}
		val id = http.postJson(users, body, headers()).body.text("id")!!
		return NewAccount(id, email, password)
	}

	/** Whether an account or alias already uses [email]. */
	fun exists(email: String): Boolean = try { http.get("$users/${HttpJson.encode(email)}", headers()); true } catch (e: NotFound) { false }

	/** Deletes an account, only inside the org-unit scope (the sandbox suite's cleanup). Gone already counts as done. */
	fun delete(userKey: String) {
		val user = try { http.get("$users/${HttpJson.encode(userKey)}", headers()).body } catch (e: NotFound) { return }
		inScope(user.text("orgUnitPath") ?: "/")
		http.delete("$users/${HttpJson.encode(userKey)}", headers())
	}

	/** Addresses of the accounts inside the org-unit scope that start with [prefix]: what earlier suite runs left. */
	fun accountsInScope(prefix: String): List<String> {
		val scope = orgUnitScope ?: return emptyList()
		val query = HttpJson.encode("orgUnitPath='$scope'")
		return http.get("$users?customer=my_customer&maxResults=500&query=$query", headers()).body.path("users").toList()
			.mapNotNull { it.text("primaryEmail") }.filter { it.startsWith(prefix) }
	}

	/** The first of [locals] at [domain] that no account or alias uses and the portal doesn't hold for anyone. */
	private fun freeAddress(locals: List<String>, domain: String, taken: (String) -> Boolean): String {
		for (local in locals) {
			val candidate = "$local@$domain"
			if (taken(candidate)) continue
			try {
				http.get("$users/${HttpJson.encode(candidate)}", headers())
			} catch (e: NotFound) {
				return candidate
			}
		}
		throw Rejected(Tool.GOOGLE, "no free address for ${locals.first()}@$domain")
	}

	private fun inScope(orgUnit: String) {
		if (orgUnitScope != null && orgUnit != orgUnitScope && !orgUnit.startsWith("$orgUnitScope/")) {
			throw Rejected(Tool.GOOGLE, "org unit $orgUnit is outside portal.adapters.google.user-scope")
		}
	}

	private fun headers() = mapOf("Authorization" to "Bearer ${token()}")

	companion object {
		private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

		/** The first name as an address: letters and digits only, accents removed ("Zoë-Ann" → "zoeann"). */
		fun localPart(firstName: String): String = clean(firstName).ifEmpty { "member" }

		/**
		 * The addresses to try, in order (decision 161): first name, first.last, first initial.last, then the first name
		 * numbered from 2 ("ada", "ada.lovelace", "a.lovelace", "ada2", …, "ada99"). Without a usable last name, only the
		 * first name and its numbers.
		 */
		fun candidates(firstName: String, lastName: String): List<String> {
			val first = localPart(firstName)
			val last = clean(lastName)
			val dotted = if (last.isEmpty()) emptyList() else listOf("$first.$last", "${first.first()}.$last")
			return (listOf(first) + dotted + (2..99).map { "$first$it" }).distinct()
		}

		private fun clean(name: String) =
			Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

		/** An org unit as Google wants it: a leading slash, no trailing one ("Sandbox/" → "/Sandbox"). */
		fun orgUnitPath(value: String): String = "/" + value.trim().trim('/')
	}
}
