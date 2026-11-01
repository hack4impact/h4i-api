package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenApiKeyToken
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenReadAdapter
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class VaultwardenReadAdapterTests : WireMockContract() {
	private val org = "11111111-1111-1111-1111-111111111111"

	override val sandbox = Sandbox(
		listOf(
			ToolAccount("m1", "ada@hack4impact.org", "ada@hack4impact.org", "Ada", AccountState.ACTIVE),
			ToolAccount("m2", "alan@hack4impact.org", "alan@hack4impact.org", "Alan", AccountState.ACCEPTED),
			ToolAccount("m3", "new@hack4impact.org", "new@hack4impact.org", null, AccountState.INVITED),
			ToolAccount("m4", "gone@hack4impact.org", "gone@hack4impact.org", "Gone", AccountState.SUSPENDED),
		),
		mapOf(
			ToolResource("c1", "umd-rise-dc-dev") to listOf(ResourceMember("m1", Access.ADMIN), ResourceMember("m2", Access.WRITE), ResourceMember("m3", Access.READ)),
			ToolResource("c2", "umd-rise-dc-production") to listOf(ResourceMember("m1", Access.READ)),
		),
	)

	private fun status(state: AccountState) = when (state) {
		AccountState.INVITED -> 0
		AccountState.ACCEPTED -> 1
		AccountState.ACTIVE -> 2
		AccountState.SUSPENDED -> -1
	}

	private fun accessJson(collection: String, access: Access) =
		"""{"id":"$collection","readOnly":${access == Access.READ},"hidePasswords":false,"manage":${access == Access.ADMIN}}"""

	override fun stub(sandbox: Sandbox): VaultwardenReadAdapter = stub(sandbox, groups = emptyList())

	/** [groups]: (group ID, its collection access, member IDs). */
	private fun stub(sandbox: Sandbox, groups: List<Triple<String, String, List<String>>>): VaultwardenReadAdapter {
		val api = "/api/organizations/$org"
		fun list(items: List<String>) = """{"data":${items.joinToString(",", "[", "]")},"object":"list","continuationToken":null}"""
		json("$api/users", list(sandbox.accounts.map { """{"id":"${it.externalId}","email":"${it.email}","name":${str(it.name)},"status":${status(it.state)},"type":2}""" }))
		json("$api/collections", list(sandbox.resources.keys.map { """{"id":"${it.externalId}","name":"2.encrypted|name","externalId":"${it.name}"}""" }))
		json("$api/groups/details", list(groups.map { (id, collections, _) -> """{"id":"$id","name":"g","collections":[$collections]}""" }))
		json("$api/users?includeCollections=true&includeGroups=true", list(sandbox.accounts.map { account ->
			val direct = sandbox.resources.mapNotNull { (c, members) -> members.firstOrNull { it.accountId == account.externalId }?.let { accessJson(c.externalId, it.access) } }
			val inGroups = groups.filter { account.externalId in it.third }.map { "\"${it.first}\"" }
			"""{"id":"${account.externalId}","email":"${account.email}","status":${status(account.state)},"collections":[${direct.joinToString(",")}],"groups":[${inGroups.joinToString(",")}]}"""
		}))
		return VaultwardenReadAdapter(server.baseUrl(), org, { token })
	}

	@Test
	fun `access through a group counts, and the strongest of direct and group access wins`() {
		val noDirect = sandbox.copy(resources = mapOf(ToolResource("c1", "umd-rise-dc-dev") to listOf(ResourceMember("m2", Access.READ))))
		val adapter = stub(noDirect, groups = listOf(Triple("g-eng", accessJson("c1", Access.WRITE), listOf("m1", "m2"))))
		assertEquals(setOf(ResourceMember("m1", Access.WRITE), ResourceMember("m2", Access.WRITE)), adapter.members("c1").toSet())
	}

	@Test
	fun `a confirmed owner or admin reaches every collection through their role, as implicit access`() {
		val api = "/api/organizations/$org"
		json("$api/collections", """{"data":[{"id":"c1","name":"2.x|y","externalId":"umd-rise-dc"}]}""")
		json("$api/groups/details", """{"data":[]}""")
		json("$api/users?includeCollections=true&includeGroups=true", """{"data":[
			{"id":"owner","type":0,"status":2,"collections":[],"groups":[]},
			{"id":"admin-invited","type":1,"status":0,"collections":[],"groups":[]},
			{"id":"user","type":2,"status":2,"collections":[{"id":"c1","readOnly":false,"hidePasswords":false,"manage":false}],"groups":[]}]}""")
		assertEquals(
			setOf(ResourceMember("owner", Access.ADMIN, implicit = true), ResourceMember("user", Access.WRITE)),
			VaultwardenReadAdapter(server.baseUrl(), org, { token }).members("c1").toSet(),
		)
	}

	@Test
	fun `a rejected API key (400 invalid_client) is AuthFailed`() {
		server.stubFor(post(urlEqualTo("/identity/connect/token")).willReturn(aResponse().withStatus(400).withBody("""{"error":"invalid_client"}""")))
		val tokens = VaultwardenApiKeyToken(server.baseUrl(), "user.abc", "wrong", "device-1")
		kotlin.test.assertFailsWith<AuthFailed> { tokens() }
	}

	@Test
	fun `the API key token sends Vaultwarden's required fields and is reused until it expires`() {
		server.stubFor(post(urlEqualTo("/identity/connect/token")).willReturn(aResponse().withBody("""{"access_token":"vw-token","expires_in":3600,"token_type":"Bearer"}""")))
		val clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC)
		val tokens = VaultwardenApiKeyToken(server.baseUrl(), "user.abc", "secret", "device-1", HttpJson(Tool.VAULTWARDEN), clock)
		assertEquals("vw-token", tokens())
		assertEquals("vw-token", tokens())
		server.verify(1, postRequestedFor(urlEqualTo("/identity/connect/token"))
			.withRequestBody(containing("grant_type=client_credentials")).withRequestBody(containing("scope=api"))
			.withRequestBody(containing("client_id=user.abc")).withRequestBody(containing("device_identifier=device-1"))
			.withRequestBody(containing("device_name=h4i-portal")).withRequestBody(containing("device_type=")))
	}
}
