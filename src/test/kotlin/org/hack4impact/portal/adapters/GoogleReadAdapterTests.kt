package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import org.hack4impact.portal.adapters.google.GoogleReadAdapter
import org.hack4impact.portal.resolver.Access
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleReadAdapterTests : WireMockContract() {
	private val ou = "/Portal Sandbox"

	override val sandbox = Sandbox(
		listOf(
			ToolAccount("101", "ada@hack4impact.org", "ada@hack4impact.org", "Ada Active", AccountState.ACTIVE),
			ToolAccount("102", "alan@hack4impact.org", "alan@hack4impact.org", "Alan Alumnus", AccountState.ACTIVE),
			ToolAccount("103", "gone@hack4impact.org", "gone@hack4impact.org", "Gone", AccountState.SUSPENDED),
		),
		mapOf(
			ToolResource("umd-rise-dc@hack4impact.org", "UMD RISE DC") to listOf(ResourceMember("101", Access.ADMIN, "ada@hack4impact.org"), ResourceMember("102", Access.WRITE, "alan@hack4impact.org")),
			ToolResource("umd-members@hack4impact.org", "UMD members") to listOf(
				ResourceMember("101", Access.WRITE, "ada@hack4impact.org"), ResourceMember("102", Access.WRITE, "alan@hack4impact.org"), ResourceMember("103", Access.WRITE, "gone@hack4impact.org"),
			),
		),
	)

	override fun stub(sandbox: Sandbox): GoogleReadAdapter {
		val dir = "/admin/directory/v1"
		paged("$dir/users?customer=my_customer&query=${HttpJson.encode("orgUnitPath='$ou'")}", "users", sandbox.accounts.map {
			"""{"id":"${it.externalId}","primaryEmail":"${it.email}","name":{"fullName":${str(it.name)}},"suspended":${it.state == AccountState.SUSPENDED}}"""
		})
		paged("$dir/groups?customer=my_customer", "groups", sandbox.resources.keys.map { """{"id":"g","email":"${it.externalId}","name":"${it.name}"}""" })
		for ((group, members) in sandbox.resources) {
			// A nested group is a member too; the adapter leaves it out.
			paged("$dir/groups/${HttpJson.encode(group.externalId)}/members", "members", members.map {
				"""{"id":"${it.accountId}","email":${str(it.login)},"type":"USER","role":"${if (it.access == Access.ADMIN) "MANAGER" else "MEMBER"}"}"""
			} + """{"id":"999","type":"GROUP","role":"MEMBER"}""")
		}
		return GoogleReadAdapter(server.baseUrl(), "my_customer", ou, { token }, pageSize = 2)
	}

	/** Pages of two, linked with Google's nextPageToken. */
	private fun paged(path: String, field: String, items: List<String>) {
		val first = path + (if ('?' in path) "&" else "?") + "maxResults=2"
		val pages = pagesOf(items)
		pages.forEachIndexed { i, page ->
			val url = if (i == 0) first else "$first&pageToken=t${i + 1}"
			val next = if (i < pages.lastIndex) ""","nextPageToken":"t${i + 2}"""" else ""
			json(url, """{"$field":${page.joinToString(",", "[", "]")}$next}""")
		}
	}

	@Test
	fun `a group scope hides other groups and refuses to read them`() {
		val scoped = GoogleReadAdapter(server.baseUrl(), "my_customer", ou, { token }, pageSize = 2, groupScope = Regex("rise", RegexOption.IGNORE_CASE))
		stub(sandbox)
		assertEquals(listOf("umd-rise-dc@hack4impact.org"), scoped.resources().map { it.externalId })
		assertFailsWith<Rejected> { scoped.members("umd-members@hack4impact.org") }
		assertEquals(2, scoped.members("umd-rise-dc@hack4impact.org").size)
	}

	@Test
	fun `a 403 rateLimitExceeded is RateLimited, not AuthFailed`() {
		server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(403).withBody("""{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")))
		assertFailsWith<RateLimited> { GoogleReadAdapter(server.baseUrl(), "my_customer", null, { token }).resources() }
	}
}
