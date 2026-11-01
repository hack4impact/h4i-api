package org.hack4impact.portal.adapters.github

import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool

/**
 * Changes GitHub teams (build plan step 9) as the GitHub App, which needs Organization → Members: Read and write.
 * Resources are teams, by slug. ADMIN is a team maintainer; READ and WRITE are members (repository permissions
 * come from what the team is given on each repo). Adding someone outside the org invites them; until they accept,
 * the grant is INVITED.
 */
class GitHubWriteAdapter(
	private val baseUrl: String,
	private val org: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GITHUB),
) : WriteAdapter {
	override val tool = Tool.GITHUB

	/** A closed team: visible to org members, joined only through the portal. */
	override fun create(name: String): String = try {
		http.postJson("$baseUrl/orgs/$org/teams", mapOf("name" to name, "privacy" to "closed"), headers()).body.text("slug")!!
	} catch (e: Rejected) {
		throw Rejected(tool, "${e.message} (a team called $name probably exists already: adopt it instead)")
	}

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult {
		val role = if (access == Access.ADMIN) "maintainer" else "member"
		val login = account.login ?: account.id?.let(::loginOf)
		if (login != null) {
			val state = http.putJson("$baseUrl/orgs/$org/teams/$resourceId/memberships/$login", mapOf("role" to role), headers()).body.text("state")
			return if (state == "active") GrantResult.DONE else GrantResult.INVITED
		}
		val email = account.email ?: return GrantResult.WAITING
		// No GitHub account known: invite the address to the org, straight into the team.
		val teamId = http.get("$baseUrl/orgs/$org/teams/$resourceId", headers()).body.path("id").asLong()
		http.postJson("$baseUrl/orgs/$org/invitations", mapOf("email" to email, "role" to "direct_member", "team_ids" to listOf(teamId)), headers())
		return GrantResult.INVITED
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult {
		val login = loginOf(accountId) ?: return GrantResult.DONE
		try {
			http.delete("$baseUrl/orgs/$org/teams/$resourceId/memberships/$login", headers())
		} catch (e: NotFound) {
			// Not in the team (or the account is gone): nothing to remove.
		}
		return GrantResult.DONE
	}

	override fun archive(resourceId: String) {
		try {
			http.delete("$baseUrl/orgs/$org/teams/$resourceId", headers())
		} catch (e: NotFound) {
			// Already gone.
		}
	}

	/** The login behind a numeric account ID (null if the account no longer exists). */
	private fun loginOf(accountId: String): String? = try {
		http.get("$baseUrl/user/$accountId", headers()).body.text("login")
	} catch (e: NotFound) {
		null
	}

	private fun headers() = mapOf(
		"Authorization" to "Bearer ${token()}",
		"Accept" to "application/vnd.github+json",
		"X-GitHub-Api-Version" to "2022-11-28",
	)
}
