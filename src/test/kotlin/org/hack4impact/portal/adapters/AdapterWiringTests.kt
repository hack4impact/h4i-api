package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * From the full portal's, trimmed to what exists in stage 2 package 3. Each tool's adapter item adds its case to
 * "every adapter is created from environment variables with the documented names".
 */
class AdapterWiringTests {
	private val runner = ApplicationContextRunner().withUserConfiguration(AdapterConfiguration::class.java)

	@Test
	fun `no adapter is created unless enabled`() {
		runner.run {
			assertTrue(it.getBeansOfType(ReadAdapter::class.java).isEmpty())
			assertTrue(it.getBeansOfType(WriteAdapter::class.java).isEmpty())
		}
	}

	@Test
	fun `the check reports what each tool has, and fails when a tool can't be read`() {
		val slack = InMemoryReadAdapter(
			Tool.SLACK,
			listOf(ToolAccount("U1", "ada", null, null, AccountState.ACTIVE), ToolAccount("U2", "alan", null, null, AccountState.SUSPENDED)),
			mapOf(ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U1", Access.WRITE))),
		)
		val ok = AdapterCheck.run(listOf(slack))
		assertTrue(ok.ok)
		assertContains(ok.report, "accounts: 2 (active 1, suspended 1)")
		assertContains(ok.report, "umd-rise-dc: 1 members")

		val github = InMemoryReadAdapter(Tool.GITHUB, failure = AuthFailed(Tool.GITHUB, "HTTP 401"))
		val failed = AdapterCheck.run(listOf(slack, github))
		assertFalse(failed.ok)
		assertContains(failed.report, "FAILED: GITHUB: HTTP 401")
		assertFalse(AdapterCheck.run(emptyList()).ok)
	}
}
