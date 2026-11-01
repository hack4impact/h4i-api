package org.hack4impact.portal.adapters.vaultwarden

import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/**
 * Changes Vaultwarden collections (build plan step 9, wiki decision 101) over the web API as the service admin.
 * Resources are collections, by ID; each member's access is set on the collection itself (READ = read only,
 * WRITE = can edit, ADMIN = can manage). Someone not in the organization is invited by email straight into the
 * collection; once they accept, [confirmPending] confirms them (shares the organization key), after which they
 * see the collection's items.
 *
 * Collection names and confirmation need the organization key, unlocked with the service admin's master password
 * ([VaultwardenCrypto]); it's only unlocked when one of those is needed.
 */
class VaultwardenWriteAdapter(
	private val baseUrl: String,
	private val organizationId: String,
	private val token: () -> String,
	private val masterPassword: String,
	private val http: HttpJson = HttpJson(Tool.VAULTWARDEN),
) : WriteAdapter {
	override val tool = Tool.VAULTWARDEN
	private val org get() = "$baseUrl/api/organizations/$organizationId"
	private val orgKey by lazy { unlock() }

	override fun create(name: String): String {
		val body = mapOf("name" to VaultwardenCrypto.encryptString(name, orgKey), "externalId" to name, "groups" to emptyList<Any>(), "users" to emptyList<Any>())
		return http.postJson("$org/collections", body, headers()).body.text("id")!!
	}

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult {
		val member = account.id
		if (member == null) {
			val email = account.email ?: return GrantResult.WAITING
			val invite = mapOf(
				"emails" to listOf(email), "groups" to emptyList<String>(), "type" to 2, // a plain user
				"collections" to listOf(entry(resourceId, access)), "permissions" to emptyMap<String, Any>(),
			)
			http.postJson("$org/users/invite", invite, headers())
			return GrantResult.INVITED
		}
		updateUsers(resourceId) { users -> users.filter { it["id"] != member } + entry(member, access) }
		return GrantResult.DONE
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult {
		updateUsers(resourceId) { users -> users.filter { it["id"] != accountId } }
		return GrantResult.DONE
	}

	override fun archive(resourceId: String) {
		try {
			http.delete("$org/collections/$resourceId", headers())
		} catch (e: NotFound) {
			// Already gone.
		}
	}

	/** Confirms members who accepted an invitation and can reach a portal-managed collection. Returns how many. */
	override fun confirmPending(managedResourceIds: Set<String>): Int {
		val accepted = http.get("$org/users?includeCollections=true", headers()).body.path("data")
			.filter { it.path("status").asInt() == 1 && it.path("collections").any { c -> c.text("id") in managedResourceIds } }
		for (member in accepted) {
			val publicKey = http.get("$baseUrl/api/users/${member.text("userId")}/public-key", headers()).body.text("publicKey")!!
			val key = VaultwardenCrypto.rsaEncrypt(orgKey.bytes, VaultwardenCrypto.publicKey(publicKey))
			http.postJson("$org/users/${member.text("id")}/confirm", mapOf("key" to key), headers())
		}
		return accepted.size
	}

	/**
	 * Replaces a collection's member list with [change] applied to it. The update takes the whole collection, so its
	 * encrypted name, external ID and groups are sent back as they are.
	 */
	private fun updateUsers(collection: String, change: (List<Map<String, Any?>>) -> List<Map<String, Any?>>) {
		val details = http.get("$org/collections/$collection/details", headers()).body
		val users = http.get("$org/collections/$collection/users", headers()).body.let { if (it.has("data")) it.path("data") else it }.toList().map(::entryOf)
		val body = mapOf(
			"name" to details.text("name"), "externalId" to details.text("externalId"),
			"groups" to details.path("groups").toList().map(::entryOf), "users" to change(users),
		)
		http.putJson("$org/collections/$collection", body, headers())
	}

	private fun entry(id: String, access: Access) =
		mapOf("id" to id, "readOnly" to (access == Access.READ), "hidePasswords" to false, "manage" to (access == Access.ADMIN))

	private fun entryOf(node: JsonNode): Map<String, Any?> = mapOf(
		"id" to node.text("id"), "readOnly" to node.path("readOnly").asBoolean(),
		"hidePasswords" to node.path("hidePasswords").asBoolean(), "manage" to node.path("manage").asBoolean(),
	)

	/** The organization key, from the service admin's account (wiki decision 101). Internal for preflight's check. */
	internal fun unlock(): SymmetricKey {
		val email = http.get("$baseUrl/api/accounts/profile", headers()).body.text("email")!!
		val prelogin = http.postJson("$baseUrl/identity/accounts/prelogin", mapOf("email" to email)).body
		if (prelogin.path("kdf").asInt() != 0) throw AuthFailed(tool, "the service admin's account uses Argon2; the portal supports PBKDF2 accounts only")
		val profile = http.get("$baseUrl/api/sync?excludeDomains=true", headers()).body.path("profile")
		val orgEntry = profile.path("organizations").firstOrNull { it.text("id") == organizationId }
			?: throw AuthFailed(tool, "the service admin isn't in organization $organizationId")
		return try {
			VaultwardenCrypto.organizationKey(
				masterPassword, email, prelogin.path("kdfIterations").asInt(),
				profile.text("key")!!, profile.text("privateKey")!!, orgEntry.text("key")!!,
			)
		} catch (e: IllegalStateException) {
			throw AuthFailed(tool, "couldn't unlock the organization key: ${e.message}")
		}
	}

	private fun headers() = mapOf("Authorization" to "Bearer ${token()}")
}
