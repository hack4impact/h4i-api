package org.hack4impact.portal.tools

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps adding a tool a matter of its adapters and one descriptor (wiki: tool plugins). Ported from the full portal,
 * trimmed to the files that exist here.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ToolPluginGuardTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val bootstrap: ToolSettingsBootstrap,
	@Autowired private val worlds: org.hack4impact.portal.sync.WorldLoader,
) {
	companion object {
		private val KEYS = Tool.entries.map { it.name.lowercase() }

		/** Files that may name several tools: the registry, the adapters' wiring, the member database's provider names, the sandbox suite. */
		private val ALLOWED = setOf(
			"src/main/kotlin/org/hack4impact/portal/tools/ToolRegistry.kt",
			"src/main/kotlin/org/hack4impact/portal/adapters/AdapterConfiguration.kt", // one block per tool's adapters
			"src/main/kotlin/org/hack4impact/portal/memberimport/MemberDbImporter.kt", // the member DB's provider names
			"src/main/kotlin/org/hack4impact/portal/sync/SandboxE2E.kt", // the sandbox suite checks each tool its own way (package 9)
		)
	}

	@Test
	fun `no file outside the registry lists tools (a new list would miss a plugin)`() {
		val listing = File("src/main/kotlin").walk().filter { it.extension == "kt" }.mapNotNull { file ->
			val path = file.invariantSeparatorsPath
			if (path in ALLOWED) return@mapNotNull null
			val text = file.readText()
			val named = KEYS.filter { key -> Regex("\"$key\"|'$key'|\\bTool\\.${key.uppercase()}\\b|\\b$key:").containsMatchIn(text) }
			"$path names ${named.joinToString()}".takeIf { named.size >= 3 }
		}.toList()
		assertTrue(listing.isEmpty(), "Read these from the tool registry instead (wiki: tool plugins):\n" + listing.joinToString("\n"))
	}

	@Test
	fun `a resource of a tool no code knows is left out of the sync, not the end of it (tool plugins review)`() {
		val r = org.hack4impact.portal.db.tables.references.RESOURCE
		val id = dsl.insertInto(r).set(r.TOOL, "figma").set(r.NAME, "left-behind").returningResult(r.ID).fetchSingle().value1()!!
		try {
			val loaded = worlds.load()
			assertTrue(loaded.world.resources.none { it.id == id })
			assertTrue(loaded.warnings.any { "unknown tool 'figma'" in it }, loaded.warnings.toString())
		} finally {
			dsl.deleteFrom(r).where(r.ID.eq(id)).execute()
		}
	}

	@Test
	fun `every tool the sync manages gets its settings row at startup, in dry run, and no other tool does`() {
		dsl.emptyPortalTables()
		dsl.deleteFrom(TOOL_SETTING).execute()

		bootstrap.afterSingletonsInstantiated()

		val rows = dsl.selectFrom(TOOL_SETTING).fetch()
		assertEquals(setOf("slack", "google", "github", "vaultwarden"), rows.map { it.tool }.toSet())
		assertTrue(rows.all { it.enabled == true && it.dryRun == true })
	}
}
