package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.slack.SlackWriteAdapter
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
class SlackWriteAdapterTests {
	private val server = WireMockServer(options().dynamicPort())
	private val adapter by lazy { SlackWriteAdapter(server.baseUrl(), { "xoxb-test" }) }

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() = server.resetAll()

	private fun answer(method: String, body: String) = server.stubFor(
		post(urlEqualTo("/$method")).withHeader("Authorization", equalTo("Bearer xoxb-test"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)),
	)

	@Test
	fun `creates a private channel and returns its ID`() {
		answer("conversations.create", """{"ok":true,"channel":{"id":"C123","name":"umd-rise-dc"}}""")
		assertEquals("C123", adapter.create("umd-rise-dc"))
		server.verify(postRequestedFor(urlEqualTo("/conversations.create")).withRequestBody(equalToJson("""{"name":"umd-rise-dc","is_private":true}""")))
	}

	@Test
	fun `a taken name explains that it's probably a channel the portal can't see`() {
		answer("conversations.create", """{"ok":false,"error":"name_taken"}""")
		val e = assertFailsWith<Rejected> { adapter.create("umd-rise-dc") }
		assertContains(e.message!!, "invite the portal's Slack app to it and adopt it")
	}

	@Test
	fun `invites a member, and someone already in the channel counts as done`() {
		answer("conversations.invite", """{"ok":true}""")
		assertEquals(GrantResult.DONE, adapter.grant("C123", AccountRef("U1"), Access.WRITE))
		server.verify(postRequestedFor(urlEqualTo("/conversations.invite")).withRequestBody(equalToJson("""{"channel":"C123","users":"U1"}""")))
		answer("conversations.invite", """{"ok":false,"error":"already_in_channel"}""")
		assertEquals(GrantResult.DONE, adapter.grant("C123", AccountRef("U1"), Access.ADMIN))
	}

	@Test
	fun `someone without a Slack account is waited for, without calling Slack`() {
		assertEquals(GrantResult.WAITING, adapter.grant("C123", AccountRef(null, email = "new@hack4impact.org"), Access.WRITE))
		assertEquals(0, server.allServeEvents.size)
	}

	@Test
	fun `removes a member, and someone already gone counts as done`() {
		answer("conversations.kick", """{"ok":false,"error":"not_in_channel"}""")
		adapter.revoke("C123", "U1")
		server.verify(postRequestedFor(urlEqualTo("/conversations.kick")).withRequestBody(equalToJson("""{"channel":"C123","user":"U1"}""")))
	}

	@Test
	fun `missing scopes are an auth failure, and an unknown channel is not found`() {
		answer("conversations.invite", """{"ok":false,"error":"missing_scope"}""")
		assertFailsWith<AuthFailed> { adapter.grant("C123", AccountRef("U1"), Access.WRITE) }
		answer("conversations.kick", """{"ok":false,"error":"channel_not_found"}""")
		assertFailsWith<NotFound> { adapter.revoke("C404", "U1") }
	}

	@Test
	fun `archives a channel, and one already archived counts as done`() {
		answer("conversations.archive", """{"ok":false,"error":"already_archived"}""")
		adapter.archive("C123")
		server.verify(postRequestedFor(urlEqualTo("/conversations.archive")).withRequestBody(equalToJson("""{"channel":"C123"}""")))
	}
}
