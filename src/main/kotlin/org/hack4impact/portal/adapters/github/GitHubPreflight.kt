package org.hack4impact.portal.adapters.github

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.preflight.Check
import org.hack4impact.portal.preflight.ToolPreflight
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Component
import java.io.File

/**
 * Preflight for GitHub (stage 2, package 8's plan): the App installation's Members permission, from the permissions
 * GitHub lists when it issues an installation token. Reading needs Members: read; changing (with the write switch)
 * Members: read and write. Repository permissions aren't needed (repositories are stage 4).
 */
@Component
@ConditionalOnBooleanProperty("portal.adapters.github.enabled")
class GitHubPreflight(private val adapters: AdapterProperties, private val http: HttpJson = HttpJson(Tool.GITHUB)) : ToolPreflight {
	override val tool = Tool.GITHUB

	override fun checks(): List<Check> {
		val name = "GitHub permissions"
		val g = adapters.github
		val key = g.privateKey.trim().let { if (it.startsWith("-----")) it else File(it).readText() }
		val jwt = GitHubAppToken(g.baseUrl, g.appId, g.installationId, key, http).jwt()
		val body = http.post("${g.baseUrl}/app/installations/${g.installationId}/access_tokens", mapOf("Authorization" to "Bearer $jwt", "Accept" to "application/vnd.github+json")).body
		val members = body.path("permissions").text("members")
		return listOf(
			when {
				members == null -> Check("Sync", name, Check.Level.FAIL, "the App installation has no Members permission: add Members (read and write) to the App and accept it in the org")
				members == "write" -> Check("Sync", name, Check.Level.OK, "the App installation has Members: read and write")
				g.write -> Check("Sync", name, Check.Level.FAIL, "writes are on but the App installation has Members: $members, not write")
				else -> Check("Sync", name, Check.Level.WARN, "the App installation has Members: $members (writes are off)")
			},
		)
	}
}
