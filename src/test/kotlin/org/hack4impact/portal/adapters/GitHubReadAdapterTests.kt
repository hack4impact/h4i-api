package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import org.hack4impact.portal.adapters.github.GitHubReadAdapter
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReadAdapterTests : WireMockContract() {
	private val org = "h4i-portal-sandbox"

	override val sandbox = Sandbox(
		listOf(
			ToolAccount("1001", "ada", null, null, AccountState.ACTIVE),
			ToolAccount("1002", "alan", null, null, AccountState.ACTIVE),
			ToolAccount("1003", "grace", null, null, AccountState.ACTIVE),
			ToolAccount(null, "hal", null, null, AccountState.INVITED),
			ToolAccount(null, null, "new@example.org", null, AccountState.INVITED),
		),
		mapOf(
			ToolResource("umd-rise-dc", "umd-rise-dc") to listOf(
				ResourceMember("1001", Access.ADMIN, "ada"), ResourceMember("1002", Access.WRITE, "alan"), ResourceMember("1003", Access.WRITE, "grace"),
			),
			ToolResource("umd-rise-dc-leads", "UMD RISE DC leads") to listOf(ResourceMember("1001", Access.ADMIN, "ada")),
		),
	)

	override fun stub(sandbox: Sandbox): GitHubReadAdapter {
		paged("/orgs/$org/members", sandbox.accounts.filter { it.externalId != null }.map { """{"login":${str(it.login)},"id":${it.externalId}}""" })
		paged("/orgs/$org/invitations", sandbox.accounts.filter { it.externalId == null }.map { """{"id":9,"login":${str(it.login)},"email":${str(it.email)}}""" })
		paged("/orgs/$org/teams", sandbox.resources.keys.map { """{"id":1,"slug":"${it.externalId}","name":"${it.name}"}""" })
		for ((team, members) in sandbox.resources) {
			paged("/orgs/$org/teams/${team.externalId}/members?role=all", members.map { """{"login":${str(it.login)},"id":${it.accountId}}""" })
			paged("/orgs/$org/teams/${team.externalId}/members?role=maintainer", members.filter { it.access == Access.ADMIN }.map { """{"login":"x","id":${it.accountId}}""" })
		}
		return GitHubReadAdapter(server.baseUrl(), org, { token }, pageSize = 2)
	}

	/** Pages of two, linked with GitHub's Link header. */
	private fun paged(path: String, items: List<String>) {
		val first = path + (if ('?' in path) "&" else "?") + "per_page=2"
		val pages = pagesOf(items)
		pages.forEachIndexed { i, page ->
			val url = if (i == 0) first else "$first&page=${i + 1}"
			val next = if (i < pages.lastIndex) mapOf("Link" to "<${server.baseUrl()}$first&page=${i + 2}>; rel=\"next\", <${server.baseUrl()}$first&page=${pages.size}>; rel=\"last\"") else emptyMap()
			json(url, page.joinToString(",", "[", "]"), next)
		}
	}

	@Test
	fun `an exhausted rate limit (403 with remaining 0) waits until the reset time`() {
		val now = Instant.parse("2026-10-09T12:00:00Z")
		server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(403).withHeader("x-ratelimit-remaining", "0").withHeader("x-ratelimit-reset", "${now.epochSecond + 120}")))
		val adapter = GitHubReadAdapter(server.baseUrl(), org, { token }, HttpJson(Tool.GITHUB, Clock.fixed(now, ZoneOffset.UTC)), pageSize = 2)
		assertEquals(Duration.ofSeconds(120), assertFailsWith<RateLimited> { adapter.accounts() }.retryAfter)
	}
}
