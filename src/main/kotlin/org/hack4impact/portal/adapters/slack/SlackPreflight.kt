package org.hack4impact.portal.adapters.slack

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.preflight.Check
import org.hack4impact.portal.preflight.ToolPreflight
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Component

/**
 * Preflight for Slack (stage 2, package 8's plan): the bot token's scopes, from `auth.test`'s `x-oauth-scopes` header,
 * without trying a write. Reading needs channels:read, groups:read, users:read and users:read.email; changing (with the
 * write switch) channels:manage and groups:write as well.
 */
@Component
@ConditionalOnBooleanProperty("portal.adapters.slack.enabled")
class SlackPreflight(private val adapters: AdapterProperties, private val http: HttpJson = HttpJson(Tool.SLACK)) : ToolPreflight {
	companion object {
		val READ = listOf("channels:read", "groups:read", "users:read", "users:read.email")
		val WRITE = listOf("channels:manage", "groups:write")
	}

	override val tool = Tool.SLACK

	override fun checks(): List<Check> {
		val name = "Slack permissions"
		val slack = adapters.slack
		val response = http.get("${slack.baseUrl.trimEnd('/')}/auth.test", mapOf("Authorization" to "Bearer ${slack.botToken}"))
		if (!response.body.path("ok").asBoolean()) return listOf(Check("Sync", name, Check.Level.FAIL, "auth.test: ${response.body.text("error") ?: "not ok"}"))
		val scopes = response.headers.firstValue("x-oauth-scopes").orElse("").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
		val team = response.body.text("team") ?: "the workspace"
		val noRead = READ - scopes
		val noWrite = WRITE - scopes
		return listOf(
			when {
				noRead.isNotEmpty() -> Check("Sync", name, Check.Level.FAIL, "the bot in $team lacks ${noRead.joinToString()}: add them to the Slack app and reinstall it")
				noWrite.isEmpty() -> Check("Sync", name, Check.Level.OK, "the bot in $team has ${(READ + WRITE).joinToString()}")
				slack.write -> Check("Sync", name, Check.Level.FAIL, "writes are on but the bot in $team lacks ${noWrite.joinToString()}")
				else -> Check("Sync", name, Check.Level.WARN, "the bot in $team lacks ${noWrite.joinToString()} (writes are off)")
			},
		)
	}
}
