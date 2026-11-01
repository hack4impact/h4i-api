package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpJsonTests {
	private val server = WireMockServer(options().dynamicPort())
	private val slept = mutableListOf<Duration>()

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() { server.resetAll(); slept.clear() }

	private fun http(policy: RateLimitPolicy) = HttpJson(Tool.SLACK, rateLimits = policy, sleep = { slept += it })

	/** Rate limited [times] times (Retry-After: 30), then answers. */
	private fun limitedThenOk(times: Int) {
		for (i in 0 until times) {
			server.stubFor(
				get(urlEqualTo("/users.list")).inScenario("limit").whenScenarioStateIs(if (i == 0) Scenario.STARTED else "limited-$i")
					.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")).willSetStateTo("limited-${i + 1}"),
			)
		}
		server.stubFor(
			get(urlEqualTo("/users.list")).inScenario("limit").whenScenarioStateIs(if (times == 0) Scenario.STARTED else "limited-$times")
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"ok":true}""")),
		)
	}

	@Test
	fun `a patient call waits what the tool asks, plus a second, and repeats only that request`() {
		limitedThenOk(2)
		val response = http(RateLimitPolicy.PATIENT).get("${server.baseUrl()}/users.list")
		assertEquals(200, response.status)
		assertEquals(listOf(Duration.ofSeconds(31), Duration.ofSeconds(31)), slept)
		server.verify(3, getRequestedFor(urlEqualTo("/users.list")))
	}

	@Test
	fun `waiting stops at the policy's limits, and by default a rate limit fails at once`() {
		limitedThenOk(3)
		assertFailsWith<RateLimited> { http(RateLimitPolicy(maxWaits = 2, maxWait = Duration.ofMinutes(1), maxTotal = Duration.ofMinutes(5))).get("${server.baseUrl()}/users.list") }
		assertEquals(2, slept.size)

		reset(); limitedThenOk(1)
		assertFailsWith<RateLimited> { http(RateLimitPolicy(maxWaits = 5, maxWait = Duration.ofSeconds(10), maxTotal = Duration.ofMinutes(5))).get("${server.baseUrl()}/users.list") }
		assertEquals(0, slept.size) // asked to wait longer than allowed

		reset(); limitedThenOk(1)
		assertFailsWith<RateLimited> { http(RateLimitPolicy.NONE).get("${server.baseUrl()}/users.list") }
		assertEquals(0, slept.size)
	}

	@Test
	fun `a GitHub org given as a link or with a slash becomes its login`() {
		for (value in listOf("hack4impact", "hack4impact/", " hack4impact ", "https://github.com/hack4impact", "github.com/hack4impact/", "https://github.com/hack4impact/some-repo")) {
			assertEquals("hack4impact", githubOrg(value), value)
		}
	}
}
