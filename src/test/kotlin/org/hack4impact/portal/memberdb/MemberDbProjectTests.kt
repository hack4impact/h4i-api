package org.hack4impact.portal.memberdb

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_LEADERSHIP_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_PROJECT_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.hack4impact.portal.projects.MemberInput
import org.hack4impact.portal.projects.MemberRoleInput
import org.hack4impact.portal.projects.ProjectInput
import org.hack4impact.portal.projects.ProjectService
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
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2 package 7: projects, their teams and chapter leads written to the fake member database, with Khoa's script
 * (stage 2 part included) applied: what package 7 must show on the stage 2 page.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false", "portal.agreements.on-request=false"])
@AutoConfigureMockMvc
class MemberDbProjectTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val projects: ProjectService,
	@Autowired private val writer: MemberDbProjectWriter,
	@Autowired private val writes: MemberDbWrites,
	@Autowired private val pull: MemberDbPull,
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

		val ADA: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")
		val ALAN: UUID = UUID.fromString("50000000-0000-0000-0000-000000000002")
		val UMD: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
		val RISE: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
		val ENGINEER: UUID = UUID.fromString("80000000-0000-0000-0000-000000000001")
		val TECH_LEAD: UUID = UUID.fromString("80000000-0000-0000-0000-000000000002")
		val ORPHAN: UUID = UUID.fromString("90000000-0000-0000-0000-000000000003")
	}

	private val memberDb by lazy { MemberDbFixture.writable.let { JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(it.url, it.username, it.password)) } }
	private val system = Viewer(null, "system", "System", true, emptyMap())
	private lateinit var umd: UUID

	private fun one(sql: String, vararg args: Any): Map<String, Any?> = memberDb.sql(sql).params(args.toList()).query().singleRow()
	private fun count(sql: String, vararg args: Any): Long = memberDb.sql(sql).params(args.toList()).query(Long::class.java).single()
	private fun portalId(sourceTable: org.jooq.Table<*>, source: UUID): UUID =
		dsl.select(sourceTable.field("id", UUID::class.java)).from(sourceTable).where(sourceTable.field("source_id", UUID::class.java)!!.eq(source)).fetchSingle().value1()!!
	private fun person(volunteer: UUID) = portalId(PERSON, volunteer)
	private fun role(source: UUID) = portalId(PROJECT_ROLE, source)
	private fun record(id: UUID) = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(id)).fetchSingle()
	private fun projectWrite(id: UUID) = dsl.selectFrom(MEMBER_DB_PROJECT_WRITE).where(MEMBER_DB_PROJECT_WRITE.PROJECT_ID.eq(id)).fetchSingle()

	private fun live(projectsEnabled: Boolean = true) = dsl.update(MEMBER_DB_STATE).set(MEMBER_DB_STATE.DRY_RUN, false).set(MEMBER_DB_STATE.PAUSED, false)
		.set(MEMBER_DB_STATE.PROJECTS_ENABLED, projectsEnabled).execute()

	/** A started project for the imported partner RISE DC, written once. */
	private fun started(name: String = "Food Drive", slug: String? = null, partner: UUID = portalId(PARTNER, RISE), past: UUID? = null): UUID {
		val project = projects.create(umd, ProjectInput(name, slug = slug, partnerId = partner, pastProjectId = past, startsOn = LocalDate.parse("2026-09-01")), system).id!!
		projects.setStatus(umd, record(project), "active", system)
		return project
	}

	private val nora = oidcLogin().idToken { it.subject("sub-nora").claim("email", "nora@hack4impact.org").claim("email_verified", true).claim("hd", "hack4impact.org") }

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute()
		pull.pull(force = true) // chapters, volunteers, partners and project roles come in
		umd = portalId(CHAPTER, UMD)
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
	}

	@Test
	fun `a started project writes its row, engagement and team, and removals and closing end rows without deleting any`() {
		live()
		val draft = projects.create(umd, ProjectInput("Food Drive", partnerId = portalId(PARTNER, RISE), startsOn = LocalDate.parse("2026-09-01")), system).id!!
		projects.addMember(umd, record(draft), MemberInput(person(ADA), role(ENGINEER)), system)
		writer.runDue()
		assertEquals(0L, count("SELECT count(*) FROM projects WHERE name = 'Food Drive'")) // a draft isn't written
		assertEquals("done", projectWrite(draft).state)

		projects.setStatus(umd, record(draft), "active", system)
		assertEquals(1, writer.runDue())
		val row = record(draft).memberDbProjectId!!
		assertEquals("active", one("SELECT status FROM projects WHERE id = ?", row)["status"])
		with(one("SELECT * FROM project_engagements WHERE id = ?", draft)) {
			assertEquals(UMD, this["chapter_id"])
			assertEquals(RISE, this["partner_id"])
			assertEquals(row, this["project_id"])
			assertEquals("active", this["status"])
		}
		val assignment = one("SELECT * FROM project_assignments WHERE project_engagement_id = ?", draft)
		assertEquals(ADA, assignment["volunteer_id"])
		assertEquals(ENGINEER, assignment["project_role_id"])
		assertNull(assignment["end_date"])

		projects.removeMember(umd, record(draft), person(ADA), system)
		writer.runDue()
		assertNotNull(one("SELECT end_date FROM project_assignments WHERE project_engagement_id = ?", draft)["end_date"])
		projects.setStatus(umd, record(draft), "closed", system)
		writer.runDue()
		assertEquals("completed", one("SELECT status FROM projects WHERE id = ?", row)["status"])
		with(one("SELECT status, end_date FROM project_engagements WHERE id = ?", draft)) {
			assertEquals("completed", this["status"])
			assertNotNull(this["end_date"])
		}
		assertEquals(1L, count("SELECT count(*) FROM project_assignments WHERE project_engagement_id = ?", draft)) // ended, not deleted
	}

	@Test
	fun `a paused project stays active in projects and pauses its engagement, and a role change ends one assignment and opens another`() {
		live()
		val project = started()
		projects.addMember(umd, record(project), MemberInput(person(ADA), role(ENGINEER)), system)
		projects.setStatus(umd, record(project), "paused", system)
		writer.runDue()
		assertEquals("active", one("SELECT status FROM projects WHERE id = ?", record(project).memberDbProjectId!!)["status"])
		assertEquals("paused", one("SELECT status FROM project_engagements WHERE id = ?", project)["status"])

		projects.setMemberRole(umd, record(project), person(ADA), MemberRoleInput(role(TECH_LEAD)), system)
		writer.runDue()
		val rows = memberDb.sql("SELECT project_role_id, end_date FROM project_assignments WHERE project_engagement_id = ? ORDER BY start_date").param(project).query().listOfRows()
		assertEquals(listOf(ENGINEER, TECH_LEAD), rows.map { it["project_role_id"] })
		assertNotNull(rows[0]["end_date"])
		assertNull(rows[1]["end_date"])
		assertEquals(rows.size, 2)
		writer.runDue() // writing again changes nothing
		assertEquals(2L, count("SELECT count(*) FROM project_assignments WHERE project_engagement_id = ?", project))
	}

	@Test
	fun `a project linked to a past row writes only its engagement and team`() {
		live()
		val project = started(past = ORPHAN)
		projects.addMember(umd, record(project), MemberInput(person(ADA), role(ENGINEER)), system)
		writer.runDue()
		assertEquals(mapOf("name" to "Orphan Project", "status" to "inactive"), one("SELECT name, status FROM projects WHERE id = ?", ORPHAN)) // left as it was
		assertEquals(ORPHAN, one("SELECT project_id FROM project_engagements WHERE id = ?", project)["project_id"])
		assertEquals(1L, count("SELECT count(*) FROM project_assignments WHERE project_engagement_id = ?", project))
	}

	@Test
	fun `a name taken in the member database gets the chapter, then the chapter and year, at creation and on rename`() {
		live()
		val first = started("RISE DC Portal")
		val second = started("RISE DC Portal", slug = "rise-dc-portal-2")
		writer.runDue()
		assertEquals("RISE DC Portal (UMD)", one("SELECT name FROM projects WHERE id = ?", record(first).memberDbProjectId!!)["name"])
		assertEquals("RISE DC Portal (UMD 2026)", one("SELECT name FROM projects WHERE id = ?", record(second).memberDbProjectId!!)["name"])
		val third = started("RISE DC Portal", slug = "rise-dc-portal-3")
		writer.runDue()
		assertEquals("failed", projectWrite(third).state) // all three taken: national renames it
		assertTrue("rename the project" in projectWrite(third).lastError!!)

		projects.update(umd, record(first), ProjectInput("Food Bank App", partnerId = portalId(PARTNER, RISE), startsOn = LocalDate.parse("2026-09-01")), system)
		writer.runDue()
		assertEquals("Food Bank App (UMD)", one("SELECT name FROM projects WHERE id = ?", record(first).memberDbProjectId!!)["name"])
	}

	@Test
	fun `a partner national added is written active, with its chapter, inside the first project, and the pull doesn't import it again`() {
		live()
		val partner = dsl.insertInto(PARTNER).set(PARTNER.NAME, "Brand New Partner").returningResult(PARTNER.ID).fetchSingle().value1()!!
		val project = started(partner = partner)
		writer.runDue()
		assertEquals(mapOf("name" to "Brand New Partner", "status" to "active"), one("SELECT name, status FROM partners WHERE id = ?", partner))
		assertEquals(1L, count("SELECT count(*) FROM chapter_partners WHERE chapter_id = ? AND partner_id = ?", UMD, partner))
		assertEquals(partner, dsl.select(PARTNER.SOURCE_ID).from(PARTNER).where(PARTNER.ID.eq(partner)).fetchSingle().value1())
		assertEquals(partner, one("SELECT partner_id FROM project_engagements WHERE id = ?", project)["partner_id"])
		pull.pull(force = true)
		assertEquals(1, dsl.fetchCount(PARTNER, PARTNER.NAME.eq("Brand New Partner")))
	}

	@Test
	fun `chapter leads and co-leads become leadership assignments, ended with their role, and turning project writes on queues every open one`() {
		live(projectsEnabled = false)
		val ada = person(ADA)
		val alan = person(ALAN)
		val lead = dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, ada).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").returningResult(CHAPTER_ROLE.ID).fetchSingle().value1()!!
		val coLead = dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, alan).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "co_lead").returningResult(CHAPTER_ROLE.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, alan).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "viewer").execute()
		mvc.put("/api/national/member-db") { with(nora); with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"dryRun":false,"paused":false,"projectsEnabled":true}""" }
			.andExpect { status { isOk() } }
		assertEquals(setOf(lead, coLead), dsl.select(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID).from(MEMBER_DB_LEADERSHIP_WRITE).fetchSet(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID))

		writer.runDue()
		with(one("SELECT l.volunteer_id, l.chapter_id, l.end_date, r.name FROM leadership_assignments l JOIN leadership_roles r ON r.id = l.leadership_role_id WHERE l.id = ?", lead)) {
			assertEquals(ADA, this["volunteer_id"])
			assertEquals(UMD, this["chapter_id"])
			assertEquals("Chapter lead", this["name"])
			assertNull(this["end_date"])
		}
		assertEquals("Co-lead", one("SELECT r.name FROM leadership_assignments l JOIN leadership_roles r ON r.id = l.leadership_role_id WHERE l.id = ?", coLead)["name"])

		// The co-lead's role ends (even on a future date): the assignment's end date follows at once.
		dsl.update(CHAPTER_ROLE).set(CHAPTER_ROLE.ENDS_AT, OffsetDateTime.now().plusDays(30)).where(CHAPTER_ROLE.ID.eq(coLead)).execute()
		writes.requestLeadership(coLead)
		writer.runDue()
		assertNotNull(one("SELECT end_date FROM leadership_assignments WHERE id = ?", coLead)["end_date"])
	}

	@Test
	fun `a write waits while someone's own record is pending, and goes to national when it failed or was deleted there`() {
		live()
		val project = started()
		val nia = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nia").set(PERSON.LAST_NAME, "New").set(PERSON.STATUS, "active").set(PERSON.PERSONAL_EMAIL, "nia@personal.test")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, nia).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		writes.request(nia)
		dsl.update(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.NEXT_TRY_AT, OffsetDateTime.now().plusHours(1)).where(MEMBER_DB_WRITE.PERSON_ID.eq(nia)).execute() // not written yet
		projects.addMember(umd, record(project), MemberInput(nia, role(ENGINEER)), system)

		writer.runDue()
		with(projectWrite(project)) {
			assertEquals("pending", state)
			assertEquals(0, attempts) // waiting isn't failing
			assertTrue(nia.toString() in waitingOn!!.data())
		}
		mvc.get("/api/national/member-db/projects/$project/preview") { with(nora) }.andExpect { jsonPath("$.waitingOn[0]") { value("Nia New") } }
		assertEquals(0L, count("SELECT count(*) FROM project_engagements WHERE id = ?", project)) // nothing half-written

		dsl.update(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.STATE, "failed").where(MEMBER_DB_WRITE.PERSON_ID.eq(nia)).execute()
		dsl.update(MEMBER_DB_PROJECT_WRITE).setNull(MEMBER_DB_PROJECT_WRITE.NEXT_TRY_AT).execute()
		writer.runDue()
		with(projectWrite(project)) {
			assertEquals("failed", state)
			assertTrue("Nia New's own record" in lastError!!)
		}

		projects.removeMember(umd, record(project), nia, system)
		dsl.deleteFrom(PROJECT_MEMBER).where(PROJECT_MEMBER.PERSON_ID.eq(nia)).execute()
		projects.addMember(umd, record(project), MemberInput(person(ADA), role(ENGINEER)), system)
		memberDb.sql("UPDATE volunteers SET deleted_at = now() WHERE id = ?").param(ADA).update()
		writer.runDue()
		assertTrue("deleted Ada" in projectWrite(project).lastError!!)
	}

	@Test
	fun `with project writes off they're previewable but not written, one pause stops every write, and the preview is what's written`() {
		live(projectsEnabled = false)
		val project = started()
		projects.addMember(umd, record(project), MemberInput(person(ADA), role(ENGINEER)), system)
		assertEquals(0, writer.runDue())
		val preview = mvc.get("/api/national/member-db/projects/$project/preview") { with(nora) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
		assertTrue("\"project_engagements.status\":\"active\"" in preview, preview)
		mvc.get("/api/national/member-db") { with(nora) }.andExpect {
			jsonPath("$.projectsEnabled") { value(false) }
			jsonPath("$.projectWrites[0].name") { value("Food Drive") }
		}

		live()
		dsl.update(MEMBER_DB_STATE).set(MEMBER_DB_STATE.PAUSED, true).execute()
		assertEquals(0, writer.runDue())
		assertEquals(false, writer.live())

		live()
		val planned = writer.planProject(project).fields
		assertEquals(1, writer.runDue())
		val written = tools.jackson.databind.json.JsonMapper.builder().build().readTree(projectWrite(project).written!!.data())
		for ((key, value) in planned) {
			if (key == "projects.id") continue // a new row's ID is chosen when it's written
			assertEquals(value, written.path(key).takeUnless { it.isNull }?.asString(), key)
		}
	}
}
