package org.hack4impact.portal.preflight

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
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
import kotlin.test.assertTrue

/**
 * Package 8 review R1: stage 2's member-database checks against what production may have before Khoa's stage 2 run, a
 * `portal_sync`-like login with only stage 1's grants and the original `projects.status_valid`, with the write switch on.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
class PreflightGrantsTests(@Autowired private val preflight: Preflight, @Autowired private val dsl: DSLContext) {
	companion object {
		const val LOGIN = "stage1_only"

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val db = MemberDbFixture.writable
			registry.add("portal.member-db.url") { db.url }
			registry.add("portal.member-db.username") { LOGIN }
			registry.add("portal.member-db.password") { LOGIN }
			registry.add("portal.member-db.write") { "true" }
		}
	}

	/** As the owner of the fake member database. */
	private val owner by lazy { MemberDbFixture.writable.let { JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(it.url, it.username, it.password)) } }

	private fun List<Check>.of(area: String, name: String) = single { it.area == area && it.name == name }

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute()
		// Stage 1's suggested grants (scripts/member-db-changes.sql, part 4a), for a login that isn't the tables' owner.
		owner.sql(
			"""
			DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$LOGIN') THEN CREATE ROLE $LOGIN LOGIN PASSWORD '$LOGIN'; END IF; END $$;
			GRANT USAGE ON SCHEMA public TO $LOGIN;
			GRANT SELECT ON ALL TABLES IN SCHEMA public TO $LOGIN;
			GRANT INSERT, UPDATE ON volunteers, academic_profiles, volunteer_accounts TO $LOGIN;
			""".trimIndent(),
		).update()
	}

	@Test
	fun `without the stage 2 grants and with the original projects check, both stage 2 checks fail`() {
		owner.sql(
			"""
			ALTER TABLE projects DROP CONSTRAINT status_valid;
			ALTER TABLE projects ADD CONSTRAINT status_valid CHECK (((status)::text = ANY (ARRAY['draft::text'::text, 'approved::text'::text, 'active'::text, 'inactive'::text, 'suspended'::text, 'completed'::text]))) NOT VALID;
			""".trimIndent(),
		).update()
		val checks = preflight.run()
		val grants = checks.of("Member database", "Stage 2 grants")
		assertEquals(Check.Level.FAIL, grants.level)
		assertTrue("INSERT, UPDATE on projects" in grants.detail && "INSERT, UPDATE on leadership_assignments" in grants.detail, grants.detail)
		val changes = checks.of("Member database", "Stage 2 changes")
		assertEquals(Check.Level.FAIL, changes.level)
		assertTrue("the projects.status_valid fix" in changes.detail, changes.detail)
		assertEquals(Check.Level.OK, checks.of("Member database", "Read grants").level) // stage 1's SELECT covers the pull's partners and project_roles
	}
}
