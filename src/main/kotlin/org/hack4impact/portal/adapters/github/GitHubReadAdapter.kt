package org.hack4impact.portal.adapters.github

import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/**
 * Reads one GitHub organization: members (numeric IDs) and pending invitations, teams, and team members
 * (maintainers count as ADMIN). Authenticates as a GitHub App installation (wiki: GitHub).
 */
class GitHubReadAdapter(
	private val baseUrl: String,
	private val org: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GITHUB),
	private val pageSize: Int = 100,
) : ReadAdapter {
	override val tool = Tool.GITHUB

	override fun accounts(): List<ToolAccount> =
		pages("/orgs/$org/members").map { ToolAccount(it.text("id"), it.text("login"), null, null, AccountState.ACTIVE) } +
			pages("/orgs/$org/invitations").map { ToolAccount(null, it.text("login"), it.text("email"), null, AccountState.INVITED) }

	override fun resources(): List<ToolResource> =
		pages("/orgs/$org/teams").map { ToolResource(it.text("slug")!!, it.text("name") ?: it.text("slug")!!) }

	override fun members(resourceId: String): List<ResourceMember> {
		val maintainers = pages("/orgs/$org/teams/$resourceId/members?role=maintainer").mapNotNull { it.text("id") }.toSet()
		return pages("/orgs/$org/teams/$resourceId/members?role=all").filter { it.text("id") != null }
			.map { val id = it.text("id")!!; ResourceMember(id, if (id in maintainers) Access.ADMIN else Access.WRITE, it.text("login")) }
	}

	/** Follows GitHub's Link header until there's no next page. */
	private fun pages(path: String): List<JsonNode> {
		val items = mutableListOf<JsonNode>()
		var url: String? = baseUrl + path + (if ('?' in path) "&" else "?") + "per_page=$pageSize"
		while (url != null) {
			val response = http.get(url, headers())
			response.body.forEach { items.add(it) }
			url = nextLink(response.headers.firstValue("link").orElse(null))
		}
		return items
	}

	private fun headers() = mapOf(
		"Authorization" to "Bearer ${token()}",
		"Accept" to "application/vnd.github+json",
		"X-GitHub-Api-Version" to "2022-11-28",
	)

	private fun nextLink(link: String?): String? =
		link?.split(",")?.firstOrNull { it.contains("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')
}
