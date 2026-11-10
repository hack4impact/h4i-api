package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.EMAIL_MESSAGE
import org.hack4impact.portal.db.tables.references.EVENT_PUBLICATION
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.sync.FakeTools
import org.hack4impact.portal.sync.SyncEngine
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stage 2 package 5: a lead sets up a project, its team and resources (from the full portal's step 8 tests). */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
	@Autowired private val summaries: AccessSummaries,
	@Autowired private val transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {
	private val json = JsonMapper.builder().build()
	private lateinit var umd: UUID
	private lateinit var gt: UUID
	private lateinit var ada: UUID
	private lateinit var alan: UUID
	private lateinit var bo: UUID
	private lateinit var applicant: UUID
	private lateinit var gus: UUID
	private lateinit var developer: UUID
	private lateinit var techLead: UUID
	private lateinit var fall: UUID
	private lateinit var rise: UUID

	private fun person(first: String, chapter: UUID, status: String = "active", email: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).set(PERSON.ORG_EMAIL, email)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute() }

	private fun role(person: UUID, chapter: UUID, role: String) =
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, person).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, role).execute()

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun send(method: String, path: String, body: String? = null, as_: String = "lena@hack4impact.org"): ResultActionsDsl {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(signedIn(as_)); with(csrf())
			if (body != null) { contentType = MediaType.APPLICATION_JSON; content = body }
		}
		return when (method) {
			"POST" -> mvc.post(path) { setup() }
			"PUT" -> mvc.put(path) { setup() }
			"DELETE" -> mvc.delete(path) { setup() }
			else -> mvc.get(path) { setup() }
		}
	}

	private fun body(result: ResultActionsDsl): JsonNode = json.readTree(result.andReturn().response.contentAsString)

	private fun create(name: String = "RISE DC", extra: String = ""): JsonNode =
		body(send("POST", "/api/chapters/umd/projects", """{"name":"$name","tags":["lts"],"termId":"$fall","partnerId":"$rise"$extra}""").andExpect { status { isCreated() } })

	private fun resource(project: JsonNode, tool: String) = project.path("resources").first { it.path("tool").asString() == tool }

	private fun member(person: UUID, role: UUID = developer, slug: String = "rise-dc", as_: String = "lena@hack4impact.org") =
		send("POST", "/api/chapters/umd/projects/$slug/members", """{"personId":"$person","roleId":"$role"}""", as_)

	/** The background sync a change asks for: waited for, so it doesn't overlap the next test. */
	private fun waitForOutbox() {
		val deadline = System.currentTimeMillis() + 15_000
		while (dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.COMPLETION_DATE.isNull) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
	}

	@BeforeEach
	fun seed() {
		waitForOutbox()
		dsl.emptyPortalTables()
		val school = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "University of Maryland").returningResult(INSTITUTION.ID).fetchSingle().value1()!!
		fall = dsl.insertInto(TERM).set(TERM.INSTITUTION_ID, school).set(TERM.SEASON, "fall").set(TERM.YEAR, 2026.toShort())
			.set(TERM.STARTS_ON, LocalDate.parse("2026-08-31")).set(TERM.ENDS_ON, LocalDate.parse("2026-12-20")).returningResult(TERM.ID).fetchSingle().value1()!!
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").set(CHAPTER.INSTITUTION_ID, school).returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Georgia Tech").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", umd, email = "lena@hack4impact.org").also { role(it, umd, "lead") }
		person("Vic", umd, email = "vic@hack4impact.org").also { role(it, umd, "viewer") }
		ada = person("Ada", umd, email = "ada@hack4impact.org")
		bo = person("Bo", umd, email = "bo@hack4impact.org")
		alan = person("Alan", umd, status = "alumni")
		applicant = person("Apu", umd, status = "applicant")
		gus = person("Gus", gt, email = "gus@hack4impact.org").also { role(it, gt, "lead") }
		person("Nora", gt, email = "nora@hack4impact.org").also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		techLead = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Tech Lead").set(PROJECT_ROLE.IS_LEAD, true).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		rise = dsl.insertInto(PARTNER).set(PARTNER.NAME, "RISE DC Inc").returningResult(PARTNER.ID).fetchSingle().value1()!!
		slack.failure = null; slack.accounts = emptyList(); slack.resources = emptyMap(); slack.writes.clear()
		github.failure = null; github.accounts = emptyList(); github.resources = emptyMap()
	}

	@Test
	fun `a lead sets up a project and its planned changes show after a dry run`() {
		val created = create()
		assertEquals("draft", created.path("status").asString())
		assertEquals("rise-dc", created.path("slug").asString())
		assertEquals("Fall 2026", created.path("term").asString())
		assertEquals("RISE DC Inc", created.path("partner").asString())
		// The national template's standard resources, named by the convention.
		assertEquals(
			setOf("slack:umd-rise-dc", "google:umd-rise-dc@hack4impact.org", "github:umd-rise-dc", "vaultwarden:umd-rise-dc"),
			created.path("resources").toList().map { "${it.path("tool").asString()}:${it.path("name").asString()}" }.toSet(),
		)
		assertTrue(resource(created, "github").path("requiresAgreement").asBoolean())
		assertTrue(created.path("resources").all { it.path("state").asString() == "to_create" && it.path("managed").asString() == "portal" })

		member(ada, techLead).andExpect { status { isOk() } }
		val withAlan = body(member(alan).andExpect { status { isOk() } })
		// A draft is previewed as if active: both get Slack; the gated GitHub team waits on their agreements.
		assertTrue(withAlan.path("preview").path("asIfActive").asBoolean())
		assertEquals(2, resource(withAlan, "slack").path("getting").asInt())
		assertEquals(0, resource(withAlan, "github").path("getting").asInt())
		assertEquals(2, resource(withAlan, "github").path("awaitingAgreement").asInt())
		assertEquals(0, withAlan.path("agreementsSigned").asInt())
		waitForOutbox()

		engine.dryRun("manual")
		assertEquals(0, body(send("GET", "/api/chapters/umd/projects/rise-dc")).path("planned").size()) // a draft changes nothing in the tools

		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		waitForOutbox()
		engine.dryRun("manual")

		val after = body(send("GET", "/api/chapters/umd/projects/rise-dc"))
		val planned = after.path("planned").toList().map { "${it.path("kind").asString()}:${it.path("tool").asString()}:${it.path("person").asString("")}" }.toSet()
		assertEquals(setOf("create_resource:slack:", "add:slack:Ada Test", "add:slack:Alan Test", "create_resource:github:"), planned)
		assertEquals(false, after.path("plannedOutOfDate").asBoolean())
		assertTrue(after.path("activity").any { it.path("kind").asString() == "change" && it.path("text").asString().startsWith("Created") && it.path("actor").asString() == "Lena Test" })
		assertTrue(after.path("planned").all { it.path("mode").asString() == "dry_run" && it.path("outcome").isNull })

		// A real run of a live tool: the project shows what it did, with each outcome.
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		engine.run("manual", setOf(org.hack4impact.portal.resolver.Tool.SLACK))
		val applied = body(send("GET", "/api/chapters/umd/projects/rise-dc")).path("planned").toList().filter { it.path("tool").asString() == "slack" }
		assertEquals(setOf("create_resource:applied", "add:invited"), applied.map { "${it.path("kind").asString()}:${it.path("outcome").asString()}" }.toSet())
		assertTrue(applied.all { it.path("mode").asString() == "apply" })
	}

	@Test
	fun `every change asks for a sync, and a project's leads only see and change their own team`() {
		create()
		create("Food Bank")
		val requests = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%\"trigger\":\"change\"%'")) }
		val before = requests()
		member(ada, techLead).andExpect { status { isOk() } }
		assertEquals(before + 1, requests()) // in the same transaction (decision 142)

		// Ada leads RISE DC: she sees it and changes its team, without a chapter role.
		send("GET", "/api/chapters/umd/projects/rise-dc", as_ = "ada@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$.canEdit") { value(false) }
			jsonPath("$.canEditTeam") { value(true) }
		}
		send("GET", "/api/chapters/umd/projects/rise-dc/candidates", as_ = "ada@hack4impact.org").andExpect { status { isOk() } }
		member(bo, developer, as_ = "ada@hack4impact.org").andExpect { status { isOk() } }
		// …but not the project's details, another project's team, or the chapter's projects.
		send("PUT", "/api/chapters/umd/projects/rise-dc", """{"name":"Renamed"}""", as_ = "ada@hack4impact.org").andExpect { status { isForbidden() } }
		member(bo, developer, slug = "food-bank", as_ = "ada@hack4impact.org").andExpect { status { isForbidden() } }
		send("GET", "/api/chapters/umd/projects/food-bank", as_ = "ada@hack4impact.org").andExpect { status { isForbidden() } }
		send("GET", "/api/chapters/umd/projects", as_ = "ada@hack4impact.org").andExpect { status { isForbidden() } }
		waitForOutbox()
	}

	@Test
	fun `only leads, co-leads and national change projects, and only in their chapter`() {
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "gus@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "nora@hack4impact.org").andExpect { status { isCreated() } }
		send("GET", "/api/chapters/umd/projects/x", as_ = "vic@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$.canEdit") { value(false) }
		}
		send("GET", "/api/chapters/umd/projects", as_ = "gus@hack4impact.org").andExpect { status { isForbidden() } }
		send("GET", "/api/chapters/umd/terms", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		send("GET", "/api/chapters/umd/terms").andExpect { jsonPath("$[0].label") { value("Fall 2026") } }
	}

	@Test
	fun `members must be current chapter members who are active or alumni, once each, with a role`() {
		create()
		val path = "/api/chapters/umd/projects/rise-dc/members"
		send("POST", path, """{"personId":"$ada"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.detail") { value("Choose the person's project role") }
		}
		member(applicant).andExpect { status { isBadRequest() } }
		member(gus).andExpect { status { isBadRequest() } } // Georgia Tech
		member(ada).andExpect { status { isOk() } }
		member(ada).andExpect { status { isConflict() } }
		send("PUT", "$path/$ada", """{}""").andExpect { status { isBadRequest() } } // a role can't be taken away
		send("PUT", "$path/$ada", """{"roleId":"$techLead"}""").andExpect { jsonPath("$.members[0].isLead") { value(true) } }
		send("DELETE", "$path/$ada").andExpect { jsonPath("$.members.length()") { value(0) } }
		assertNotNull(dsl.select(PROJECT_MEMBER.REMOVED_AT).from(PROJECT_MEMBER).where(PROJECT_MEMBER.PERSON_ID.eq(ada)).fetchSingle().value1())
		waitForOutbox()
	}

	@Test
	fun `partners are picked, a project starts only with one, and its start date defaults to the day it starts`() {
		send("POST", "/api/chapters/umd/projects", """{"name":"Nowhere","partnerId":"${UUID.randomUUID()}"}""").andExpect { status { isUnprocessableContent() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"No Partner"}""").andExpect { status { isCreated() } }
		send("POST", "/api/chapters/umd/projects/no-partner/status", """{"status":"active"}""").andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.detail") { value("Choose the project's partner before starting it") }
		}
		dsl.update(PARTNER).set(PARTNER.DELETED_AT, java.time.OffsetDateTime.now()).where(PARTNER.ID.eq(rise)).execute()
		send("PUT", "/api/chapters/umd/projects/no-partner", """{"name":"No Partner","partnerId":"$rise"}""").andExpect { status { isUnprocessableContent() } } // deleted
		dsl.update(PARTNER).setNull(PARTNER.DELETED_AT).where(PARTNER.ID.eq(rise)).execute()
		send("PUT", "/api/chapters/umd/projects/no-partner", """{"name":"No Partner","partnerId":"$rise"}""").andExpect { status { isOk() } }
		send("POST", "/api/chapters/umd/projects/no-partner/status", """{"status":"active"}""").andExpect {
			status { isOk() }
			jsonPath("$.startsOn") { value(LocalDate.now().toString()) }
		}
		send("PUT", "/api/chapters/umd/projects/no-partner", """{"name":"No Partner"}""").andExpect { status { isUnprocessableContent() } } // can't drop it once started

		// A start date already set stays.
		create("Dated", extra = ""","startsOn":"2026-09-02"""")
		send("POST", "/api/chapters/umd/projects/dated/status", """{"status":"active"}""").andExpect { jsonPath("$.startsOn") { value("2026-09-02") } }
		waitForOutbox()
	}

	@Test
	fun `national adds partners, refusing a duplicate in any case, and leads list them`() {
		send("POST", "/api/national/partners", """{"name":"Food Bank"}""", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/national/partners", """{"name":" Food Bank ","website":"https://food.example"}""", as_ = "nora@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Food Bank") }
		}
		send("POST", "/api/national/partners", """{"name":"food bank"}""", as_ = "nora@hack4impact.org").andExpect { status { isConflict() } }
		send("POST", "/api/national/partners", """{"name":"  "}""", as_ = "nora@hack4impact.org").andExpect { status { isUnprocessableContent() } }
		send("GET", "/api/partners").andExpect {
			jsonPath("$.length()") { value(2) }
			jsonPath("$[0].name") { value("Food Bank") }
		}
		send("GET", "/api/partners", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
	}

	@Test
	fun `resources are attached new or shared, checked, and a never-created one is dropped when detached`() {
		create()
		create("Food Bank")
		val path = "/api/chapters/umd/projects/rise-dc/resources"
		send("POST", path, """{"tool":"slack","name":"#UMD Rise Dc Design"}""").andExpect { status { isBadRequest() } } // spaces
		send("POST", path, """{"tool":"slack","name":"umd-food-bank"}""").andExpect { status { isConflict() } } // exists: attach it instead
		send("POST", path, """{"tool":"notion","name":"x"}""").andExpect { status { isBadRequest() } } // Notion pages are stage 4
		send("POST", path, """{"tool":"documenso","name":"x"}""").andExpect { status { isBadRequest() } }
		send("POST", path, """{"tool":"figma","name":"x"}""").andExpect { status { isBadRequest() } } // not a registered tool
		send("POST", path, """{"tool":"Slack","name":"umd-rise-dc-random"}""").andExpect { status { isOk() } }
		// A lead names only the chapter's own: any other unused name (a group at any H4I address) is national's to add.
		send("POST", path, """{"tool":"google","name":"finance@hack4impact.org"}""").andExpect { status { isBadRequest() } }
		send("POST", path, """{"tool":"slack","name":"announcements"}""").andExpect { status { isBadRequest() } }
		send("POST", path, """{"tool":"google","name":"finance@hack4impact.org"}""", as_ = "nora@hack4impact.org").andExpect { status { isOk() } }
		assertEquals("slack", dsl.select(RESOURCE.TOOL).from(RESOURCE).where(RESOURCE.NAME.eq("umd-rise-dc-random")).fetchSingle().value1())
		send("POST", path, """{"tool":"github","name":"umd-rise-dc-design","audience":"role"}""").andExpect { status { isBadRequest() } } // role missing

		val design = body(send("POST", path, """{"tool":"slack","name":"#umd-rise-dc-design","audience":"role","roleId":"$developer","tags":["Design"]}""").andExpect { status { isOk() } })
		val row = design.path("resources").first { it.path("name").asString() == "umd-rise-dc-design" }
		assertEquals("Developer", row.path("role").asString())
		assertEquals("design", row.path("tags")[0].asString())

		val foodBankSlack = dsl.select(RESOURCE.ID).from(RESOURCE).where(RESOURCE.NAME.eq("umd-food-bank"), RESOURCE.TOOL.eq("slack")).fetchSingle().value1()!!
		val shared = body(send("POST", path, """{"resourceId":"$foodBankSlack","audience":"leads","access":"read"}""").andExpect { status { isOk() } })
		assertEquals(listOf("Food Bank"), shared.path("resources").first { it.path("resourceId").asString() == "$foodBankSlack" }.path("sharedWith").toList().map { it.asString() })
		send("GET", "/api/chapters/umd/resources").andExpect { jsonPath("$[?(@.name == 'umd-food-bank')].projects[1]") { value("RISE DC") } }
		send("GET", "/api/chapters/umd/resources", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }

		val designId = row.path("resourceId").asString()
		send("DELETE", "$path/$designId").andExpect { status { isOk() } }
		assertNotNull(dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(UUID.fromString(designId))).fetchSingle().value1())
		send("DELETE", "$path/$foodBankSlack").andExpect { status { isOk() } }
		assertEquals(null, dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(foodBankSlack)).fetchSingle().value1()) // Food Bank still uses it
		waitForOutbox()
	}

	@Test
	fun `a resource whose name the tool already has is flagged until national decides`() {
		val created = create()
		val slackId = resource(created, "slack").path("resourceId").asString()
		dsl.update(RESOURCE).set(RESOURCE.EXISTS_IN_TOOL_AT, java.time.OffsetDateTime.now()).where(RESOURCE.ID.eq(UUID.fromString(slackId))).execute()
		val after = body(send("GET", "/api/chapters/umd/projects/rise-dc"))
		assertTrue(resource(after, "slack").path("existsInTool").asBoolean())
		assertEquals(false, resource(after, "github").path("existsInTool").asBoolean())
	}

	@Test
	fun `status moves draft, active, paused, closed, and a closed project can't change`() {
		create()
		val path = "/api/chapters/umd/projects/rise-dc/status"
		send("POST", path, """{"status":"paused"}""").andExpect { status { isConflict() } }
		send("POST", path, """{"status":"active"}""").andExpect { jsonPath("$.status") { value("active") } }
		send("POST", path, """{"status":"draft"}""").andExpect { status { isConflict() } }
		send("POST", path, """{"status":"paused"}""").andExpect { jsonPath("$.status") { value("paused") } }
		send("POST", path, """{"status":"closed"}""").andExpect { jsonPath("$.status") { value("closed") } }
		member(ada).andExpect { status { isConflict() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"RISE DC"}""").andExpect { status { isConflict() } } // the short name is taken
		assertEquals(4, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.TARGET_TYPE.eq("project"))) // create and three status changes; refused changes leave no trace
		waitForOutbox()
	}

	@Test
	fun `everyone whose projects or access a change touches gets one summary, and the member page says why`() {
		create()
		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		member(ada).andExpect { status { isOk() } }
		val summaries = { to: String -> dsl.fetchCount(EMAIL_MESSAGE, EMAIL_MESSAGE.TEMPLATE.eq("access_summary"), EMAIL_MESSAGE.TO_ADDRESS.eq(to)) }
		assertEquals(1, summaries("ada@hack4impact.org"))
		assertEquals("Your UMD projects and access", dsl.select(EMAIL_MESSAGE.SUBJECT).from(EMAIL_MESSAGE).where(EMAIL_MESSAGE.TEMPLATE.eq("access_summary")).fetchSingle().value1())
		member(bo).andExpect { status { isOk() } }
		assertEquals(1, summaries("ada@hack4impact.org")) // Bo joining changes nothing for Ada
		assertEquals(1, summaries("bo@hack4impact.org"))

		// Her page: the project, and the channel because she's on it (the GitHub team after she signs).
		send("GET", "/api/me/home", as_ = "ada@hack4impact.org").andExpect {
			jsonPath("$.projects[0].name") { value("RISE DC") }
			jsonPath("$.projects[0].role") { value("Developer") }
			jsonPath("$.projects[0].canOpen") { value(false) }
			jsonPath("$.access[?(@.tool == 'slack')].reasons[0]") { value("On RISE DC") }
			jsonPath("$.access[?(@.tool == 'github')].state") { value("agreement_not_set_up") } // no Documenso in these tests (package 6 review R1)
		}
		send("GET", "/api/chapters/umd/members/$ada/access").andExpect { jsonPath("$[?(@.tool == 'github')].reasons[0]") { value("After the RISE DC agreement, which isn't set up yet") } }
		// Hers already sent before sending was switched off: she still has it to sign (package 8 review R3).
		val template = dsl.insertInto(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE).set(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE.KIND, "project")
			.set(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE.NAME, "Project agreement").set(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, "t")
			.set(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE.VERSION, 1).returningResult(org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE.ID).fetchSingle().value1()
		val projectId = dsl.select(PROJECT.ID).from(PROJECT).where(PROJECT.SLUG.eq("rise-dc")).fetchSingle().value1()
		dsl.insertInto(org.hack4impact.portal.db.tables.references.AGREEMENT).set(org.hack4impact.portal.db.tables.references.AGREEMENT.PERSON_ID, ada)
			.set(org.hack4impact.portal.db.tables.references.AGREEMENT.PROJECT_ID, projectId).set(org.hack4impact.portal.db.tables.references.AGREEMENT.TEMPLATE_ID, template)
			.set(org.hack4impact.portal.db.tables.references.AGREEMENT.STATUS, "sent").execute()
		send("GET", "/api/me/home", as_ = "ada@hack4impact.org").andExpect { jsonPath("$.access[?(@.tool == 'github')].state") { value("after_agreement") } }
		send("GET", "/api/chapters/umd/members/$ada/access", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		send("GET", "/api/chapters/umd/members/$gus/access").andExpect { status { isNotFound() } }

		// The summary's wording.
		val text = Emails.accessSummary("Ada", "UMD", listOf("RISE DC"), emptyList(), listOf("#umd-rise-dc (Slack)"), emptyList(), listOf("umd-rise-dc (GitHub)"), "https://portal/projects/umd/rise-dc").text
		assertContains(text, "You're on: RISE DC")
		assertContains(text, "  - #umd-rise-dc (Slack)")
		assertContains(text, "Once you sign the project agreement")
		assertContains(Emails.accessSummary("Ada", "UMD", emptyList(), emptyList(), emptyList(), emptyList(), listOf("x"), "l", agreementReady = false).text, "Once national sets up the project agreement")
		assertContains(text, "https://portal/projects/umd/rise-dc")
		waitForOutbox()
	}

	@Test
	fun `project roles are listed for anyone signed in, leads first`() {
		send("GET", "/api/project-roles", as_ = "ada@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$[0].name") { value("Tech Lead") }
			jsonPath("$[0].isLead") { value(true) }
		}
		assertEquals(0, dsl.fetchCount(PROJECT))
		assertEquals(0, dsl.fetchCount(SYNC_RUN))
	}

	// Package 5 review fixes ------------------------------------------------------------------------------------------

	@Test
	fun `nobody is promised access the sync won't give (review R2)`() {
		val created = create()
		val slackId = UUID.fromString(resource(created, "slack").path("resourceId").asString())
		dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(slackId)).execute() // national left the channel alone
		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		member(ada).andExpect { status { isOk() } }
		waitForOutbox()

		// The summary's snapshot: not the unmanaged channel, nor the Google group (no Google adapter in these tests).
		val adaGets = summaries.snapshot().grants.keys.filter { it.first == ada }.map { it.second }.toSet()
		val google = UUID.fromString(resource(created, "google").path("resourceId").asString())
		assertTrue(slackId !in adaGets && google !in adaGets, adaGets.toString())
		send("GET", "/api/me/home", as_ = "ada@hack4impact.org").andExpect {
			jsonPath("$.access[?(@.tool == 'slack')]") { isEmpty() }
			jsonPath("$.access[?(@.tool == 'google')].state") { value("not_connected") }
		}
	}

	@Test
	fun `a partner deleted after it was picked blocks only starting, and national is told why it can't add it (review R3)`() {
		create()
		dsl.update(PARTNER).set(PARTNER.DELETED_AT, java.time.OffsetDateTime.now()).where(PARTNER.ID.eq(rise)).execute()
		send("PUT", "/api/chapters/umd/projects/rise-dc", """{"name":"RISE DC 2","termId":"$fall","partnerId":"$rise"}""").andExpect { status { isOk() } } // unchanged partner
		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("was deleted in the member database")) }
		}
		send("POST", "/api/national/partners", """{"name":"rise dc inc"}""", as_ = "nora@hack4impact.org").andExpect {
			status { isConflict() }
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("Restore it there")) }
		}
		waitForOutbox()
	}

	@Test
	fun `detaching a resource that waits for national closes its task (review R4)`() {
		val created = create()
		val slackId = UUID.fromString(resource(created, "slack").path("resourceId").asString())
		dsl.update(RESOURCE).set(RESOURCE.EXISTS_IN_TOOL_AT, java.time.OffsetDateTime.now()).where(RESOURCE.ID.eq(slackId)).execute()
		dsl.insertInto(org.hack4impact.portal.db.tables.references.ADMIN_TASK).set(org.hack4impact.portal.db.tables.references.ADMIN_TASK.TOOL, "slack")
			.set(org.hack4impact.portal.db.tables.references.ADMIN_TASK.ACTION, "resource_exists").set(org.hack4impact.portal.db.tables.references.ADMIN_TASK.RESOURCE_ID, slackId)
			.set(org.hack4impact.portal.db.tables.references.ADMIN_TASK.DESCRIPTION, "umd-rise-dc already exists in Slack").execute()
		send("DELETE", "/api/chapters/umd/projects/rise-dc/resources/$slackId").andExpect { status { isOk() } }
		assertEquals("done", dsl.select(org.hack4impact.portal.db.tables.references.ADMIN_TASK.STATUS).from(org.hack4impact.portal.db.tables.references.ADMIN_TASK).fetchSingle().value1())
		waitForOutbox()
	}

	@Test
	fun `only the project's team is emailed, whatever else changed meanwhile (review R5)`() {
		val project = UUID.fromString(create().path("id").asString())
		val channel = UUID.randomUUID()
		val before = AccessSummaries.Snapshot(emptyMap(), emptySet(), emptySet())
		// Between the snapshots: Ada joined this project; Bo gained something elsewhere (another lead's change).
		val after = AccessSummaries.Snapshot(mapOf((bo to channel) to org.hack4impact.portal.resolver.Access.WRITE), emptySet(), setOf(ada to project))
		val sent = transactionsDo { summaries.send(before, after, project) }
		assertEquals(1, sent)
		assertEquals(0, dsl.fetchCount(EMAIL_MESSAGE, EMAIL_MESSAGE.TO_ADDRESS.eq("bo@hack4impact.org")))
	}

	@Test
	fun `access waiting on the agreement shows the level the project gives (review R7)`() {
		val created = create()
		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		val github = resource(created, "github").path("resourceId").asString()
		send("PUT", "/api/chapters/umd/projects/rise-dc/resources/$github", """{"audience":"team","access":"admin","requiresAgreement":true}""").andExpect { status { isOk() } }
		member(ada).andExpect { status { isOk() } }
		send("GET", "/api/me/home", as_ = "ada@hack4impact.org").andExpect {
			jsonPath("$.access[?(@.tool == 'github')].state") { value("agreement_not_set_up") } // no Documenso in these tests (package 6 review R1)
			jsonPath("$.access[?(@.tool == 'github')].access") { value("admin") }
		}
		waitForOutbox()
	}

	private fun <T> transactionsDo(block: () -> T): T = org.springframework.transaction.support.TransactionTemplate(transactionManager).execute { block() }!!
}
