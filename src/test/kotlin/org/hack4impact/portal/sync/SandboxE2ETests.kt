package org.hack4impact.portal.sync

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/** The sandbox suite's parts that run without the sandboxes (stage 2 package 9); the suite itself is scripts/sandbox-e2e.sh. */
class SandboxE2ETests {
	private val at = LocalDateTime.of(2026, 11, 1, 15, 42, 7)

	@Test
	fun `each run's resources start with the tester and the time to the second, in characters every tool accepts`() {
		assertEquals("sandbox-chase-fournier-20261101154207-", sandboxPrefix("Chase.Fournier", at))
		assertEquals("sandbox-tester-20261101154207-", sandboxPrefix("__", at))
		assertEquals("sandbox-a-very-long-user-nam-20261101154207-", sandboxPrefix("a-very-long-user-name-from-some-machine", at))
	}
}
