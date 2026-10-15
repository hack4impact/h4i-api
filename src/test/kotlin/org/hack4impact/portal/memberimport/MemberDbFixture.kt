package org.hack4impact.portal.memberimport

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** A fake national member DB (schema recreated from the real export, fake rows), started once per test run. */
object MemberDbFixture {
	val container: PostgreSQLContainer by lazy {
		PostgreSQLContainer(DockerImageName.parse("postgres:17")).also {
			it.start()
			ResourceDatabasePopulator(ClassPathResource("memberdb/schema.sql"), ClassPathResource("memberdb/seed.sql"))
				.execute(DriverManagerDataSource(it.jdbcUrl, it.username, it.password))
		}
	}

	val settings get() = MemberImportProperties.MemberDb(container.jdbcUrl, container.username, container.password)

	/** A second database in the same container, for tests that write to the member database (the importer's stays as seeded). */
	val writable: MemberImportProperties.MemberDb by lazy {
		DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
			val exists = c.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname = 'memberdb_writable'").next()
			if (!exists) c.createStatement().execute("CREATE DATABASE memberdb_writable")
		}
		MemberImportProperties.MemberDb(container.jdbcUrl.replace(Regex("/[^/?]+(\\?|$)"), "/memberdb_writable$1"), container.username, container.password)
			.also(::reset)
	}

	/**
	 * Puts [writable] back as seeded, with the changes Khoa agreed to (`scripts/member-db-changes.sql`) applied: first
	 * the duplicate accounts resolved by hand, as the script asks, then the script itself.
	 */
	fun resetWritable() = reset(writable)

	private fun reset(db: MemberImportProperties.MemberDb) {
		DriverManager.getConnection(db.url, db.username, db.password).use { c ->
			c.createStatement().use { st ->
				st.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
				st.execute(ClassPathResource("memberdb/schema.sql").getContentAsString(Charsets.UTF_8))
				st.execute(ClassPathResource("memberdb/seed.sql").getContentAsString(Charsets.UTF_8))
				// The manual step: one of each repeated row, and the Slack ID two people had stays with the first.
				st.execute(
					"""
					DELETE FROM volunteer_accounts a USING volunteer_accounts b WHERE a.id = b.id AND a.ctid > b.ctid;
					DELETE FROM volunteer_accounts a USING volunteer_accounts b
					  WHERE a.provider = b.provider AND a.external_id = b.external_id AND a.id > b.id;
					""".trimIndent(),
				)
				st.execute(Files.readString(Path.of("scripts/member-db-changes.sql")))
			}
		}
	}
}
