package org.hack4impact.portal.adapters

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a tool contains, in the portal's terms. Each adapter's test turns it into that tool's API responses. */
data class Sandbox(val accounts: List<ToolAccount>, val resources: Map<ToolResource, List<ResourceMember>>)

enum class Failure { RATE_LIMIT, SERVER_ERROR, BAD_CREDENTIALS }

/**
 * The contract every read adapter passes (PRD: adapter contract tests). Real adapters run it against
 * WireMock with page size 2, so a sandbox of three or more accounts exercises pagination.
 */
abstract class ReadAdapterContract {
	/** At least three accounts, at least two resources, one of them with members at different access levels. */
	protected abstract val sandbox: Sandbox

	protected abstract fun adapter(sandbox: Sandbox, failure: Failure? = null): ReadAdapter

	@Test
	fun `lists every account, across pages`() {
		val accounts = adapter(sandbox).accounts()
		assertEquals(sandbox.accounts.size, accounts.size)
		assertEquals(sandbox.accounts.toSet(), accounts.toSet())
	}

	@Test
	fun `lists every resource`() {
		assertEquals(sandbox.resources.keys, adapter(sandbox).resources().toSet())
	}

	@Test
	fun `lists each resource's members with their access`() {
		val adapter = adapter(sandbox)
		for ((resource, members) in sandbox.resources) {
			assertEquals(members.toSet(), adapter.members(resource.externalId).toSet(), resource.externalId)
		}
	}

	@Test
	fun `an unknown resource is NotFound`() {
		assertFailsWith<NotFound> { adapter(sandbox).members("no-such-resource") }
	}

	@Test
	fun `rate limiting is RateLimited, with the tool's retry-after`() {
		val e = assertFailsWith<RateLimited> { adapter(sandbox, Failure.RATE_LIMIT).accounts() }
		assertEquals(Duration.ofSeconds(30), e.retryAfter)
		assertTrue(e.retryable)
	}

	@Test
	fun `server errors are Unavailable and retryable`() {
		assertTrue(assertFailsWith<Unavailable> { adapter(sandbox, Failure.SERVER_ERROR).resources() }.retryable)
	}

	@Test
	fun `bad credentials are AuthFailed and not retried`() {
		assertFalse(assertFailsWith<AuthFailed> { adapter(sandbox, Failure.BAD_CREDENTIALS).accounts() }.retryable)
	}
}
