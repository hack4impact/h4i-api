package org.hack4impact.portal.adapters.vaultwarden

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.preflight.Check
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/** Vaultwarden's preflight (3-Vaultwarden): the service admin's role in the organization, and the organization key unlocking. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultwardenPreflightTests {
	private val server = WireMockServer(options().dynamicPort()).apply { start() }

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() {
		server.resetAll()
		json(post(urlEqualTo("/identity/connect/token")), """{"access_token":"vw-token","expires_in":3600}""")
		json(post(urlEqualTo("/identity/accounts/prelogin")), """{"kdf":0,"kdfIterations":${TestAccount.ITERATIONS}}""")
		json(get(urlEqualTo("/api/sync?excludeDomains=true")), """{"profile":{"key":"${TestAccount.protectedUserKey}","privateKey":"${TestAccount.protectedPrivateKey}",
			"organizations":[{"id":"org1","key":"${TestAccount.protectedOrgKey}"}]}}""")
		profile(type = 0, status = 2)
		json(get(urlEqualTo("/api/version")), "\"1.37.4\"")
	}

	private fun json(mapping: com.github.tomakehurst.wiremock.client.MappingBuilder, body: String) =
		server.stubFor(mapping.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))

	private fun profile(type: Int, status: Int) =
		json(get(urlEqualTo("/api/accounts/profile")), """{"email":"${TestAccount.EMAIL}","organizations":[{"id":"org1","type":$type,"status":$status}]}""")

	private fun checks(password: String = TestAccount.PASSWORD, write: Boolean = true) = VaultwardenPreflight(
		AdapterProperties(vaultwarden = AdapterProperties.Vaultwarden(
			enabled = true, baseUrl = server.baseUrl(), organizationId = "org1", clientId = "user.x", clientSecret = "s", write = write, masterPassword = password,
		)),
	).checks().associate { it.name to it.level }

	@Test
	fun `an owner whose master password unlocks the organization key is OK`() {
		assertEquals(
			mapOf("Vaultwarden version" to Check.Level.OK, "Vaultwarden service admin" to Check.Level.OK, "Vaultwarden organization key" to Check.Level.OK),
			checks(),
		)
	}

	@Test
	fun `a Vaultwarden older than 1_37 warns`() {
		json(get(urlEqualTo("/api/version")), "\"1.34.3\"")
		assertEquals(Check.Level.WARN, checks()["Vaultwarden version"])
	}

	@Test
	fun `a plain user or an unconfirmed admin fails`() {
		profile(type = 2, status = 2)
		assertEquals(Check.Level.FAIL, checks()["Vaultwarden service admin"])
		profile(type = 1, status = 1)
		assertEquals(Check.Level.FAIL, checks()["Vaultwarden service admin"])
	}

	@Test
	fun `a wrong or missing master password fails with writes on and warns with writes off`() {
		assertEquals(Check.Level.FAIL, checks(password = "wrong")["Vaultwarden organization key"])
		assertEquals(Check.Level.WARN, checks(password = "wrong", write = false)["Vaultwarden organization key"])
		assertEquals(Check.Level.FAIL, checks(password = "")["Vaultwarden organization key"])
		assertEquals(Check.Level.WARN, checks(password = "", write = false)["Vaultwarden organization key"])
	}
}
