package org.hack4impact.portal.preflight

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.FakeTools
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.assertEquals

/**
 * Stage 2's preflight checks (package 8) with the member database's write switch on and two fake tools: each prints
 * FAIL against what isn't set up, and OK against what is.
 */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
class PreflightStage2Tests(@Autowired private val preflight: Preflight, @Autowired private val dsl: DSLContext, @Autowired private val slack: InMemoryReadAdapter) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val db = MemberDbFixture.writable
			registry.add("portal.member-db.url") { db.url }
			registry.add("portal.member-db.username") { db.username }
			registry.add("portal.member-db.password") { db.password }
			registry.add("portal.member-db.write") { "true" }
		}
	}

	private fun List<Check>.of(area: String, name: String) = single { it.area == area && it.name == name }

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute()
		slack.failure = null
	}

	@Test
	fun `the member database's stage 2 changes pass with Khoa's script and fail without them`() {
		assertEquals(Check.Level.OK, preflight.run().of("Member database", "Stage 2 changes").level)
		val db = MemberDbFixture.writable
		JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(db.url, db.username, db.password))
			.sql("ALTER TABLE leadership_assignments DROP COLUMN chapter_id CASCADE").update()
		assertEquals(Check.Level.FAIL, preflight.run().of("Member database", "Stage 2 changes").level)
	}

	@Test
	fun `a tool that can be read passes, one that can't fails, and a tool with no adapter is reported not synced`() {
		val checks = preflight.run()
		assertEquals(Check.Level.OK, checks.of("Sync", "Slack").level, checks.of("Sync", "Slack").detail)
		assertEquals(Check.Level.OK, checks.of("Sync", "Slack switches").level) // enabled, in dry run
		assertEquals(Check.Level.WARN, checks.of("Sync", "Slack permissions").level) // added with the Slack adapter item
		assertEquals(Check.Level.WARN, checks.of("Sync", "Google").level) // no adapter here
		slack.failure = Unavailable(Tool.SLACK, "down")
		assertEquals(Check.Level.FAIL, preflight.run().of("Sync", "Slack").level)
	}
}
