package org.hack4impact.portal.memberdb

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_DRIFT
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The member database as an integration (wiki: staged-rollout, decision 162), against the fake member database with
 * the changes Khoa agreed to: what the portal writes for a new member and for a claim, conflicts and retries, dry run
 * and pause, and the pull with drift.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MemberDbIntegrationTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val writer: MemberDbWriter,
	@Autowired private val pull: MemberDbPull,
	@Autowired private val writes: MemberDbWrites,
) {
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

		const val ADA = "50000000-0000-0000-0000-000000000001"
		const val ALAN = "50000000-0000-0000-0000-000000000002"
		const val UMD = "20000000-0000-0000-0000-000000000001"
		const val FALL_2025 = "30000000-0000-0000-0000-000000000001"
	}

	private val memberDb by lazy { MemberDbFixture.writable.let { JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(it.url, it.username, it.password)) } }

	private fun one(sql: String, vararg args: Any): Map<String, Any?> = memberDb.sql(sql).params(args.toList()).query().singleRow()

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun writesOn() = dsl.update(MEMBER_DB_STATE).set(MEMBER_DB_STATE.DRY_RUN, false).set(MEMBER_DB_STATE.PAUSED, false).execute()

	private fun state(person: UUID) = dsl.selectFrom(MEMBER_DB_WRITE).where(MEMBER_DB_WRITE.PERSON_ID.eq(person)).fetchSingle()

	/** Someone who joined through the portal: approved, with their school profile, a verified GitHub account and a new H4I address. */
	private fun newMember(first: String = "Nia"): UUID {
		val umd = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq("umd")).fetchSingle().value1()!!
		val term = dsl.select(TERM.ID).from(TERM).where(TERM.SOURCE_ID.eq(UUID.fromString(FALL_2025))).fetchSingle().value1()
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "New").set(PERSON.STATUS, "active")
			.set(PERSON.PERSONAL_EMAIL, "${first.lowercase()}@personal.test").set(PERSON.SCHOOL_EMAIL, "${first.lowercase()}@umd.edu")
			.set(PERSON.ORG_EMAIL, "${first.lowercase()}@umd.hack4impact.org").set(PERSON.MAJOR, "Computer Science").set(PERSON.DEGREE_TYPE, "Bachelor's")
			.set(PERSON.GRADUATION_SEASON, "fall").set(PERSON.GRADUATION_YEAR, 2025.toShort()).set(PERSON.GRADUATION_TERM_ID, term)
			.set(PERSON.CLAIMED_AT, OffsetDateTime.now())
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, person).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_ID, "999")
			.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "nia-gh").set(TOOL_ACCOUNT.STATE, "confirmed").execute()
		writes.request(person)
		return person
	}

	private fun imported(volunteer: String) = dsl.select(PERSON.ID).from(PERSON).where(PERSON.SOURCE_ID.eq(UUID.fromString(volunteer))).fetchSingle().value1()!!

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute() // dry run, as the migration leaves it
		assertEquals("completed", pull.pull().status) // chapters, terms and the seeded volunteers come in
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
	}

	@Test
	fun `nothing is written in dry run, then a new member becomes a volunteer with their profile and accounts`() {
		val nia = newMember()
		assertEquals(0, writer.runDue())
		assertEquals("pending", state(nia).state)
		mvc.get("/api/national/member-db/writes/$nia/preview") { with(signedIn("nora@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.newVolunteer") { value(true) }
			jsonPath("$.fields['volunteers.email']") { value("nia@personal.test") } // the personal address, when there is one (Q50)
			jsonPath("$.fields['academic_profiles.graduation_term_id']") { value(FALL_2025) }
		}

		writesOn()
		assertEquals(1, writer.runDue())
		val v = one("SELECT * FROM volunteers WHERE id = ?", nia) // the volunteer shares the portal's ID
		assertEquals("Nia", v["first_name"])
		assertEquals("nia@personal.test", v["email"])
		assertEquals("nia@umd.hack4impact.org", v["org_email"])
		assertEquals(UUID.fromString(UMD), v["chapter_id"])
		assertEquals("active", v["status"])
		assertEquals("student", v["type"])
		val profile = one("SELECT * FROM academic_profiles WHERE volunteer_id = ?", nia)
		assertEquals("Computer Science", profile["major"])
		assertEquals("Bachelor's", profile["degree_type"])
		assertEquals("nia@umd.edu", profile["email"])
		assertEquals(UUID.fromString(FALL_2025), profile["graduation_term_id"])
		assertEquals(
			setOf("github:999", "google:nia@umd.hack4impact.org"),
			memberDb.sql("SELECT provider || ':' || external_id FROM volunteer_accounts WHERE volunteer_id = ?").param(nia).query(String::class.java).set(),
		)
		assertEquals(nia, dsl.select(PERSON.SOURCE_ID).from(PERSON).where(PERSON.ID.eq(nia)).fetchSingle().value1())
		with(state(nia)) {
			assertEquals("done", state)
			assertNotNull(writtenAt)
		}

		// Written again (a later change): an update, not a second volunteer or profile.
		writes.request(nia)
		assertEquals(1, writer.runDue())
		assertEquals(1L, one("SELECT count(*) AS n FROM academic_profiles WHERE volunteer_id = ?", nia)["n"])
		assertEquals(1L, one("SELECT count(*) AS n FROM volunteers WHERE id = ?", nia)["n"])
	}

	@Test
	fun `a claim updates the imported volunteer and replaces their GitHub username with the verified ID`() {
		writesOn()
		val alan = imported(ALAN)
		dsl.update(PERSON).set(PERSON.STATUS, "active").set(PERSON.CLAIMED_AT, OffsetDateTime.now()).set(PERSON.MAJOR, "Mathematics").set(PERSON.DEGREE_TYPE, "Master's")
			.where(PERSON.ID.eq(alan)).execute()
		dsl.update(TOOL_ACCOUNT).set(TOOL_ACCOUNT.EXTERNAL_ID, "4242").set(TOOL_ACCOUNT.STATE, "confirmed")
			.where(TOOL_ACCOUNT.PERSON_ID.eq(alan), TOOL_ACCOUNT.TOOL.eq("github")).execute()
		writes.request(alan)
		assertEquals(1, writer.runDue())

		val v = one("SELECT status, type FROM volunteers WHERE id = ?::uuid", ALAN)
		assertEquals("active", v["status"])
		assertEquals("student", v["type"])
		assertEquals(listOf("4242"), memberDb.sql("SELECT external_id FROM volunteer_accounts WHERE volunteer_id = ?::uuid AND provider = 'github'").param(ALAN).query(String::class.java).list())
		val profile = one("SELECT id, degree_type FROM academic_profiles WHERE volunteer_id = ?::uuid", ALAN) // their existing profile, updated
		assertEquals(UUID.fromString("60000000-0000-0000-0000-000000000003"), profile["id"])
		assertEquals("Master's", profile["degree_type"])
	}

	@Test
	fun `an account linked to someone else stops the write for national, and a failure that may pass is tried again later`() {
		writesOn()
		val alan = imported(ALAN)
		dsl.update(PERSON).set(PERSON.CLAIMED_AT, OffsetDateTime.now()).where(PERSON.ID.eq(alan)).execute()
		// Ada's GitHub ID in the member database, which the portal doesn't have for her (it would refuse the link itself).
		dsl.deleteFrom(TOOL_ACCOUNT).where(TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.eq("1234567")).execute()
		dsl.update(TOOL_ACCOUNT).set(TOOL_ACCOUNT.EXTERNAL_ID, "1234567").set(TOOL_ACCOUNT.STATE, "confirmed")
			.where(TOOL_ACCOUNT.PERSON_ID.eq(alan), TOOL_ACCOUNT.TOOL.eq("github")).execute()
		writes.request(alan)
		assertEquals(0, writer.runDue())
		with(state(alan)) {
			assertEquals("failed", state) // retrying won't help
			assertTrue("linked to another volunteer" in lastError!!, lastError)
		}
		assertEquals("Alan", one("SELECT first_name FROM volunteers WHERE id = ?::uuid", ALAN)["first_name"]) // nothing half-written

		mvc.post("/api/national/member-db/writes/$alan/retry") { with(signedIn("nora@hack4impact.org")); with(csrf()) }.andExpect {
			status { isOk() }
			jsonPath("$.waiting[0].name") { value("Alan Alumnus") }
		}

		val nia = newMember("N".repeat(200)) // longer than the member database allows: an error that isn't a conflict
		dsl.update(MEMBER_DB_WRITE).setNull(MEMBER_DB_WRITE.NEXT_TRY_AT).where(MEMBER_DB_WRITE.PERSON_ID.eq(alan)).execute()
		writer.runDue()
		with(state(nia)) {
			assertEquals("pending", state)
			assertEquals(1, attempts)
			assertTrue(nextTryAt!!.isAfter(OffsetDateTime.now()))
		}
	}

	@Test
	fun `the pull brings in what the member database owns and reports changes there to what the portal owns`() {
		writesOn()
		val nia = newMember()
		writer.runDue()
		assertEquals("completed", pull.pull().status) // the write itself changed the member database
		assertEquals("unchanged", pull.pull().status) // nothing since

		memberDb.sql("UPDATE volunteers SET first_name = 'Changed' WHERE id = ?").param(nia).update()
		memberDb.sql("INSERT INTO volunteer_accounts (volunteer_id, provider, external_id) VALUES (?, 'slack', 'U0NIA')").param(nia).update()
		val pulled = pull.pull()
		assertEquals("completed", pulled.status)
		assertEquals(1, pulled.drift)
		val drift = dsl.selectFrom(MEMBER_DB_DRIFT).fetchSingle()
		assertEquals("volunteers.first_name", drift.field)
		assertEquals("Nia", drift.portalValue)
		assertEquals("Changed", drift.memberDbValue)
		assertEquals("Nia", dsl.select(PERSON.FIRST_NAME).from(PERSON).where(PERSON.ID.eq(nia)).fetchSingle().value1()) // reported, not taken (Q49)
		assertTrue(dsl.fetchExists(TOOL_ACCOUNT, TOOL_ACCOUNT.PERSON_ID.eq(nia), TOOL_ACCOUNT.TOOL.eq("slack"), TOOL_ACCOUNT.EXTERNAL_ID.eq("U0NIA"))) // the member database's

		mvc.get("/api/national/member-db") { with(signedIn("nora@hack4impact.org")) }.andExpect {
			jsonPath("$.drift[0].field") { value("volunteers.first_name") }
			jsonPath("$.drift[0].memberDbValue") { value("Changed") }
		}
		memberDb.sql("UPDATE volunteers SET first_name = 'Nia' WHERE id = ?").param(nia).update()
		assertEquals(0, pull.pull().drift)
		assertEquals(0, dsl.fetchCount(MEMBER_DB_DRIFT)) // agree again: cleared
	}

	@Test
	fun `a volunteer soft-deleted in the member database is reported, and the portal keeps them until national decides`() {
		val ada = imported(ADA)
		memberDb.sql("UPDATE volunteers SET deleted_at = '2026-10-15T12:00:00Z' WHERE id = ?::uuid").param(ADA).update()
		val pulled = pull.pull()
		assertEquals(1, pulled.drift)
		with(dsl.selectFrom(MEMBER_DB_DRIFT).fetchSingle()) {
			assertEquals(ada, personId)
			assertEquals(MemberDbPull.DELETED, field)
			assertTrue(memberDbValue!!.startsWith("deleted on 2026-10-1"), memberDbValue)
		}
		assertTrue(dsl.fetchExists(PERSON, PERSON.ID.eq(ada), PERSON.STATUS.ne("removed"))) // reported, not acted on

		memberDb.sql("UPDATE volunteers SET deleted_at = NULL WHERE id = ?::uuid").param(ADA).update() // restored there
		assertEquals(0, pull.pull().drift)
		assertEquals(0, dsl.fetchCount(MEMBER_DB_DRIFT))

		memberDb.sql("UPDATE volunteers SET deleted_at = now() WHERE id = ?::uuid").param(ADA).update()
		dsl.update(PERSON).set(PERSON.STATUS, "removed").where(PERSON.ID.eq(ada)).execute() // national removed them here too
		assertEquals(0, pull.pull().drift)
	}

	@Test
	fun `national pauses writes with a reason, only national reaches these controls`() {
		val lena = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Lena").set(PERSON.LAST_NAME, "Lead").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "lena@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		val umd = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq("umd")).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, lena).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute()
		mvc.get("/api/national/member-db") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }

		val change = { body: String -> mvc.put("/api/national/member-db") { with(signedIn("nora@hack4impact.org")); with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body } }
		change("""{"dryRun":false,"paused":false}""").andExpect { status { isOk() }; jsonPath("$.dryRun") { value(false) } }
		change("""{"dryRun":false,"paused":true}""").andExpect { status { isBadRequest() } } // why?
		change("""{"dryRun":false,"paused":true,"reason":"Khoa is migrating the member database"}""").andExpect { jsonPath("$.paused") { value(true) } }
		newMember()
		assertEquals(0, writer.runDue())
		assertNull(dsl.select(MEMBER_DB_STATE.LAST_PULL_ERROR).from(MEMBER_DB_STATE).fetchSingle().value1())
	}
}
