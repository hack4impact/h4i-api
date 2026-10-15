package org.hack4impact.portal.memberimport

import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.IMPORT_RUN
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID
import kotlin.system.exitProcess

@ConfigurationProperties("portal.import")
data class MemberImportProperties(
	val enabled: Boolean = false,
	val reportPath: Path = Path.of("build/import-report.md"),
	val memberDb: MemberDb = MemberDb(),
) {
	data class MemberDb(val url: String = "", val username: String = "", val password: String = "")
}

/**
 * Runs one member-DB import and exits. Start with the `import` profile:
 * `./gradlew bootRun --args='--spring.profiles.active=import'`.
 */
@Component
@ConditionalOnBooleanProperty("portal.import.enabled")
class MemberImportRunner(
	private val properties: MemberImportProperties,
	private val importer: MemberDbImporter,
	private val dsl: DSLContext,
	private val transactions: TransactionTemplate,
	private val json: JsonMapper,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		val exitCode = try {
			runImport()
			0
		} catch (e: Exception) {
			log.error("Member DB import failed", e)
			1
		}
		exitProcess(SpringApplication.exit(context, { exitCode }))
	}

	fun runImport(): ImportReport {
		require(properties.memberDb.url.isNotBlank()) { "Set portal.import.member-db.url (MEMBER_DB_URL)" }
		val runId = dsl.insertInto(IMPORT_RUN).defaultValues().returningResult(IMPORT_RUN.ID).fetchSingle().value1()!!
		try {
			val report = transactions.execute { importer.import(JdbcClient.create(readOnly(properties.memberDb))) }
			finish(runId, report)
			Files.createDirectories(properties.reportPath.toAbsolutePath().parent)
			Files.writeString(properties.reportPath, report.toMarkdown(runId))
			log.info("Member DB import {} finished: {} issues. Report: {}", runId, report.issues.size, properties.reportPath.toAbsolutePath())
			report.counts.forEach { (entity, c) -> log.info("  {}: {}", entity, c) }
			return report
		} catch (e: Exception) {
			dsl.update(IMPORT_RUN)
				.set(IMPORT_RUN.STATUS, "failed")
				.set(IMPORT_RUN.FINISHED_AT, OffsetDateTime.now())
				.set(IMPORT_RUN.ERROR, e.toString())
				.where(IMPORT_RUN.ID.eq(runId))
				.execute()
			throw e
		}
	}

	private fun finish(runId: UUID, report: ImportReport) {
		val counts = JSONB.valueOf(json.writeValueAsString(report.counts))
		dsl.update(IMPORT_RUN)
			.set(IMPORT_RUN.STATUS, "completed")
			.set(IMPORT_RUN.FINISHED_AT, OffsetDateTime.now())
			.set(IMPORT_RUN.COUNTS, counts)
			.set(IMPORT_RUN.ISSUES, JSONB.valueOf(json.writeValueAsString(report.issues)))
			.where(IMPORT_RUN.ID.eq(runId))
			.execute()
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "import")
			.set(AUDIT_EVENT.ACTION, "member_db.import")
			.set(AUDIT_EVENT.TARGET_TYPE, "import_run")
			.set(AUDIT_EVENT.TARGET_ID, runId)
			.set(AUDIT_EVENT.AFTER, counts)
			.execute()
	}

}

/** The member DB is never written to: connections are read-only at both the driver and the server. */
internal fun readOnly(db: MemberImportProperties.MemberDb) = DriverManagerDataSource(db.url, db.username, db.password).apply {
	setConnectionProperties(
		Properties().apply {
			setProperty("readOnly", "true")
			setProperty("options", "-c default_transaction_read_only=on")
		},
	)
}
