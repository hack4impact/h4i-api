package org.hack4impact.portal.tools

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the registry says about each essential tool. Ported from the full portal with stage 2's changes
 * (wiki: staged-rollout-stage-2, tool model): Notion has no project resources, and two new flags say which tools
 * people have accounts in and which the sync manages.
 */
class ToolRegistryGoldenTests {
	private val registry = ToolRegistry(StaticListableBeanFactory().getBeanProvider(ToolDescriptor::class.java))

	@Test
	fun `every tool has a descriptor`() {
		assertEquals(Tool.entries.toSet(), registry.all.map { it.tool }.toSet())
	}

	@Test
	fun `names, labels and box titles are the full portal's`() {
		assertEquals(mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google", "notion" to "Notion", "vaultwarden" to "Vaultwarden", "documenso" to "Documenso"),
			registry.all.associate { it.key to it.name })
		assertEquals(mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google group", "vaultwarden" to "Vaultwarden"),
			registry.resourceTools.associate { it.key to it.resourceLabel })
		assertEquals("umd-rise (Google group)", registry.label("google", "umd-rise"))
		assertEquals(listOf("slack" to "Slack channels", "github" to "GitHub teams", "vaultwarden" to "Vaultwarden collections", "google" to "Google groups"),
			registry.resourceTools.sortedBy { it.projectOrder }.map { it.key to it.resourcesTitle })
	}

	@Test
	fun `list order is the member page's`() {
		assertEquals(listOf("slack", "google", "github", "notion", "vaultwarden", "documenso"), registry.all.map { it.key })
	}

	@Test
	fun `accounts, syncing and project resources`() {
		assertEquals(listOf("slack", "google", "github", "notion", "vaultwarden"), registry.accountTools.map { it.key }) // /api/tools
		assertEquals(listOf("slack", "google", "github", "vaultwarden"), registry.syncTools.map { it.key }) // tool_setting rows, /api/national/tools
		assertEquals(listOf("slack", "google", "github", "vaultwarden"), registry.resourceTools.map { it.key }) // Notion pages are stage 4
	}

	@Test
	fun `policy flags are the full portal's`() {
		assertEquals(setOf(Tool.GITHUB, Tool.VAULTWARDEN), registry.sensitive)
		assertEquals(setOf(Tool.SLACK), registry.all.filter { it.archiveOnClose }.map { it.tool }.toSet())
		assertEquals(setOf("github", "google", "vaultwarden"), registry.resourceTools.filter { it.hasAdminLevel }.map { it.key }.toSet())
	}

	@Test
	fun `naming is the full portal's`() {
		val rules = registry.all.associate { it.key to it.naming?.hint }
		assertEquals(mapOf("slack" to "lowercase letters, digits, - and _, at most 80", "google" to "an address at hack4impact.org", "github" to "lowercase letters, digits and -",
			"notion" to null, "vaultwarden" to "at most 100 characters", "documenso" to null), rules)
		assertTrue(registry.find("slack")!!.naming!!.pattern.matches("umd-rise_dc"))
		assertTrue(!registry.find("slack")!!.naming!!.pattern.matches("Umd"))
		assertTrue(registry.find("google")!!.naming!!.pattern.matches("umd-rise@umd.hack4impact.org"))
		assertTrue(!registry.find("google")!!.naming!!.pattern.matches("umd-rise@gmail.com"))
		assertTrue(registry.find("github")!!.naming!!.pattern.matches("umd-rise-dc"))
		assertTrue(registry.find("vaultwarden")!!.naming!!.pattern.matches("UMD Rise: shared"))
		assertEquals(setOf("slack", "github", "google"), registry.all.filter { it.lowercaseNames }.map { it.key }.toSet())
		assertEquals(mapOf("slack" to "{chapter}-{project}-eng", "google" to "{chapter}-{project}-eng@hack4impact.org", "github" to "{chapter}-{project}-eng",
			"vaultwarden" to "{chapter}-{project}-eng"), registry.resourceTools.associate { it.key to it.nameExample })
		assertEquals("#umd-rise", registry.displayName("slack", "umd-rise"))
		assertEquals("umd-rise", registry.displayName("github", "umd-rise"))
	}

	@Test
	fun `links into the tools are the full portal's`() {
		val settings = AdapterProperties().let {
			it.copy(github = it.github.copy(org = "https://github.com/hack4impact"), vaultwarden = it.vaultwarden.copy(baseUrl = "https://vault.example/"))
		}
		fun link(key: String, name: String, id: String) = registry.find(key)!!.link(settings, name, id)
		assertEquals("https://slack.com/app_redirect?channel=C1", link("slack", "umd-rise", "C1"))
		assertEquals("https://github.com/orgs/hack4impact/teams/umd-rise", link("github", "umd-rise", "123"))
		assertEquals("https://groups.google.com/a/umd.hack4impact.org/g/umd-rise", link("google", "umd-rise@umd.hack4impact.org", "g1"))
		assertEquals("https://www.notion.so/abc123", link("notion", "Page", "abc-123"))
		assertEquals("https://vault.example", link("vaultwarden", "x", "v1"))
		assertNull(link("documenso", "x", "d1"))
	}
}
