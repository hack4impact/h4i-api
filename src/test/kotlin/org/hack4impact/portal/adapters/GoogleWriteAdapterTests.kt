package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.google.GoogleWriteAdapter
import org.hack4impact.portal.resolver.Access
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoogleWriteAdapterTests {
	private val server = WireMockServer(options().dynamicPort())
	private val dir = "/admin/directory/v1"
	private val group = "sandbox-rise-dc@hack4impact.org"
	private val encoded = "sandbox-rise-dc%40hack4impact.org"
	private val slept = mutableListOf<java.time.Duration>()
	private val adapter by lazy {
		GoogleWriteAdapter(server.baseUrl(), { "ya29.test" }, groupScope = Regex("sandbox", RegexOption.IGNORE_CASE), settleTries = 2, sleep = { slept += it })
	}

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() { server.resetAll(); slept.clear() }

	private fun json(status: Int, body: String) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

	@Test
	fun `creates a group in scope, and explains one that exists`() {
		server.stubFor(post(urlEqualTo("$dir/groups")).withHeader("Authorization", equalTo("Bearer ya29.test")).willReturn(json(200, """{"email":"$group"}""")))
		assertEquals(group, adapter.create(group))
		server.verify(postRequestedFor(urlEqualTo("$dir/groups"))
			.withRequestBody(equalToJson("""{"email":"$group","name":"sandbox-rise-dc","description":"Managed by the H4I portal"}""")))
		server.stubFor(post(urlEqualTo("$dir/groups")).willReturn(json(409, """{"error":{"code":409,"message":"Entity already exists."}}""")))
		assertContains(assertFailsWith<Rejected> { adapter.create(group) }.message!!, "adopt it instead")
	}

	@Test
	fun `never touches a group outside the scope`() {
		assertFailsWith<Rejected> { adapter.create("umd-rise-dc@hack4impact.org") }
		assertFailsWith<Rejected> { adapter.grant("board@hack4impact.org", AccountRef("1", email = "a@hack4impact.org"), Access.WRITE) }
		assertFailsWith<Rejected> { adapter.revoke("board@hack4impact.org", "1") }
		assertEquals(0, server.allServeEvents.size)
	}

	@Test
	fun `adds a member by address as member or manager, and fixes the role of someone already in it`() {
		server.stubFor(post(urlEqualTo("$dir/groups/$encoded/members")).willReturn(json(200, """{"email":"ada@hack4impact.org"}""")))
		assertEquals(GrantResult.DONE, adapter.grant(group, AccountRef("101", email = "ada@hack4impact.org"), Access.ADMIN))
		server.verify(postRequestedFor(urlEqualTo("$dir/groups/$encoded/members")).withRequestBody(equalToJson("""{"email":"ada@hack4impact.org","role":"MANAGER"}""")))

		server.stubFor(post(urlEqualTo("$dir/groups/$encoded/members")).willReturn(json(409, """{"error":{"code":409,"message":"Member already exists."}}""")))
		server.stubFor(put(urlEqualTo("$dir/groups/$encoded/members/ada%40hack4impact.org")).willReturn(json(200, """{"role":"MEMBER"}""")))
		assertEquals(GrantResult.DONE, adapter.grant(group, AccountRef("101", email = "ada@hack4impact.org"), Access.WRITE))
		server.verify(putRequestedFor(urlEqualTo("$dir/groups/$encoded/members/ada%40hack4impact.org")).withRequestBody(equalToJson("""{"role":"MEMBER"}""")))

		assertEquals(GrantResult.WAITING, adapter.grant(group, AccountRef(null), Access.WRITE))
	}

	@Test
	fun `removes a member, and someone already gone counts as done`() {
		server.stubFor(delete(urlEqualTo("$dir/groups/$encoded/members/101")).willReturn(aResponse().withStatus(204)))
		adapter.revoke(group, "101")
		server.verify(deleteRequestedFor(urlEqualTo("$dir/groups/$encoded/members/101")))
		server.stubFor(delete(urlEqualTo("$dir/groups/$encoded/members/101")).willReturn(json(404, """{"error":{"code":404,"message":"Resource Not Found: memberKey"}}""")))
		adapter.revoke(group, "101")
	}

	@Test
	fun `a new group that isn't ready yet is waited for, then reported as temporary`() {
		server.stubFor(post(urlEqualTo("$dir/groups/$encoded/members")).inScenario("new").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
			.willReturn(json(404, """{"error":{"code":404,"message":"Resource Not Found: groupKey"}}""")).willSetStateTo("ready"))
		server.stubFor(post(urlEqualTo("$dir/groups/$encoded/members")).inScenario("new").whenScenarioStateIs("ready").willReturn(json(200, """{"email":"ada@hack4impact.org"}""")))
		assertEquals(GrantResult.DONE, adapter.grant(group, AccountRef("101", email = "ada@hack4impact.org"), Access.WRITE))
		assertEquals(1, slept.size)

		server.resetAll(); slept.clear()
		server.stubFor(post(urlEqualTo("$dir/groups/$encoded/members")).willReturn(json(404, """{"error":{"code":404,"message":"Resource Not Found: groupKey"}}""")))
		assertFailsWith<Unavailable> { adapter.grant(group, AccountRef("101", email = "ada@hack4impact.org"), Access.WRITE) } // the sync retries it later
		assertEquals(2, slept.size)
	}

	@Test
	fun `deletes a group in scope only`() {
		server.stubFor(delete(urlEqualTo("$dir/groups/$encoded")).willReturn(aResponse().withStatus(204)))
		adapter.archive(group)
		server.verify(deleteRequestedFor(urlEqualTo("$dir/groups/$encoded")))
		assertFailsWith<Rejected> { adapter.archive("board@hack4impact.org") }
	}
}
