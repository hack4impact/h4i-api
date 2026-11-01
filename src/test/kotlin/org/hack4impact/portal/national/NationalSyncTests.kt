package org.hack4impact.portal.national

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * National's sync controls (stage 2 package 4): the syncing tools' switches, held removals, dead letters, the queue's
 * sync tasks, and a chapter's sync status. From the full portal's `NationalTests`, on the Slack fake instead of Notion.
 */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"]) // the tests run the engine themselves
@AutoConfigureMockMvc
class NationalSyncTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
) {
	private lateinit var umd: UUID
	private lateinit var ada: UUID

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
			"PUT" -> mvc.put(path) { setup() }
			else -> mvc.get(path) { setup() }
		}
	}

	private fun person(first: String, email: String, role: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, email)
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, it).execute() }
			}

	private fun openTasks(action: String) = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq(action), ADMIN_TASK.STATUS.eq("open")).fetch()

	private fun slackRun() = engine.run("manual", setOf(Tool.SLACK)).single()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "University of Maryland").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lena@hack4impact.org", role = "lead")
		person("Vic", "vic@hack4impact.org", role = "viewer")
		ada = person("Ada", "ada@hack4impact.org")
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		slack.failure = null; slack.failWrites = null; slack.writes.clear(); slack.resources = emptyMap(); slack.accounts = emptyList()
		// Ada on a live project with a portal-made channel.
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada").execute()
		slack.accounts = listOf(ToolAccount("U-ada", "ada", "ada@hack4impact.org", null, AccountState.ACTIVE))
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE").set(PROJECT.SLUG, "rise").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val channel = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise").set(RESOURCE.CHAPTER_ID, umd).returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, channel).set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		val developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, ada).set(PROJECT_MEMBER.PROJECT_ROLE_ID, developer).execute()
	}

	@Test
	fun `held removals go ahead once national confirms them`() {
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slackRun()
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).execute()

		val seen = slackRun()
		assertEquals("paused", seen.status)
		val held = slackRun() // a later run rewrites the task
		val confirm = openTasks("confirm_removals").single()
		// Only the run the page showed can be confirmed (second review L4).
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done","runId":"${seen.runId}"}""").andExpect { status { isConflict() } }
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done"}""").andExpect { status { isConflict() } }
		val requested = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%SLACK%'")) }
		val before = requested()
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done","runId":"${held.runId}"}""").andExpect { status { isOk() } }
		assertEquals(before + 1, requested()) // confirming asks for a Slack run (review M9); the test makes it below
		slack.writes.clear()
		val confirmed = slackRun()
		assertEquals("completed", confirmed.status)
		assertEquals(listOf("revoke new-umd-rise U-ada"), slack.writes)
	}

	@Test
	fun `only national changes tools, only syncing tools have switches, and leaving dry run needs the write switch`() {
		call("GET", "/api/national/tools", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		call("GET", "/api/national/tools").andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(4) } // slack, google, github, vaultwarden: no Documenso or Notion
			jsonPath("$[0].tool") { value("slack") }
			jsonPath("$[3].tool") { value("vaultwarden") }
			jsonPath("$[?(@.tool == 'slack')].writable") { value(true) } // the fake has writes
			jsonPath("$[?(@.tool == 'google')].writable") { value(false) }
		}
		call("PUT", "/api/national/tools/documenso", """{"enabled":true,"dryRun":false}""").andExpect { status { isNotFound() } }
		call("PUT", "/api/national/tools/google", """{"enabled":true,"dryRun":false}""").andExpect { status { isConflict() } }
		call("PUT", "/api/national/tools/slack", """{"enabled":false,"dryRun":true}""").andExpect { status { isBadRequest() } }
		call("PUT", "/api/national/tools/slack", """{"enabled":false,"dryRun":true,"reason":"Slack outage"}""").andExpect {
			status { isOk() }
			jsonPath("$[?(@.tool == 'slack')].pauseReason") { value("Slack outage") }
		}
		assertEquals(false, dsl.select(TOOL_SETTING.ENABLED).from(TOOL_SETTING).where(TOOL_SETTING.TOOL.eq("slack")).fetchSingle().value1())
	}

	@Test
	fun `dead letters are listed and put back in line`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		dsl.update(SYNC_RECORD).set(SYNC_RECORD.STATUS, "dead_letter").set(SYNC_RECORD.ATTEMPTS, 5).set(SYNC_RECORD.LAST_ERROR, "boom").where(SYNC_RECORD.PERSON_ID.eq(ada)).execute()
		call("GET", "/api/national/dead-letters").andExpect {
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].person") { value("Ada Test") }
			jsonPath("$[0].lastError") { value("boom") }
		}
		call("POST", "/api/national/dead-letters/retry", "[]").andExpect { jsonPath("$.length()") { value(0) } }
		assertEquals("pending", dsl.select(SYNC_RECORD.STATUS).from(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle().value1())
	}

	@Test
	fun `tasks about a person but no resource are left alone by the sync, and a name that exists is decided elsewhere`() {
		val other = dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, "slack").set(ADMIN_TASK.ACTION, "create_account").set(ADMIN_TASK.PERSON_ID, ada)
			.set(ADMIN_TASK.DESCRIPTION, "Create an account for Ada").returningResult(ADMIN_TASK.ID).fetchSingle().value1()!!
		slack.resources = mapOf(org.hack4impact.portal.adapters.ToolResource("C-rise", "umd-rise") to emptyList())
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slackRun()
		assertEquals("open", dsl.select(ADMIN_TASK.STATUS).from(ADMIN_TASK).where(ADMIN_TASK.ID.eq(other)).fetchSingle().value1())

		val exists = openTasks("resource_exists").single()
		call("GET", "/api/national/queue").andExpect { jsonPath("$[?(@.action == 'resource_exists')].resourceId") { value(exists.resourceId.toString()) } }
		call("POST", "/api/national/queue/${exists.id}", """{"action":"done"}""").andExpect { status { isConflict() } }
		call("POST", "/api/national/queue/${exists.id}", """{"action":"cancel"}""").andExpect { status { isConflict() } }
	}

	@Test
	fun `a chapter's leads see its sync status and ask for a dry run, and viewers and others can't`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		call("GET", "/api/chapters/umd/sync", as_ = "lena@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$.tools[0].tool") { value("slack") }
			jsonPath("$.tools[0].creates") { value(1) }
			jsonPath("$.changes[?(@.kind == 'create_resource')].resource") { value("umd-rise") }
		}
		call("GET", "/api/chapters/umd/sync", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		call("GET", "/api/chapters/umd/sync", as_ = "ada@hack4impact.org").andExpect { status { isForbidden() } }
		call("POST", "/api/chapters/umd/sync/run", as_ = "lena@hack4impact.org").andExpect { status { isAccepted() } }
		call("POST", "/api/chapters/umd/sync/run", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
	}
}
