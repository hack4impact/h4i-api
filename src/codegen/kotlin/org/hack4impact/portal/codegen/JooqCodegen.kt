package org.hack4impact.portal.codegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** Usage: JooqCodegen <migrations dir> <output dir>. Run through `./gradlew generateJooq`. */
fun main(args: Array<String>) {
	val (migrations, output) = args
	PostgreSQLContainer(DockerImageName.parse("postgres:17")).use { pg ->
		pg.start()
		Flyway.configure()
			.dataSource(pg.jdbcUrl, pg.username, pg.password)
			.schemas("portal")
			.locations("filesystem:$migrations")
			.load()
			.migrate()
		GenerationTool.generate(
			Configuration()
				.withJdbc(
					Jdbc()
						.withDriver("org.postgresql.Driver")
						.withUrl(pg.jdbcUrl)
						.withUser(pg.username)
						.withPassword(pg.password),
				)
				.withGenerator(
					Generator()
						.withName("org.jooq.codegen.KotlinGenerator")
						.withDatabase(
							Database()
								.withName("org.jooq.meta.postgres.PostgresDatabase")
								.withInputSchema("portal")
								.withExcludes("flyway_schema_history|set_updated_at"),
						)
						.withGenerate(Generate().withRecords(true).withPojos(false).withDaos(false))
						.withTarget(Target().withPackageName("org.hack4impact.portal.db").withDirectory(output)),
				),
		)
	}
}
