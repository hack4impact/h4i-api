package org.hack4impact.portal.preflight

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_QUESTION
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberdb.MemberDbPull
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.LocalDate.now
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The preflight against the fake member database with Khoa's changes: what it flags before go-live, and a chapter that's ready. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PreflightTests(@Autowired private val preflight: Preflight, @Autowired private val dsl: DSLContext, @Autowired private val pull: MemberDbPull) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val db = MemberDbFixture.writable
			registry.add("portal.member-db.url") { db.url }
			registry.add("portal.member-db.username") { db.username }
			registry.add("portal.member-db.password") { db.password }
			registry.add("portal.github-oauth.client-id") { "gh-client" }
			registry.add("portal.github-oauth.client-secret") { "gh-secret" }
		}
	}

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute()
		assertEquals("completed", pull.pull().status) // chapters, schools and terms come in
	}

	private fun List<Check>.of(area: String, name: String) = single { it.area == area && it.name == name }

	@Test
	fun `flags what isn't set up yet and reads the member database's grants and Khoa's changes`() {
		val checks = preflight.run()
		assertEquals(Check.Level.FAIL, checks.of("Portal", "Email").level) // log mode
		assertEquals(Check.Level.FAIL, checks.of("Portal", "National admins").level)
		assertEquals(Check.Level.OK, checks.of("Portal", "GitHub sign-in").level)
		assertEquals(Check.Level.OK, checks.of("Member database", "Connection").level)
		assertEquals(Check.Level.OK, checks.of("Member database", "Change check").level)
		assertEquals(Check.Level.OK, checks.of("Member database", "Read grants").level)
		assertEquals(Check.Level.OK, checks.of("Member database", "Khoa's changes").level, checks.of("Member database", "Khoa's changes").detail)
		assertEquals(Check.Level.WARN, checks.of("Member database", "No delete grant").level) // the test login owns the tables
		assertEquals(Check.Level.WARN, checks.of("Google", "Accounts").level) // not creating accounts
		assertEquals(Check.Level.WARN, checks.of("Documenso", "Agreements").level)
		val umd = checks.of("Chapter", "umd")
		assertEquals(Check.Level.FAIL, umd.level)
		assertTrue("no school email domains" in umd.detail && "no lead or co-lead" in umd.detail, umd.detail)
	}

	@Test
	fun `a chapter with domains, a lead, questions and terms is ready`() {
		val umd = dsl.selectFrom(CHAPTER).where(CHAPTER.CODE.eq("umd")).fetchSingle()
		dsl.update(INSTITUTION).set(INSTITUTION.EMAIL_DOMAINS, arrayOf("umd.edu")).where(INSTITUTION.ID.eq(umd.institutionId)).execute()
		dsl.update(CHAPTER).set(CHAPTER.GOOGLE_DOMAIN, "umd.hack4impact.org").set(CHAPTER.GOOGLE_ORG_UNIT, "/Chapters/UMD").where(CHAPTER.ID.eq(umd.id)).execute()
		val lena = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Lena").set(PERSON.LAST_NAME, "Lead").set(PERSON.STATUS, "active").returningResult(PERSON.ID).fetchSingle().value1()
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, lena).set(CHAPTER_ROLE.CHAPTER_ID, umd.id).set(CHAPTER_ROLE.ROLE, "lead").execute()
		dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, lena).execute()
		dsl.insertInto(CHAPTER_QUESTION).set(CHAPTER_QUESTION.CHAPTER_ID, umd.id).set(CHAPTER_QUESTION.POSITION, 1).set(CHAPTER_QUESTION.LABEL, "Why?").execute()

		assertTrue("no academic terms" in preflight.run().of("Chapter", "umd").detail) // the seed's terms end this year at the latest (Q51)
		val year = now().year + 1
		dsl.insertInto(TERM).set(TERM.INSTITUTION_ID, umd.institutionId).set(TERM.SEASON, "spring").set(TERM.YEAR, year.toShort())
			.set(TERM.STARTS_ON, LocalDate.of(year, 1, 20)).set(TERM.ENDS_ON, LocalDate.of(year, 5, 20)).execute()

		val checks = preflight.run()
		assertEquals(Check.Level.OK, checks.of("Chapter", "umd").level, checks.of("Chapter", "umd").detail)
		assertEquals(Check.Level.OK, checks.of("Portal", "National admins").level)
	}
}
