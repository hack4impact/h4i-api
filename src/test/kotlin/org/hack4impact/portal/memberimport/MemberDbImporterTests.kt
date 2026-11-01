package org.hack4impact.portal.memberimport

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.IMPORT_RUN
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MemberDbImporterTests(
	@Autowired private val importer: MemberDbImporter,
	@Autowired private val dsl: DSLContext,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val json: JsonMapper,
	@Autowired private val context: ConfigurableApplicationContext,
) {
	private fun volunteer(n: Int) = UUID.fromString("50000000-0000-0000-0000-%012d".format(n))

	private fun runImport(): ImportReport =
		transactions.execute { importer.import(JdbcClient.create(readOnly(MemberDbFixture.settings))) }

	private fun person(volunteer: Int) = dsl.selectFrom(PERSON).where(PERSON.SOURCE_ID.eq(volunteer(volunteer))).fetchOne()

	@BeforeEach
	fun emptyPortal() = dsl.emptyPortalTables()

	@Test
	fun `imports people with mapped statuses and skips soft-deleted ones`() {
		val report = runImport()

		assertEquals(11, report.counts("person").inserted)
		assertEquals(1, report.counts("person").skipped)
		assertNull(person(7))
		assertEquals("active", person(1)!!.status)
		assertEquals("alumni", person(2)!!.status) // type alumni
		assertEquals("alumni", person(3)!!.status) // inactive
		assertEquals("alumni", person(4)!!.status) // hiatus
		assertEquals("removed", person(5)!!.status) // suspended
		assertEquals("alumni", person(6)!!.status) // community, until Q17 is decided
		assertEquals("community", person(6)!!.kind)
		assertEquals("inactive/student", person(3)!!.sourceStatus)
	}

	@Test
	fun `normalizes emails and picks the newest school record`() {
		val report = runImport()

		val ada = person(1)!!
		assertEquals("ada@personal.test", ada.personalEmail)
		assertEquals("ada@umd.edu", ada.schoolEmail)
		assertEquals("ada@hack4impact.org", ada.orgEmail)
		val spring2026 = dsl.select(TERM.ID).from(TERM).where(TERM.SOURCE_ID.eq(UUID.fromString("30000000-0000-0000-0000-000000000002"))).fetchSingle().value1()
		assertEquals(spring2026, ada.graduationTermId)
		assertTrue(report.issues.any { it.kind == "needs review" && "school records" in it.message })
	}

	@Test
	fun `reports duplicates and people who can't be claimed instead of merging them`() {
		val report = runImport()

		assertTrue(person(9) != null && person(10) != null)
		assertTrue(report.issues.any { it.kind == "possible duplicate" && it.message.startsWith("same@personal.test") })
		assertTrue(report.issues.any { it.sourceId == volunteer(8).toString() && "no email" in it.message })
		assertTrue(report.issues.any { it.sourceId == volunteer(11).toString() && "chapter that wasn't imported" in it.message })
	}

	@Test
	fun `trims padded chapter codes and skips invalid or deleted chapters`() {
		val report = runImport()

		assertEquals(setOf("umd", "gt"), dsl.select(CHAPTER.CODE).from(CHAPTER).fetchSet(CHAPTER.CODE))
		assertTrue(report.issues.any { "bad code!" in it.message })
		// UMD: Ada, Alan, Grace, Lee. GT: Hal, Sam, Cora and both Danas. Otto's chapter was deleted; Nia has none.
		assertEquals(9, dsl.fetchCount(CHAPTER_MEMBERSHIP))
	}

	@Test
	fun `keeps stable tool IDs apart from logins and rejects conflicting accounts`() {
		val report = runImport()

		val accounts = dsl.selectFrom(TOOL_ACCOUNT).fetch()
		val github = accounts.filter { it.tool == "github" }
		assertEquals(setOf("1234567", "7654321"), github.mapNotNull { it.externalId }.toSet()) // two accounts, both kept
		assertEquals(setOf("alan-dev", "grace-gh"), github.mapNotNull { it.externalLogin }.toSet()) // URLs reduced to usernames
		assertEquals("cora@hack4impact.org", accounts.single { it.tool == "google" }.externalLogin)
		assertEquals(1, accounts.count { it.externalId == "U0ADA" })
		val ada = person(1)!!.id
		assertEquals(setOf("U0ADA", "U0ADA2"), accounts.filter { it.personId == ada && it.tool == "slack" }.map { it.externalId }.toSet())
		assertTrue(accounts.all { it.state == "unverified" })
		assertTrue(report.issues.any { "U0ADA" in it.message && "also linked" in it.message })
		assertTrue(report.issues.any { "isn't a GitHub profile" in it.message })
		assertTrue(report.issues.any { it.kind == "not imported" && "linkedin" in it.message })
	}

	@Test
	fun `imports partners and project roles to pick from, but not projects or their teams`() {
		val report = runImport()

		assertEquals(setOf("RISE DC", "Food Bank"), dsl.select(PARTNER.NAME).from(PARTNER).fetchSet(PARTNER.NAME)) // Gone Partner was deleted
		assertEquals(1, report.counts("partner").skipped)
		val roles = dsl.selectFrom(PROJECT_ROLE).fetch().associate { it.name!! to it.isLead!! }
		assertEquals(mapOf("Engineer" to false, "Tech Lead" to true), roles)
		assertTrue(dsl.selectFrom(PROJECT_ROLE).fetch().all { it.sourceId != null })
		assertEquals(0, dsl.fetchCount(PROJECT))
		assertEquals(0, dsl.fetchCount(PROJECT_MEMBER))
	}

	@Test
	fun `a partner renamed in the portal keeps its name`() {
		runImport()
		val rise = UUID.fromString("40000000-0000-0000-0000-000000000001")
		dsl.update(PARTNER).set(PARTNER.NAME, "RISE D.C.").where(PARTNER.SOURCE_ID.eq(rise)).execute()

		val second = runImport()

		assertEquals("RISE D.C.", dsl.select(PARTNER.NAME).from(PARTNER).where(PARTNER.SOURCE_ID.eq(rise)).fetchSingle().value1())
		assertEquals(1, second.counts("partner").kept)
	}

	@Test
	fun `a partner deleted in the member database after its import is deleted here, and restored with it`() {
		MemberDbFixture.resetWritable()
		val source = JdbcClient.create(readOnly(MemberDbFixture.writable))
		val memberDb = JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(MemberDbFixture.writable.url, MemberDbFixture.writable.username, MemberDbFixture.writable.password))
		fun import() = transactions.execute { importer.import(source) }!!
		val rise = UUID.fromString("40000000-0000-0000-0000-000000000001")
		fun deletedAt() = dsl.select(PARTNER.DELETED_AT).from(PARTNER).where(PARTNER.SOURCE_ID.eq(rise)).fetchSingle().value1()
		import()
		assertNull(deletedAt())

		memberDb.sql("UPDATE partners SET deleted_at = '2026-11-01T12:00:00Z' WHERE id = ?").param(rise).update()
		import()
		val deleted = deletedAt()
		assertEquals(OffsetDateTime.parse("2026-11-01T12:00:00Z").toInstant(), deleted?.toInstant())
		import()
		assertEquals(deleted, deletedAt()) // already deleted: left as it is

		memberDb.sql("UPDATE partners SET deleted_at = NULL WHERE id = ?").param(rise).update()
		import()
		assertNull(deletedAt()) // the deletion wasn't an edit in the portal, so the import may undo it
	}

	@Test
	fun `a partner whose name differs only in case from one already here is skipped and reported`() {
		dsl.insertInto(PARTNER).set(PARTNER.NAME, "Rise dc").execute() // added by national before the import, no source_id

		val report = runImport()

		with(report.counts("partner")) {
			assertEquals(1, inserted) // Food Bank
			assertEquals(2, skipped) // RISE DC refused, Gone Partner deleted
		}
		assertTrue(report.issues.any { it.entity == "partner" && it.sourceId == "40000000-0000-0000-0000-000000000001" && "rejected by the portal schema" in it.message })
		assertEquals(listOf("Rise dc"), dsl.select(PARTNER.NAME).from(PARTNER).where(org.jooq.impl.DSL.lower(PARTNER.NAME).eq("rise dc")).fetch(PARTNER.NAME))
	}

	@Test
	fun `running twice changes nothing new`() {
		runImport()
		val second = runImport()

		assertTrue(second.counts.values.all { it.inserted == 0 })
		assertEquals(11, dsl.fetchCount(PERSON))
	}

	@Test
	fun `never overwrites what was edited or claimed in the portal`() {
		runImport()
		dsl.update(PERSON).set(PERSON.PREFERRED_NAME, "Addie").where(PERSON.SOURCE_ID.eq(volunteer(1))).execute()
		dsl.update(PERSON).set(PERSON.CLAIMED_AT, OffsetDateTime.now()).where(PERSON.SOURCE_ID.eq(volunteer(2))).execute()
		dsl.update(PERSON).set(PERSON.STATUS, "active").where(PERSON.SOURCE_ID.eq(volunteer(2))).execute()

		val second = runImport()

		assertEquals("Addie", person(1)!!.preferredName)
		assertEquals("active", person(2)!!.status)
		assertEquals(2, second.counts("person").kept)
	}

	@Test
	fun `never writes to the member DB`() {
		val source = JdbcClient.create(readOnly(MemberDbFixture.settings))
		val error = assertFails { source.sql("UPDATE volunteers SET first_name = 'X'").update() }
		assertContains(error.toString(), "read-only")
	}

	@Test
	fun `the runner records the run, audits it and writes a report`(@TempDir dir: Path) {
		val reportPath = dir.resolve("import-report.md")
		val runner = MemberImportRunner(
			MemberImportProperties(enabled = true, reportPath = reportPath, memberDb = MemberDbFixture.settings),
			importer, dsl, transactions, json, context,
		)

		runner.runImport()

		val run = dsl.selectFrom(IMPORT_RUN).fetchSingle()
		assertEquals("completed", run.status)
		assertContains(run.counts!!.data(), "person")
		assertEquals(1, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.TARGET_ID.eq(run.id)))
		val markdown = Files.readString(reportPath)
		assertContains(markdown, "| person | 12 | 11 |")
		assertContains(markdown, "possible duplicate")
	}
}
