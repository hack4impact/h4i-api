package org.hack4impact.portal.memberimport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Shapes seen in the real member DB on 2026-10-09 (wiki: member database, check results). */
class GitHubValueTests {

	private fun username(raw: String): String {
		val parsed = GitHubValue.parse(raw)
		assertNull(parsed.problem, raw)
		assertEquals(false, parsed.isId)
		return parsed.value
	}

	@Test
	fun `profile URLs become usernames`() {
		assertEquals("ada", username("https://github.com/ada"))
		assertEquals("ada", username("http://www.github.com/ada/"))
		assertEquals("ada", username("https://GitHub.com/ada/some-repo"))
		assertEquals("ada", username("github.com/ada?tab=repositories"))
	}

	@Test
	fun `usernames with a scheme stuck in front are still usernames`() {
		assertEquals("ada-l", username("https://ada-l"))
		assertEquals("ada-l", username("ada-l"))
	}

	@Test
	fun `a bare number is a user ID`() {
		val parsed = GitHubValue.parse("1234567")
		assertEquals(true, parsed.isId)
		assertEquals("1234567", parsed.value)
	}

	@Test
	fun `other sites and empty paths are rejected`() {
		assertNotNull(GitHubValue.parse("https://www.linkedin.com/in/ada").problem)
		assertNotNull(GitHubValue.parse("https://github.com/").problem)
		assertNotNull(GitHubValue.parse("https://ada.github.io").problem)
	}
}
