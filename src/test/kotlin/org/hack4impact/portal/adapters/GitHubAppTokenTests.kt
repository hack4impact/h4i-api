package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.github.GitHubAppToken
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubAppTokenTests {
	private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
	private val now = Instant.parse("2026-10-09T12:00:00Z")
	private val server = WireMockServer(options().dynamicPort()).apply { start() }

	@AfterEach
	fun stop() = server.stop()

	private fun pem(type: String, der: ByteArray) =
		"-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

	private val pkcs8 = pem("PRIVATE KEY", keys.private.encoded)

	// GitHub issues PKCS#1. For a 2048-bit key, PKCS#8 is a 26-byte header around the PKCS#1 bytes.
	private val pkcs1 = pem("RSA PRIVATE KEY", keys.private.encoded.copyOfRange(26, keys.private.encoded.size))

	private fun verifies(jwt: String): Boolean {
		val (header, payload, signature) = jwt.split(".")
		return Signature.getInstance("SHA256withRSA").apply {
			initVerify(keys.public)
			update("$header.$payload".toByteArray())
		}.verify(Base64.getUrlDecoder().decode(signature))
	}

	@Test
	fun `signs a valid RS256 JWT from a PKCS#1 or PKCS#8 key`() {
		for (key in listOf(pkcs1, pkcs8)) {
			val jwt = GitHubAppToken(server.baseUrl(), "12345", "678", key, clock = Clock.fixed(now, ZoneOffset.UTC)).jwt()
			assertTrue(verifies(jwt))
			val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
			assertEquals("""{"iat":${now.epochSecond - 60},"exp":${now.epochSecond + 540},"iss":"12345"}""", payload)
		}
	}

	@Test
	fun `exchanges the JWT for an installation token and reuses it until a minute before expiry`() {
		server.stubFor(post(urlEqualTo("/app/installations/678/access_tokens"))
			.withHeader("Authorization", matching("Bearer .+\\..+\\..+"))
			.willReturn(aResponse().withStatus(201).withBody("""{"token":"ghs_test","expires_at":"2026-10-09T13:00:00Z"}""")))
		val clock = MutableClock(now)
		val tokens = GitHubAppToken(server.baseUrl(), "12345", "678", pkcs1, HttpJson(Tool.GITHUB), clock)
		assertEquals("ghs_test", tokens())
		assertEquals("ghs_test", tokens())
		server.verify(1, postRequestedFor(urlEqualTo("/app/installations/678/access_tokens")))
		clock.now = Instant.parse("2026-10-09T12:59:30Z") // within a minute of expiry
		tokens()
		server.verify(2, postRequestedFor(urlEqualTo("/app/installations/678/access_tokens")))
	}

	private class MutableClock(var now: Instant) : Clock() {
		override fun getZone(): ZoneId = ZoneOffset.UTC
		override fun withZone(zone: ZoneId): Clock = this
		override fun instant(): Instant = now
	}
}
