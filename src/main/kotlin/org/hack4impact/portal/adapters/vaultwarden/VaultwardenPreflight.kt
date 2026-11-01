package org.hack4impact.portal.adapters.vaultwarden

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.adapters.vaultwardenHttp
import org.hack4impact.portal.preflight.Check
import org.hack4impact.portal.preflight.ToolPreflight
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Component

/**
 * Preflight for Vaultwarden (stage 2, package 8's plan): the service admin's API key logs in and the account is a
 * confirmed owner or admin of the organization (needed to list members and manage collections), and its master
 * password unlocks the organization key (needed to name collections and confirm members), and the server is a version
 * the adapters were checked against. Nothing is changed.
 */
@Component
@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.enabled")
class VaultwardenPreflight(private val adapters: AdapterProperties) : ToolPreflight {
	override val tool = Tool.VAULTWARDEN

	override fun checks(): List<Check> {
		val v = adapters.vaultwarden
		val url = v.baseUrl.trimEnd('/')
		val http = vaultwardenHttp(v)
		val token = VaultwardenApiKeyToken(url, v.clientId, v.clientSecret, v.deviceId, http)
		return listOf(version(url, http), role(v, url, token, http), organizationKey(v, url, token, http))
	}

	/**
	 * Checked live on 2026-11-01: 1.37.4 works end to end; 1.34.3 silently drops the collection access sent with an
	 * invite or a collection update, so members never reach their collection. Versions in between weren't tried.
	 */
	private fun version(url: String, http: HttpJson): Check {
		val name = "Vaultwarden version"
		val version = http.get("$url/api/version").body.asString()
		val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
		val recent = (parts.getOrElse(0) { 0 } to parts.getOrElse(1) { 0 }).let { (major, minor) -> major > 1 || (major == 1 && minor >= 37) }
		return if (recent) Check("Sync", name, Check.Level.OK, version)
		else Check("Sync", name, Check.Level.WARN, "$version: the adapters were checked on 1.37.4; 1.34.3 drops collection access on invites and collection updates. Upgrade before turning writes on")
	}

	private fun role(v: AdapterProperties.Vaultwarden, url: String, token: () -> String, http: HttpJson): Check {
		val name = "Vaultwarden service admin"
		val profile = http.get("$url/api/accounts/profile", mapOf("Authorization" to "Bearer ${token()}")).body
		val entry = profile.path("organizations").firstOrNull { it.text("id") == v.organizationId }
			?: return Check("Sync", name, Check.Level.FAIL, "the API key logs in as ${profile.text("email")}, who isn't in organization ${v.organizationId}")
		val type = entry.path("type").asInt(-1)
		return when {
			entry.path("status").asInt() != 2 -> Check("Sync", name, Check.Level.FAIL, "${profile.text("email")} hasn't been confirmed in the organization")
			type == 0 || type == 1 -> Check("Sync", name, Check.Level.OK, "${profile.text("email")} is the organization's ${if (type == 0) "owner" else "admin"}")
			else -> Check("Sync", name, Check.Level.FAIL, "${profile.text("email")} must be an owner or admin of the organization to list members and manage collections")
		}
	}

	private fun organizationKey(v: AdapterProperties.Vaultwarden, url: String, token: () -> String, http: HttpJson): Check {
		val name = "Vaultwarden organization key"
		if (v.masterPassword.isBlank()) {
			return if (v.write) Check("Sync", name, Check.Level.FAIL, "writes are on but PORTAL_ADAPTERS_VAULTWARDEN_MASTERPASSWORD isn't set")
			else Check("Sync", name, Check.Level.WARN, "not checked: no master password (writes are off)")
		}
		return try {
			VaultwardenWriteAdapter(url, v.organizationId, token, v.masterPassword, http).unlock()
			Check("Sync", name, Check.Level.OK, "the master password unlocks the organization key")
		} catch (e: AdapterException) {
			Check("Sync", name, if (v.write) Check.Level.FAIL else Check.Level.WARN, e.message ?: "can't unlock the organization key")
		}
	}
}
