package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.github.GitHubWriteAdapter
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
class GitHubWriteAdapterTests {
	private val server = WireMockServer(options().dynamicPort())
	private val org = "h4i-portal-sandbox"
	private val adapter by lazy { GitHubWriteAdapter(server.baseUrl(), org, { "ghs_test" }) }

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() = server.resetAll()

	private fun json(status: Int, body: String) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

	@Test
	fun `creates a closed team and returns its slug, and explains a taken name`() {
		server.stubFor(post(urlEqualTo("/orgs/$org/teams")).withHeader("Authorization", equalTo("Bearer ghs_test")).willReturn(json(201, """{"id":7,"slug":"umd-rise-dc"}""")))
		assertEquals("umd-rise-dc", adapter.create("umd-rise-dc"))
		server.verify(postRequestedFor(urlEqualTo("/orgs/$org/teams")).withRequestBody(equalToJson("""{"name":"umd-rise-dc","privacy":"closed"}""")))

		server.stubFor(post(urlEqualTo("/orgs/$org/teams")).willReturn(json(422, """{"message":"Validation Failed","errors":[{"message":"Name must be unique for this org"}]}""")))
		assertContains(assertFailsWith<Rejected> { adapter.create("umd-rise-dc") }.message!!, "adopt it instead")
	}

	@Test
	fun `adds by login as member or maintainer, and invites someone outside the org`() {
		server.stubFor(put(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")).willReturn(json(200, """{"state":"active","role":"maintainer"}""")))
		assertEquals(GrantResult.DONE, adapter.grant("umd-rise-dc", AccountRef("1001", "ada"), Access.ADMIN))
		server.verify(putRequestedFor(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")).withRequestBody(equalToJson("""{"role":"maintainer"}""")))

		server.stubFor(put(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/newbie")).willReturn(json(200, """{"state":"pending","role":"member"}""")))
		assertEquals(GrantResult.INVITED, adapter.grant("umd-rise-dc", AccountRef(null, "newbie"), Access.WRITE))
	}

	@Test
	fun `an account known only by ID is looked up, and one known only by email is invited into the team`() {
		server.stubFor(get(urlEqualTo("/user/1002")).willReturn(json(200, """{"id":1002,"login":"alan"}""")))
		server.stubFor(put(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/alan")).willReturn(json(200, """{"state":"active"}""")))
		assertEquals(GrantResult.DONE, adapter.grant("umd-rise-dc", AccountRef("1002"), Access.WRITE))

		server.stubFor(get(urlEqualTo("/orgs/$org/teams/umd-rise-dc")).willReturn(json(200, """{"id":7,"slug":"umd-rise-dc"}""")))
		server.stubFor(post(urlEqualTo("/orgs/$org/invitations")).willReturn(json(201, """{"id":99}""")))
		assertEquals(GrantResult.INVITED, adapter.grant("umd-rise-dc", AccountRef(null, email = "cy@hack4impact.org"), Access.WRITE))
		server.verify(postRequestedFor(urlEqualTo("/orgs/$org/invitations"))
			.withRequestBody(equalToJson("""{"email":"cy@hack4impact.org","role":"direct_member","team_ids":[7]}""")))

		assertEquals(GrantResult.WAITING, adapter.grant("umd-rise-dc", AccountRef(null), Access.WRITE))
	}

	@Test
	fun `removes by looking up the login, and someone already gone counts as done`() {
		server.stubFor(get(urlEqualTo("/user/1001")).willReturn(json(200, """{"id":1001,"login":"ada"}""")))
		server.stubFor(delete(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")).willReturn(aResponse().withStatus(204)))
		adapter.revoke("umd-rise-dc", "1001")
		server.verify(deleteRequestedFor(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")))

		server.stubFor(delete(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")).willReturn(json(404, """{"message":"Not Found"}""")))
		adapter.revoke("umd-rise-dc", "1001")
		server.stubFor(get(urlEqualTo("/user/404404")).willReturn(json(404, """{"message":"Not Found"}""")))
		adapter.revoke("umd-rise-dc", "404404") // a deleted account: nothing to do
	}

	@Test
	fun `a team the app may not change fails that change only (review M1)`() {
		server.stubFor(put(urlEqualTo("/orgs/$org/teams/umd-rise-dc/memberships/ada")).willReturn(json(403, """{"message":"Resource not accessible by integration"}""")))
		assertFailsWith<Rejected> { adapter.grant("umd-rise-dc", AccountRef(null, "ada"), Access.WRITE) }
	}

	@Test
	fun `deletes a team, and one already gone counts as done`() {
		server.stubFor(delete(urlEqualTo("/orgs/$org/teams/umd-rise-dc")).willReturn(aResponse().withStatus(204)))
		adapter.archive("umd-rise-dc")
		server.verify(deleteRequestedFor(urlEqualTo("/orgs/$org/teams/umd-rise-dc")))
		server.stubFor(delete(urlEqualTo("/orgs/$org/teams/umd-rise-dc")).willReturn(json(404, """{"message":"Not Found"}""")))
		adapter.archive("umd-rise-dc")
	}
}
