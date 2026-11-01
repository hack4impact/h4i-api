package org.hack4impact.portal.national

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
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
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Linking existing resources by hand and chapter resources (stage 2 package 4, decision 170), on the fake Slack and
 * GitHub: what each package 4 case on the stage 2 page says it must show.
 */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"]) // the tests run the engine themselves
@AutoConfigureMockMvc
class ResourceLinkTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
) {
	private lateinit var umd: UUID
	private lateinit var lena: UUID
	private lateinit var ada: UUID
	private lateinit var alan: UUID
	private lateinit var project: UUID
	private lateinit var template: UUID

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun call(method: String, path: String, body: String? = null, as_: String = "nora@hack4impact.org"): ResultActionsDsl {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(signedIn(as_)); with(csrf())
			if (body != null) { contentType = MediaType.APPLICATION_JSON; content = body }
		}
		return when (method) {
			"POST" -> mvc.post(path) { setup() }
			"DELETE" -> mvc.delete(path) { setup() }
			else -> mvc.get(path) { setup() }
		}
	}

	private fun person(first: String, status: String = "active", slackId: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				slackId?.let { dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, it).execute() }
			}

	private fun channel(id: String, name: String, vararg members: String) = ToolResource(id, name) to members.map { ResourceMember(it, Access.WRITE) }

	private fun applying(tool: String) = dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq(tool)).execute()

	private fun slackRun() = engine.run("manual", setOf(Tool.SLACK)).single()

	private fun preview(id: UUID, query: String) = "/api/national/resources/$id/link?$query"

	private fun startChapterLink(name: String): UUID {
		val body = call("POST", "/api/national/chapters/umd/resources", """{"tool":"slack","name":"$name"}""").andExpect { status { isOk() } }
			.andReturn().response.contentAsString
		return UUID.fromString(Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1])
	}

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		lena = person("Lena", slackId = "U-lena").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		ada = person("Ada", slackId = "U-ada")
		alan = person("Alan", status = "alumni", slackId = "U-alan")
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, ada).set(PROJECT_MEMBER.PROJECT_ROLE_ID, developer).execute()
		// The project's channel from the template, not created yet; Slack already has one with that name.
		template = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise-dc").set(RESOURCE.CHAPTER_ID, umd).returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, template)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		for (fake in listOf(slack, github)) { fake.failure = null; fake.failWrites = null; fake.writes.clear(); fake.resources = emptyMap(); fake.accounts = emptyList() }
		slack.accounts = listOf("lena", "ada", "alan").map { ToolAccount("U-$it", it, "$it@hack4impact.org", null, AccountState.ACTIVE) }
		slack.resources = mapOf(
			channel("C-rise", "umd-rise-dc", "U-alan", "U-stranger"), // an alumnus from an earlier semester, and an account nobody knows
			channel("C-umd", "umd", "U-lena"),
		)
	}

	@Test
	fun `a template name that exists is previewed, linked with the confirmed count, and then only gains people`() {
		engine.dryRun("manual", setOf(Tool.SLACK)) // look before creating: the task, and nothing created
		call("GET", preview(template, "target=project_team&projectSlug=rise-dc")).andExpect {
			status { isOk() }
			jsonPath("$.resource.externalId") { value("C-rise") }
			jsonPath("$.willAdd") { value(1) }
			jsonPath("$.people.length()") { value(3) }
			jsonPath("$.people[?(@.verdict == 'will_add')].name") { value("Ada Test") }
			jsonPath("$.people[?(@.verdict == 'kept')].name") { value("Alan Test") }
			jsonPath("$.people[?(@.verdict == 'unknown_account')].account") { value("U-stranger") }
		}
		val link = { adds: Int -> call("POST", "/api/national/resources/$template/link", """{"action":"link","target":"project_team","projectSlug":"rise-dc","confirmedAdds":$adds}""") }
		link(2).andExpect { status { isConflict() } } // not what the preview showed
		link(1).andExpect {
			status { isOk() }
			jsonPath("$.managed") { value("adopted") }
			jsonPath("$.externalId") { value("C-rise") }
			jsonPath("$.existsInTool") { value(false) }
		}
		assertEquals("done", dsl.select(ADMIN_TASK.STATUS).from(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq("resource_exists")).fetchSingle().value1())
		assertEquals(1, dsl.fetchCount(PROJECT_RESOURCE, PROJECT_RESOURCE.RESOURCE_ID.eq(template))) // the template's link is kept, not doubled
		assertTrue(dsl.fetchExists(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%SLACK%'"))) // a Slack run asked for

		applying("slack")
		slackRun()
		assertEquals(listOf("grant C-rise U-ada WRITE"), slack.writes) // Alan and the stranger stay
		// Ada leaves: the portal takes back what it added; Alan, who was already there, keeps it.
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).execute()
		slack.writes.clear()
		slackRun()
		assertEquals(listOf("revoke C-rise U-ada"), slack.writes)
	}

	@Test
	fun `a chapter's own channel is started, linked for its members, and follows who is active`() {
		val umdChannel = startChapterLink("#UMD")
		call("GET", "/api/national/chapters/umd/resources").andExpect {
			jsonPath("$[?(@.name == 'umd')].managed") { value("unmanaged") }
			jsonPath("$[?(@.name == 'umd')].existsInTool") { value(false) } // unmanaged: nothing for the portal to do until linked
		}
		call("GET", preview(umdChannel, "target=chapter_members")).andExpect {
			jsonPath("$.willAdd") { value(1) } // Ada; Alan is an alumnus
			jsonPath("$.people[?(@.verdict == 'already_there')].name") { value("Lena Test") }
		}
		call("POST", "/api/national/resources/$umdChannel/link", """{"action":"link","target":"chapter_members","confirmedAdds":1}""").andExpect { status { isOk() } }
		assertEquals("members", dsl.select(CHAPTER_RESOURCE.AUDIENCE).from(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(umdChannel)).fetchSingle().value1())
		call("POST", "/api/national/chapters/umd/resources", """{"tool":"slack","name":"umd"}""").andExpect { status { isConflict() } } // linked already

		applying("slack")
		dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(template)).execute() // only the chapter channel here
		slackRun()
		assertEquals(listOf("grant C-umd U-ada WRITE"), slack.writes)
		// A new member is approved: added at the next sync. Ada becomes an alumna: taken out (the portal added her).
		person("Nia", slackId = "U-nia")
		slack.accounts = slack.accounts + ToolAccount("U-nia", "nia", "nia@hack4impact.org", null, AccountState.ACTIVE)
		dsl.update(PERSON).set(PERSON.STATUS, "alumni").where(PERSON.ID.eq(ada)).execute()
		slack.writes.clear()
		slackRun()
		assertEquals(setOf("grant C-umd U-nia WRITE", "revoke C-umd U-ada"), slack.writes.toSet())
	}

	@Test
	fun `detaching takes back only what the portal added, and unmanaging removes no one`() {
		val umdChannel = startChapterLink("umd")
		call("POST", "/api/national/resources/$umdChannel/link", """{"action":"link","target":"chapter_members","confirmedAdds":1}""").andExpect { status { isOk() } }
		applying("slack")
		dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(template)).execute()
		slackRun()
		slack.writes.clear()

		call("DELETE", "/api/national/resources/$template?mode=detach").andExpect { status { isUnprocessableContent() } } // not linked
		call("DELETE", "/api/national/resources/$umdChannel?mode=archive").andExpect { status { isUnprocessableContent() } }
		call("DELETE", "/api/national/resources/$umdChannel?mode=detach").andExpect { status { isNoContent() } }
		slackRun()
		assertEquals(listOf("revoke C-umd U-ada"), slack.writes) // Lena was there before the link: she stays

		// Linked again (a detached resource may be), then unmanaged: nobody is removed, and nothing more is changed.
		call("POST", "/api/national/resources/$umdChannel/link", """{"action":"link","target":"chapter_members","confirmedAdds":1}""").andExpect { status { isOk() } }
		slackRun()
		slack.writes.clear()
		call("DELETE", "/api/national/resources/$umdChannel?mode=unmanaged").andExpect { status { isNoContent() } }
		slackRun()
		assertEquals(emptyList(), slack.writes)
	}

	@Test
	fun `renaming creates the portal's own under the new name and leaves the existing one`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		val rename = { name: String -> call("POST", "/api/national/resources/$template/link", """{"action":"rename","newName":"$name"}""") }
		rename("Bad Name!").andExpect { status { isUnprocessableContent() } }
		rename("#UMD-Rise-DC-2026").andExpect {
			status { isOk() }
			jsonPath("$.name") { value("umd-rise-dc-2026") }
			jsonPath("$.managed") { value("portal") }
		}
		applying("slack")
		slackRun()
		assertEquals("create umd-rise-dc-2026", slack.writes.first())
		assertEquals(listOf("U-alan", "U-stranger"), slack.members("C-rise").map { it.accountId }) // the old one untouched
	}

	@Test
	fun `leaving it unmanaged closes the task and the portal stops trying`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		call("POST", "/api/national/resources/$template/link", """{"action":"unmanaged"}""").andExpect { jsonPath("$.managed") { value("unmanaged") } }
		assertEquals("done", dsl.select(ADMIN_TASK.STATUS).from(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq("resource_exists")).fetchSingle().value1())
		applying("slack")
		val run = slackRun()
		assertEquals(emptyList(), slack.writes)
		assertEquals(0, run.adds + run.creates)
	}

	@Test
	fun `a linked GitHub team keeps the agreement gate`() {
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_ID, "101").execute()
		github.accounts = listOf(ToolAccount("101", "ada-gh", null, null, AccountState.ACTIVE))
		github.resources = mapOf(ToolResource("umd-rise-dc", "UMD RISE DC") to emptyList())
		val body = call("POST", "/api/national/chapters/umd/resources", """{"tool":"github","externalId":"umd-rise-dc"}""").andReturn().response.contentAsString
		val team = UUID.fromString(Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1])

		call("GET", preview(team, "target=project_team&projectSlug=rise-dc")).andExpect { jsonPath("$.willAdd") { value(0) } } // Ada hasn't signed
		call("POST", "/api/national/resources/$team/link", """{"action":"link","target":"project_team","projectSlug":"rise-dc","confirmedAdds":0}""").andExpect { status { isOk() } }
		assertEquals(true, dsl.select(PROJECT_RESOURCE.REQUIRES_AGREEMENT).from(PROJECT_RESOURCE).where(PROJECT_RESOURCE.RESOURCE_ID.eq(team)).fetchSingle().value1())
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").execute()
		call("GET", preview(team, "target=project_team&projectSlug=rise-dc")).andExpect { jsonPath("$.willAdd") { value(1) } }
	}

	@Test
	fun `only national links, an external ID links once, and a target must fit`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		call("GET", preview(template, "target=project_team&projectSlug=rise-dc"), as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		call("POST", "/api/national/resources/$template/link", """{"action":"unmanaged"}""", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		call("POST", "/api/national/chapters/umd/resources", """{"tool":"slack","name":"umd"}""", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }

		call("GET", preview(template, "target=project_team&projectSlug=nope")).andExpect { status { isUnprocessableContent() } }
		call("GET", preview(template, "target=everyone")).andExpect { status { isUnprocessableContent() } }
		call("POST", "/api/national/resources/$template/link", """{"action":"link","target":"project_team","projectSlug":"rise-dc"}""").andExpect { status { isUnprocessableContent() } }
		call("POST", "/api/national/chapters/umd/resources", """{"tool":"slack","name":"umd","externalId":"C-umd"}""").andExpect { status { isUnprocessableContent() } }
		call("POST", "/api/national/chapters/umd/resources", """{"tool":"slack","name":"umd-secret"}""").andExpect {
			status { isNotFound() }
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("invite the portal's Slack app")) }
		}

		// C-rise already belongs to another portal resource.
		dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise-dc-old").set(RESOURCE.EXTERNAL_ID, "C-rise").set(RESOURCE.MANAGED, "adopted").set(RESOURCE.CHAPTER_ID, umd).execute()
		call("POST", "/api/national/resources/$template/link", """{"action":"link","target":"project_team","projectSlug":"rise-dc","confirmedAdds":1}""").andExpect { status { isConflict() } }
		assertNull(dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(template)).fetchSingle().value1())
	}
}
