package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.hack4impact.portal.adapters.slack.SlackReadAdapter
import org.hack4impact.portal.resolver.Access
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlackReadAdapterTests : WireMockContract() {
	override val sandbox = Sandbox(
		listOf(
			ToolAccount("U01", "ada", "ada@hack4impact.org", "Ada Active", AccountState.ACTIVE),
			ToolAccount("U02", "alan", "alan@hack4impact.org", "Alan Alumnus", AccountState.ACTIVE),
			ToolAccount("U03", "gone", "gone@hack4impact.org", "Gone", AccountState.SUSPENDED),
		),
		mapOf(
			ToolResource("C01", "umd-rise-dc") to listOf(ResourceMember("U01", Access.WRITE), ResourceMember("U02", Access.WRITE), ResourceMember("U03", Access.WRITE)),
			ToolResource("C02", "umd-old-project", archived = true) to listOf(ResourceMember("U02", Access.WRITE)),
		),
	)

	override fun stub(sandbox: Sandbox): SlackReadAdapter {
		// A bot and Slackbot are in every workspace; the adapter leaves them out.
		val users = sandbox.accounts.map {
			"""{"id":"${it.externalId}","name":${str(it.login)},"deleted":${it.state == AccountState.SUSPENDED},"is_bot":false,"profile":{"email":${str(it.email)},"real_name":${str(it.name)}}}"""
		} + """{"id":"B01","name":"portal","deleted":false,"is_bot":true,"profile":{}}""" + """{"id":"USLACKBOT","name":"slackbot","deleted":false,"is_bot":false,"profile":{}}"""
		paged("/users.list", "members", users)
		paged("/conversations.list?types=public_channel,private_channel&exclude_archived=false", "channels",
			sandbox.resources.keys.map { """{"id":"${it.externalId}","name":"${it.name}","is_archived":${it.archived},"is_private":true}""" })
		for ((channel, members) in sandbox.resources) {
			paged("/conversations.members?channel=${channel.externalId}", "members", members.map { "\"${it.accountId}\"" })
		}
		// Slack answers an unknown channel with HTTP 200 and ok=false.
		server.stubFor(authed(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/conversations.members"))).atPriority(10)
			.willReturn(aResponse().withBody("""{"ok":false,"error":"channel_not_found"}""")))
		return SlackReadAdapter(server.baseUrl(), { token }, pageSize = 2)
	}

	/** Pages of two, linked with Slack's next_cursor. */
	private fun paged(path: String, field: String, items: List<String>) {
		val first = path + (if ('?' in path) "&" else "?") + "limit=2"
		val pages = pagesOf(items)
		pages.forEachIndexed { i, page ->
			val url = if (i == 0) first else "$first&cursor=page${i + 1}"
			val next = if (i < pages.lastIndex) "page${i + 2}" else ""
			json(url, """{"ok":true,"$field":${page.joinToString(",", "[", "]")},"response_metadata":{"next_cursor":"$next"}}""")
		}
	}

	override fun stubFailure(failure: Failure) {
		if (failure == Failure.BAD_CREDENTIALS) {
			server.stubFor(any(anyUrl()).atPriority(1).willReturn(aResponse().withBody("""{"ok":false,"error":"invalid_auth"}""")))
		} else {
			super.stubFailure(failure)
		}
	}

	@Test
	fun `a ratelimited error body is RateLimited`() {
		server.stubFor(any(anyUrl()).willReturn(aResponse().withBody("""{"ok":false,"error":"ratelimited"}""")))
		assertEquals(Duration.ofSeconds(60), assertFailsWith<RateLimited> { SlackReadAdapter(server.baseUrl(), { token }).accounts() }.retryAfter)
	}
}
