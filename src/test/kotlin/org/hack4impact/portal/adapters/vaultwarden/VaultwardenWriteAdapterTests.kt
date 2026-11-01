package org.hack4impact.portal.adapters.vaultwarden

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.resolver.Access
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultwardenWriteAdapterTests {
	private val server = WireMockServer(options().dynamicPort())
	private val org = "/api/organizations/org1"
	private lateinit var adapter: VaultwardenWriteAdapter

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()

	@BeforeEach
	fun reset() {
		server.resetAll()
		adapter = VaultwardenWriteAdapter(server.baseUrl(), "org1", { "vw-token" }, TestAccount.PASSWORD)
		json(get(urlEqualTo("/api/accounts/profile")), """{"email":"${TestAccount.EMAIL}"}""")
		// Pre-login is public: no token.
		server.stubFor(post(urlEqualTo("/identity/accounts/prelogin")).willReturn(aResponse().withHeader("Content-Type", "application/json")
			.withBody("""{"kdf":0,"kdfIterations":${TestAccount.ITERATIONS}}""")))
		json(get(urlEqualTo("/api/sync?excludeDomains=true")), """{"profile":{"key":"${TestAccount.protectedUserKey}","privateKey":"${TestAccount.protectedPrivateKey}",
			"organizations":[{"id":"org1","key":"${TestAccount.protectedOrgKey}"}]}}""")
	}

	private fun json(mapping: com.github.tomakehurst.wiremock.client.MappingBuilder, body: String) =
		server.stubFor(mapping.withHeader("Authorization", equalTo("Bearer vw-token")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))

	private fun sent(method: String, path: String): JsonNode =
		HttpJson.MAPPER.readTree(server.allServeEvents.first { it.request.method.name == method && it.request.url == path }.request.bodyAsString)

	@Test
	fun `creates a collection whose name is encrypted with the organization key`() {
		server.stubFor(post(urlEqualTo("$org/collections")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"col1"}""")))
		assertEquals("col1", adapter.create("umd-rise-dc"))
		val body = sent("POST", "$org/collections")
		assertEquals("umd-rise-dc", VaultwardenCrypto.decryptString(body.path("name").asString(), TestAccount.orgKey))
		assertEquals("umd-rise-dc", body.path("externalId").asString())
	}

	@Test
	fun `gives a member access on the collection, keeping its name, groups and other members`() {
		json(get(urlEqualTo("$org/collections/col1/details")), """{"id":"col1","name":"2.enc|name|mac","externalId":"umd-rise-dc",
			"groups":[{"id":"g1","readOnly":true,"hidePasswords":false,"manage":false}]}""")
		json(get(urlEqualTo("$org/collections/col1/users")), """[{"id":"m-old","readOnly":false,"hidePasswords":false,"manage":true}]""")
		server.stubFor(put(urlEqualTo("$org/collections/col1")).willReturn(aResponse().withStatus(200).withBody("{}")))

		assertEquals(GrantResult.DONE, adapter.grant("col1", AccountRef("m-new"), Access.READ))
		val body = sent("PUT", "$org/collections/col1")
		assertEquals("2.enc|name|mac", body.path("name").asString())
		assertEquals("g1", body.path("groups")[0].path("id").asString())
		assertEquals(listOf("m-old", "m-new"), body.path("users").toList().map { it.path("id").asString() })
		assertEquals(true, body.path("users")[1].path("readOnly").asBoolean())
		assertEquals(true, body.path("users")[0].path("manage").asBoolean())
	}

	@Test
	fun `removes a member from the collection`() {
		json(get(urlEqualTo("$org/collections/col1/details")), """{"id":"col1","name":"2.a|b|c","externalId":null,"groups":[]}""")
		json(get(urlEqualTo("$org/collections/col1/users")), """[{"id":"m1"},{"id":"m2"}]""")
		server.stubFor(put(urlEqualTo("$org/collections/col1")).willReturn(aResponse().withStatus(200).withBody("{}")))
		adapter.revoke("col1", "m1")
		assertEquals(listOf("m2"), sent("PUT", "$org/collections/col1").path("users").toList().map { it.path("id").asString() })
	}

	@Test
	fun `invites someone outside the organization straight into the collection`() {
		server.stubFor(post(urlEqualTo("$org/users/invite")).willReturn(aResponse().withStatus(200).withBody("{}")))
		assertEquals(GrantResult.INVITED, adapter.grant("col1", AccountRef(null, email = "cy@hack4impact.org"), Access.ADMIN))
		val body = sent("POST", "$org/users/invite")
		assertEquals("cy@hack4impact.org", body.path("emails")[0].asString())
		assertEquals(2, body.path("type").asInt())
		assertEquals("col1", body.path("collections")[0].path("id").asString())
		assertEquals(true, body.path("collections")[0].path("manage").asBoolean())
		assertEquals(GrantResult.WAITING, adapter.grant("col1", AccountRef(null), Access.WRITE))
	}

	@Test
	fun `confirms accepted members of managed collections by sharing the organization key with their public key`() {
		val member = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		json(get(urlEqualTo("$org/users?includeCollections=true")), """{"data":[
			{"id":"m1","userId":"u1","status":1,"collections":[{"id":"col1"}]},
			{"id":"m2","userId":"u2","status":2,"collections":[{"id":"col1"}]},
			{"id":"m3","userId":"u3","status":1,"collections":[{"id":"not-ours"}]}]}""")
		json(get(urlEqualTo("/api/users/u1/public-key")), """{"publicKey":"${Base64.getEncoder().encodeToString(member.public.encoded)}"}""")
		server.stubFor(post(urlEqualTo("$org/users/m1/confirm")).willReturn(aResponse().withStatus(200).withBody("{}")))

		assertEquals(1, adapter.confirmPending(setOf("col1")))
		val key = sent("POST", "$org/users/m1/confirm").path("key").asString()
		assertContentEquals(TestAccount.orgKey.bytes, VaultwardenCrypto.rsaDecrypt(key, member.private))
	}
}
