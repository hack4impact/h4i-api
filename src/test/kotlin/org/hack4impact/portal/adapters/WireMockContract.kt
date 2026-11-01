package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

/** Runs the contract against a real adapter talking HTTP to WireMock. Stubs require the test token. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WireMockContract : ReadAdapterContract() {
	protected val server = WireMockServer(options().dynamicPort())
	protected val token = "test-token"

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	/** Stubs the tool's API so it contains [sandbox], and returns an adapter pointed at WireMock with page size 2. */
	protected abstract fun stub(sandbox: Sandbox): ReadAdapter

	override fun adapter(sandbox: Sandbox, failure: Failure?): ReadAdapter {
		val adapter = stub(sandbox)
		failure?.let { stubFailure(it) }
		return adapter
	}

	/** Makes every call fail. Tools that report a failure differently (Slack) override this. */
	protected open fun stubFailure(failure: Failure) {
		val response = when (failure) {
			Failure.RATE_LIMIT -> aResponse().withStatus(429).withHeader("Retry-After", "30")
			Failure.SERVER_ERROR -> aResponse().withStatus(502)
			Failure.BAD_CREDENTIALS -> aResponse().withStatus(401)
		}
		server.stubFor(any(anyUrl()).atPriority(1).willReturn(response))
	}

	protected fun json(url: String, body: String, headers: Map<String, String> = emptyMap()) {
		server.stubFor(authed(get(urlEqualTo(url))).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body).apply {
			headers.forEach { (k, v) -> withHeader(k, v) }
		}))
	}

	protected fun authed(mapping: MappingBuilder): MappingBuilder = mapping.withHeader("Authorization", equalTo("Bearer $token"))

	/** Splits items into pages of two. */
	protected fun <T> pagesOf(items: List<T>): List<List<T>> = items.chunked(2).ifEmpty { listOf(emptyList()) }

	protected fun str(value: String?) = if (value == null) "null" else "\"${value.replace("\"", "\\\"")}\""
}
