package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.github.GitHubPreflight
import org.hack4impact.portal.preflight.Check
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/** GitHub's preflight (3-GitHub): the App installation's Members permission, FAIL when under-granted and OK when it can write. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubPreflightTests {
	private val server = WireMockServer(options().dynamicPort()).apply { start() }
	private val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(
		java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded,
	) + "\n-----END PRIVATE KEY-----\n"

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	private fun installation(permissions: String) = server.stubFor(
		post(urlEqualTo("/app/installations/456/access_tokens")).withHeader("Authorization", matching("Bearer .+\\..+\\..+"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"token":"ghs_x","expires_at":"2030-01-01T00:00:00Z","permissions":$permissions}""")),
	)

	private fun preflight(write: Boolean) = GitHubPreflight(
		AdapterProperties(github = AdapterProperties.GitHub(enabled = true, baseUrl = server.baseUrl(), org = "h4i", appId = "123", installationId = "456", privateKey = pem, write = write)),
	).checks().single()

	@Test
	fun `Members read and write is OK`() {
		installation("""{"members":"write","metadata":"read"}""")
		assertEquals(Check.Level.OK, preflight(write = true).level)
	}

	@Test
	fun `no Members permission fails, read only fails only with the write switch on`() {
		installation("""{"metadata":"read"}""")
		assertEquals(Check.Level.FAIL, preflight(write = false).level)
		installation("""{"members":"read"}""")
		assertEquals(Check.Level.WARN, preflight(write = false).level)
		assertEquals(Check.Level.FAIL, preflight(write = true).level)
	}
}
