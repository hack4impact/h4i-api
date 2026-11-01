package org.hack4impact.portal.adapters.vaultwarden

import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant

/**
 * Reads one Vaultwarden organization over its web API as the service admin: members with their status,
 * collections, and who can reach each collection directly or through a group. Collection names are
 * end-to-end encrypted, so resources are named by ID or external ID (collections the portal creates carry their
 * name as external ID). Writes: [VaultwardenWriteAdapter].
 */
class VaultwardenReadAdapter(
	private val baseUrl: String,
	private val organizationId: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.VAULTWARDEN),
) : ReadAdapter {
	override val tool = Tool.VAULTWARDEN
	private val org get() = "$baseUrl/api/organizations/$organizationId"

	override fun accounts(): List<ToolAccount> = list("$org/users").map {
		val state = when (it.path("status").asInt()) {
			0 -> AccountState.INVITED
			1 -> AccountState.ACCEPTED // accepted, waiting for an admin to confirm (share the org key)
			2 -> AccountState.ACTIVE
			else -> AccountState.SUSPENDED // -1: revoked
		}
		ToolAccount(it.text("id"), it.text("email"), it.text("email"), it.text("name"), state)
	}

	override fun resources(): List<ToolResource> =
		list("$org/collections").map { ToolResource(it.text("id")!!, it.text("externalId") ?: it.text("id")!!) }

	override fun members(resourceId: String): List<ResourceMember> {
		if (list("$org/collections").none { it.text("id") == resourceId }) throw NotFound(tool, "collection $resourceId")
		val groupAccess = list("$org/groups/details").associate { group ->
			group.text("id")!! to group.path("collections").firstOrNull { it.text("id") == resourceId }?.let(::access)
		}
		return list("$org/users?includeCollections=true&includeGroups=true").mapNotNull { member ->
			// Confirmed owners and admins reach every collection through their role (decision 107).
			if (member.path("type").isNumber && member.path("type").asInt() in setOf(0, 1) && member.path("status").asInt() == 2) {
				return@mapNotNull ResourceMember(member.text("id")!!, Access.ADMIN, implicit = true)
			}
			val direct = member.path("collections").firstOrNull { it.text("id") == resourceId }?.let(::access)
			val viaGroups = member.path("groups").mapNotNull { groupAccess[it.asString()] }
			(listOfNotNull(direct) + viaGroups).maxOrNull()?.let { ResourceMember(member.text("id")!!, it) }
		}
	}

	private fun access(entry: JsonNode) = when {
		entry.path("manage").asBoolean() -> Access.ADMIN
		entry.path("readOnly").asBoolean() || entry.path("hidePasswords").asBoolean() -> Access.READ
		else -> Access.WRITE
	}

	private fun list(url: String): List<JsonNode> =
		http.get(url, mapOf("Authorization" to "Bearer ${token()}")).body.path("data").toList()
}

/**
 * Access tokens for the service admin's personal API key (client_credentials, scope "api"). Vaultwarden
 * requires device fields on this grant; the portal identifies itself as one fixed device.
 */
class VaultwardenApiKeyToken(
	private val baseUrl: String,
	private val clientId: String,
	private val clientSecret: String,
	private val deviceId: String,
	private val http: HttpJson = HttpJson(Tool.VAULTWARDEN),
	private val clock: Clock = Clock.systemUTC(),
) : () -> String {
	private var cached: Pair<String, Instant>? = null

	@Synchronized
	override fun invoke(): String {
		cached?.let { (token, expires) -> if (clock.instant().isBefore(expires.minusSeconds(60))) return token }
		// A wrong client ID or secret is HTTP 400 invalid_client here, not 401: report it as bad credentials.
		val body = try { requestToken() } catch (e: Rejected) { throw AuthFailed(Tool.VAULTWARDEN, "API key rejected (${e.message})") }
		val token = body.text("access_token")!!
		cached = token to clock.instant().plusSeconds(body.path("expires_in").asLong(3600))
		return token
	}

	private fun requestToken() =
		http.postForm("$baseUrl/identity/connect/token", mapOf(
			"grant_type" to "client_credentials",
			"scope" to "api",
			"client_id" to clientId,
			"client_secret" to clientSecret,
			"device_identifier" to deviceId,
			"device_name" to "h4i-portal",
			"device_type" to "21",
		)).body
}
