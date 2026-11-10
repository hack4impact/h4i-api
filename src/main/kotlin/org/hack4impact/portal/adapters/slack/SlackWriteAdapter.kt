package org.hack4impact.portal.adapters.slack

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/**
 * Changes Slack (build plan step 9) with the bot token: creates private channels, invites and removes members.
 * Slack has no access levels in a channel, so every grant is plain membership. People join the workspace
 * themselves (approved email domain), so someone without a Slack account is waited for, not invited.
 * Needs bot scopes `groups:write` (private channels) and `channels:manage` (public ones the portal adopts).
 */
class SlackWriteAdapter(
	private val baseUrl: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.SLACK),
) : WriteAdapter {
	override val tool = Tool.SLACK

	override fun create(name: String): String =
		call("conversations.create", mapOf("name" to name, "is_private" to true)).path("channel").text("id")!!

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult {
		val user = account.id ?: return GrantResult.WAITING
		val invite = { call("conversations.invite", mapOf("channel" to resourceId, "users" to user), alreadyDone = setOf("already_in_channel")) }
		try {
			invite()
		} catch (e: Rejected) {
			// Slack lets only a member invite. The app is in the channels it created, not in an existing public one national
			// linked: it joins (scope `channels:join`) and invites again. A private channel can't be joined: someone adds the app.
			if ("not_in_channel" !in e.message.orEmpty()) throw e
			try {
				call("conversations.join", mapOf("channel" to resourceId))
			} catch (joining: AdapterException) {
				throw Rejected(tool, "conversations.invite: not_in_channel, and the portal's Slack app couldn't join the channel (${joining.message}): add the app to the channel, or give it the channels:join scope")
			}
			invite()
		}
		return GrantResult.DONE
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult {
		call("conversations.kick", mapOf("channel" to resourceId, "user" to accountId), alreadyDone = setOf("not_in_channel"))
		return GrantResult.DONE
	}

	override fun archive(resourceId: String) {
		call("conversations.archive", mapOf("channel" to resourceId), alreadyDone = setOf("already_archived"))
	}

	/** One Web API call. [alreadyDone] are error codes that mean the change is already in place (idempotency). */
	private fun call(method: String, body: Map<String, Any>, alreadyDone: Set<String> = emptySet()): JsonNode {
		val response = http.postJson("$baseUrl/$method", body, mapOf("Authorization" to "Bearer ${token()}")).body
		if (response.path("ok").asBoolean()) return response
		val code = response.text("error") ?: "unknown_error"
		if (code in alreadyDone) return response
		throw slackError(tool, method, code)
	}
}
