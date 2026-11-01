package org.hack4impact.portal.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/** The tools people have accounts in, as `/api/tools` lists them. */
class ToolControllerTests {
	@Test
	fun `lists every tool in order`() {
		val listed = ToolController().tools().map { Triple(it.key, it.name, it.order) }
		assertEquals(listOf(Triple("slack", "Slack", 1), Triple("google", "Google", 2), Triple("github", "GitHub", 3), Triple("notion", "Notion", 4)), listed)
	}

	@Test
	fun `a stored tool name maps back to its tool, and an unknown one to nothing`() {
		assertEquals(Tool.GITHUB, Tool.fromKey("GitHub"))
		assertEquals(null, Tool.fromKey("figma"))
		assertEquals(null, Tool.fromKey(null))
	}
}
