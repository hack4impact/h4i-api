package org.hack4impact.portal

import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource
import kotlin.test.assertEquals

/** Each stage's migrations apply on a database the previous stage made and filled, as production will be. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MigrationTests(@Autowired private val dataSource: DataSource, @Autowired private val dsl: DSLContext) {
	private val schema = "upgrade_test"
	private val jdbc = JdbcClient.create(dataSource)

	private fun flyway(target: String) =
		Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").target(target).load()

	@AfterEach
	fun drop() {
		jdbc.sql("DROP SCHEMA IF EXISTS $schema CASCADE").update()
	}

	@Test
	fun `stage 2's V4 applies on a stage 1 database with members in it`() {
		flyway("3").migrate()
		jdbc.sql(
			"""
			INSERT INTO $schema.chapter (id, code, name) VALUES ('20000000-0000-0000-0000-000000000001', 'umd', 'Hack4Impact UMD');
			INSERT INTO $schema.person (id, first_name, last_name, status) VALUES ('50000000-0000-0000-0000-000000000001', 'Ada', 'Lovelace', 'active');
			INSERT INTO $schema.chapter_membership (person_id, chapter_id) VALUES ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001');
			INSERT INTO $schema.chapter_role (person_id, chapter_id, role) VALUES ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'lead');
			""".trimIndent(),
		).update()

		flyway("latest").migrate()

		assertEquals(1, count("person"))
		assertEquals(1, count("chapter_role"))
		assertEquals(4, count("resource_template"))
		assertEquals(0, count("tool_setting")) // the app's bootstrap adds them (stage 2 package 2)
		jdbc.sql(
			"""
			INSERT INTO $schema.project (chapter_id, name, slug) VALUES ('20000000-0000-0000-0000-000000000001', 'RISE DC', 'rise-dc')
			""".trimIndent(),
		).update()
		assertEquals(1, count("project"))
	}

	@Test
	fun `the test helper puts back exactly the template the migration seeds`() {
		flyway("latest").migrate()
		dsl.emptyPortalTables()

		assertEquals(template(schema), template("portal"))
		assertEquals(listOf("slack", "google", "github", "vaultwarden"), template(schema).map { it!!.substringBefore("|") })
	}

	/** Every template row as one string, in order: what the migration seeded, or what `emptyPortalTables` put back. */
	private fun template(schema: String) = jdbc.sql(
		"""
		SELECT concat_ws('|', tool, position, name_pattern, audience, access_level, requires_agreement, tags::text, chapter_id::text)
		FROM $schema.resource_template ORDER BY position
		""".trimIndent(),
	).query(String::class.java).list()

	private fun count(table: String) = jdbc.sql("SELECT count(*) FROM $schema.$table").query(Int::class.java).single()
}
