package org.hack4impact.portal.tools

import tools.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tools people have accounts in, as `/api/tools` lists them, and the web tests' copy of that list. */
class ToolControllerTests {
	@Test
	fun `lists every tool in order, the same as the web tests' copy`() {
		val listed = ToolController().tools()
		assertEquals(listOf("slack", "google", "github", "notion"), listed.map { it.key })
		val copy = JsonMapper.builder().build().readTree(File("web/src/test/tools.json"))
		val web: List<Triple<String, String, Int>> = copy.toList().map { Triple(it.path("key").asString(), it.path("name").asString(), it.path("order").asInt()) }
		assertEquals(listed.map { Triple(it.key, it.name, it.order) }, web)
	}

	@Test
	fun `a stored tool name maps back to its tool, and an unknown one to nothing`() {
		assertEquals(Tool.GITHUB, Tool.fromKey("GitHub"))
		assertEquals(null, Tool.fromKey("figma"))
		assertEquals(null, Tool.fromKey(null))
	}
}
