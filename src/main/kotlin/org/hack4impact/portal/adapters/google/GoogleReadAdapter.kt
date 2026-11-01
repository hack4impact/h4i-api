package org.hack4impact.portal.adapters.google

import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/**
 * Reads Google Workspace through the Directory API: users (optionally limited to one org unit, e.g. the
 * sandbox OU), groups, and group members. Owners and managers count as ADMIN; nested groups are left out.
 */
class GoogleReadAdapter(
	private val baseUrl: String,
	private val customer: String,
	private val orgUnitPath: String?,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GOOGLE),
	private val pageSize: Int = 200,
	/**
	 * Only groups whose email or name matches are listed or read (wiki decision 61). Groups aren't scoped by org
	 * unit, so without this a sandbox setup would read every real group in the domain. Null = every group.
	 */
	private val groupScope: Regex? = null,
) : ReadAdapter {
	override val tool = Tool.GOOGLE
	private val directory get() = "$baseUrl/admin/directory/v1"

	override fun accounts(): List<ToolAccount> {
		val ou = orgUnitPath?.takeIf { it.isNotBlank() }?.let { "&query=" + HttpJson.encode("orgUnitPath='$it'") } ?: ""
		return pages("$directory/users?customer=${HttpJson.encode(customer)}$ou", "users").map {
			ToolAccount(
				it.text("id"), it.text("primaryEmail"), it.text("primaryEmail"), it.path("name").text("fullName"),
				if (it.path("suspended").asBoolean()) AccountState.SUSPENDED else AccountState.ACTIVE,
			)
		}
	}

	override fun resources(): List<ToolResource> =
		pages("$directory/groups?customer=${HttpJson.encode(customer)}", "groups")
			.map { ToolResource(it.text("email")!!, it.text("name") ?: it.text("email")!!) }
			.filter { inScope(it.externalId, it.name) }

	override fun members(resourceId: String): List<ResourceMember> {
		if (!inScope(resourceId, null)) throw Rejected(tool, "group $resourceId is outside portal.adapters.google.group-scope")
		return pages("$directory/groups/${HttpJson.encode(resourceId)}/members", "members")
			.filter { it.text("type") == "USER" }
			.map { ResourceMember(it.text("id")!!, if (it.text("role") in setOf("OWNER", "MANAGER")) Access.ADMIN else Access.WRITE, it.text("email")) }
	}

	private fun inScope(email: String, name: String?) = groupScope == null || groupScope.containsMatchIn(email) || (name != null && groupScope.containsMatchIn(name))

	private fun pages(url: String, field: String): List<JsonNode> {
		val items = mutableListOf<JsonNode>()
		var pageToken = ""
		do {
			val body = http.get(
				"$url${if ('?' in url) "&" else "?"}maxResults=$pageSize" + (if (pageToken.isEmpty()) "" else "&pageToken=${HttpJson.encode(pageToken)}"),
				mapOf("Authorization" to "Bearer ${token()}"),
			).body
			body.path(field).forEach { items.add(it) }
			pageToken = body.text("nextPageToken").orEmpty()
		} while (pageToken.isNotEmpty())
		return items
	}
}
