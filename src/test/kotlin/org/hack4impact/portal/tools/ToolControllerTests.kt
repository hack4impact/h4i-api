package org.hack4impact.portal.tools

import org.hack4impact.portal.resolver.Tool
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tools people have accounts in, as `/api/tools` lists them. */
class ToolControllerTests {
	private val registry = ToolRegistry(StaticListableBeanFactory().getBeanProvider(ToolDescriptor::class.java))

	@Test
	fun `lists every tool people have accounts in, in order (stage 2 adds Vaultwarden)`() {
		val listed = ToolController(registry).tools().map { Triple(it.key, it.name, it.order) }
		assertEquals(
			listOf(Triple("slack", "Slack", 1), Triple("google", "Google", 2), Triple("github", "GitHub", 3), Triple("notion", "Notion", 4), Triple("vaultwarden", "Vaultwarden", 5)),
			listed,
		)
	}

	@Test
	fun `a stored tool name maps back to its tool, and an unknown one to nothing`() {
		assertEquals(Tool.GITHUB, Tool.fromKey("GitHub"))
		assertEquals(null, Tool.fromKey("figma"))
		assertEquals(null, Tool.fromKey(null))
	}
}
