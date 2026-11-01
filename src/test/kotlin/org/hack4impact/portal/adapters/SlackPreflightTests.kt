package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.slack.SlackPreflight
import org.hack4impact.portal.preflight.Check
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slack's preflight (3-Slack): the bot's scopes from `auth.test`, FAIL when under-granted and OK when all are there. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackPreflightTests {
	private val server = WireMockServer(options().dynamicPort()).apply { start() }

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	private fun preflight(write: Boolean) =
		SlackPreflight(AdapterProperties(slack = AdapterProperties.Slack(enabled = true, baseUrl = server.baseUrl(), botToken = "xoxb-test", write = write))).checks().single()

	private fun authTest(scopes: String, ok: Boolean = true) = server.stubFor(
		get(urlEqualTo("/auth.test")).withHeader("Authorization", equalTo("Bearer xoxb-test")).willReturn(
			aResponse().withHeader("Content-Type", "application/json").withHeader("x-oauth-scopes", scopes)
				.withBody(if (ok) """{"ok":true,"team":"Hack4Impact"}""" else """{"ok":false,"error":"invalid_auth"}"""),
		),
	)

	@Test
	fun `every scope granted is OK`() {
		authTest((SlackPreflight.READ + SlackPreflight.WRITE).joinToString(","))
		assertEquals(Check.Level.OK, preflight(write = true).level)
	}

	@Test
	fun `a missing read scope fails, a missing write scope fails only with the write switch on`() {
		authTest("channels:read,groups:read,users:read")
		val missingEmail = preflight(write = false)
		assertEquals(Check.Level.FAIL, missingEmail.level)
		assertTrue("users:read.email" in missingEmail.detail, missingEmail.detail)

		authTest(SlackPreflight.READ.joinToString(","))
		assertEquals(Check.Level.WARN, preflight(write = false).level)
		assertEquals(Check.Level.FAIL, preflight(write = true).level)
	}

	@Test
	fun `a bad token fails`() {
		authTest("", ok = false)
		assertEquals(Check.Level.FAIL, preflight(write = false).level)
		assertTrue("invalid_auth" in preflight(write = false).detail)
	}
}
