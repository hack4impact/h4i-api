package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sync engine changing tools, against in-memory tools (full portal build plan step 9, ported for stage 2). */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"]) // the tests run the engine themselves
class SyncApplyTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
	@Autowired private val queue: org.hack4impact.portal.national.AdminQueue,
	@Autowired private val retries: SyncRetries,
) {
	private lateinit var umd: UUID
	private lateinit var project: UUID
	private lateinit var channel: UUID
	private lateinit var developer: UUID
	private val people = mutableMapOf<String, UUID>()

	private fun person(name: String, slackId: String?, status: String = "active"): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, name).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status)
			.set(PERSON.ORG_EMAIL, "${name.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				people[name] = id
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				slackId?.let { dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, it).execute() }
			}

	private fun member(name: String) =
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, people.getValue(name)).set(PROJECT_MEMBER.PROJECT_ROLE_ID, developer).execute()

	private fun applying(tool: String) = dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq(tool)).execute()

	private fun slackChannel() = dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1()

	private fun slackRun() = engine.run("manual", setOf(Tool.SLACK)).single()

	private fun outcomes(runId: UUID?) = dsl.select(SYNC_CHANGE.KIND, SYNC_CHANGE.OUTCOME).from(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(runId))
		.fetch { "${it.value1()}:${it.value2()}" }.sorted()

	private fun record(name: String) = dsl.selectFrom(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(people.getValue(name)), SYNC_RECORD.RESOURCE_ID.eq(channel)).fetchOne()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		people.clear()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Ada", "U-ada")
		person("Bo", "U-bo")
		person("Cy", null) // no Slack account yet
		developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		// Made in the portal (step 8), not created in Slack yet.
		channel = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise-dc").set(RESOURCE.CHAPTER_ID, umd)
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, channel)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		member("Ada")
		member("Bo")
		for (fake in listOf(slack, github)) { fake.failure = null; fake.failWrites = null; fake.writes.clear(); fake.resources = emptyMap(); fake.accounts = emptyList() }
		slack.accounts = listOf(
			ToolAccount("U-ada", "ada", "ada@hack4impact.org", null, AccountState.ACTIVE),
			ToolAccount("U-bo", "bo", "bo@hack4impact.org", null, AccountState.ACTIVE),
		)
	}

	@Test
	fun `a tool in dry run is never changed`() {
		val run = slackRun()
		assertEquals(emptyList(), slack.writes)
		assertEquals(0, run.applied)
		assertTrue(outcomes(run.runId).none { it.endsWith(":applied") })
	}

	@Test
	fun `out of dry run, the channel is created and the team added, and the next run has nothing to do`() {
		applying("slack")
		val first = slackRun()
		assertEquals("create umd-rise-dc", slack.writes.first()) // created before anyone is added
		assertEquals(setOf("grant new-umd-rise-dc U-ada WRITE", "grant new-umd-rise-dc U-bo WRITE"), slack.writes.drop(1).toSet())
		assertEquals("completed", first.status)
		assertEquals(2, first.applied)
		assertEquals(listOf("add:applied", "add:applied", "create_resource:applied"), outcomes(first.runId))
		assertEquals("new-umd-rise-dc", dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1())
		with(record("Ada")!!) {
			assertEquals("in_sync", status)
			assertNotNull(appliedAt)
		}

		slack.writes.clear()
		val second = slackRun()
		assertEquals(emptyList(), slack.writes) // idempotent: nothing left to change
		assertEquals(0, second.adds)
	}

	@Test
	fun `someone with two accounts in the tool gets access on their H4I one`() {
		applying("slack")
		// Her H4I account, and an old one the tool happens to list after it.
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, people.getValue("Ada")).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada-old").execute()
		slack.accounts = slack.accounts + ToolAccount("U-ada-old", "ada-old", "ada@gmail.test", null, AccountState.ACTIVE)
		slackRun()
		assertTrue("grant new-umd-rise-dc U-ada WRITE" in slack.writes)
		assertTrue(slack.writes.none { "U-ada-old" in it })
	}

	@Test
	fun `a just-created H4I account gets the access even before the tool lists it`() {
		applying("slack")
		// Ada's listed account is an old one; her H4I account was just created (Google's listing lags).
		dsl.deleteFrom(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(people.getValue("Ada"))).execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, people.getValue("Ada")).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada-old").execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, people.getValue("Ada")).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada-new")
			.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "ada@hack4impact.org").execute()
		slack.accounts = listOf(ToolAccount("U-ada-old", "ada-old", "ada@gmail.test", null, AccountState.ACTIVE), slack.accounts[1])
		slackRun()
		assertTrue("grant new-umd-rise-dc U-ada-new WRITE" in slack.writes)
		assertTrue(slack.writes.none { "U-ada-old" in it })
	}

	@Test
	fun `someone without an account the tool knows is invited or waited for, and still counts as granted`() {
		applying("slack")
		member("Cy")
		slackRun()
		assertTrue("invite new-umd-rise-dc cy@hack4impact.org" in slack.writes)
		with(record("Cy")!!) {
			assertEquals("waiting_on_member", status)
			assertNotNull(appliedAt) // the portal gave it, so the portal may take it back
		}
		// The next run doesn't invite again while the invitation is pending (second review M2)…
		slack.writes.clear()
		slackRun()
		assertTrue(slack.writes.none { it.startsWith("invite") }, slack.writes.toString())
		// …but a week later it does, as the first may have expired.
		dsl.update(SYNC_RECORD).set(SYNC_RECORD.LAST_ATTEMPT_AT, OffsetDateTime.now().minusDays(8)).where(SYNC_RECORD.PERSON_ID.eq(people.getValue("Cy"))).execute()
		slackRun()
		assertTrue(slack.writes.any { it.startsWith("invite") }, slack.writes.toString())
	}

	@Test
	fun `access the portal applied and no longer wants is removed, and access it never gave is left alone`() {
		applying("slack")
		slackRun()
		// Bo leaves the project; someone the portal never added (Ada's alt account) appears by hand.
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Bo"))).execute()
		person("Di", "U-di")
		slack.accounts = slack.accounts + ToolAccount("U-di", "di", null, null, AccountState.ACTIVE)
		val key = slack.resources.keys.single()
		slack.resources = mapOf(key to slack.resources.getValue(key) + ResourceMember("U-di", Access.WRITE))
		slack.writes.clear()

		val run = slackRun()
		assertEquals(listOf("revoke new-umd-rise-dc U-bo"), slack.writes)
		assertEquals(1, run.removed)
		assertEquals(1, run.drift) // Di stays: the portal never gave it
		with(record("Bo")!!) {
			assertEquals("absent", desired)
			assertEquals("in_sync", status)
			assertNull(appliedAt)
		}
	}

	@Test
	fun `access the portal raised is lowered again, and access raised by hand is left alone (decision 144)`() {
		applying("slack")
		slackRun() // Ada and Bo get write
		val access = { level: String -> dsl.update(PROJECT_RESOURCE).set(PROJECT_RESOURCE.ACCESS_LEVEL, level).where(PROJECT_RESOURCE.RESOURCE_ID.eq(channel)).execute() }
		access("admin")
		slackRun()
		assertEquals("admin", record("Ada")!!.appliedAccessLevel)
		// Back to write: the portal lowers what it raised.
		slack.writes.clear()
		access("write")
		val lowered = slackRun()
		assertEquals(listOf("grant ${slackChannel()} U-ada WRITE", "grant ${slackChannel()} U-bo WRITE"), slack.writes.sorted())
		assertTrue(outcomes(lowered.runId).count { it == "change:applied" } == 2)
		// Raised by hand in Slack above what the portal gave: reported as drift, not lowered.
		slack.resources = slack.resources.mapValues { (_, members) -> members.map { if (it.accountId == "U-bo") it.copy(access = Access.ADMIN) else it } }
		slack.writes.clear()
		val handRaised = slackRun()
		assertTrue(slack.writes.isEmpty(), slack.writes.toString())
		assertTrue("drift:null" in outcomes(handRaised.runId))
	}

	@Test
	fun `over the blast-radius limit removals are held for national while adds still happen`() {
		applying("slack")
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slackRun()
		// Not something a lead did to the team: Bo stopped being a member at all (a lead's removal isn't held, decision 178).
		dsl.update(PERSON).set(PERSON.STATUS, "removed").where(PERSON.ID.eq(people.getValue("Bo"))).execute()
		member("Cy")
		slack.writes.clear()

		val run = slackRun()
		assertEquals("paused", run.status)
		assertEquals(listOf("invite new-umd-rise-dc cy@hack4impact.org"), slack.writes) // no revoke
		assertTrue("remove:held" in outcomes(run.runId))
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.RUN_ID.eq(run.runId)).fetchSingle()
		assertEquals("confirm_removals", task.action)
		assertEquals("open", task.status)
	}

	@Test
	fun `a failed change is retried with backoff, then becomes a dead letter`() {
		applying("slack")
		slack.resources = mapOf(ToolResource("C1", "umd-rise-dc") to emptyList())
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "C1").where(RESOURCE.ID.eq(channel)).execute()
		slack.failWrites = Unavailable(Tool.SLACK, "down")

		slackRun()
		with(record("Ada")!!) {
			assertEquals("failed", status)
			assertEquals(1, attempts)
			assertTrue(nextRetryAt!!.isAfter(OffsetDateTime.now()))
		}
		val tooSoon = slackRun()
		assertTrue(outcomes(tooSoon.runId).all { it == "add:skipped" }) // not due yet
		assertEquals(1, record("Ada")!!.attempts)

		dsl.update(SYNC_RECORD).set(SYNC_RECORD.ATTEMPTS, SyncApplier.MAX_ATTEMPTS - 1).set(SYNC_RECORD.NEXT_RETRY_AT, OffsetDateTime.now().minusMinutes(1)).execute()
		slackRun()
		assertEquals("dead_letter", record("Ada")!!.status)

		slack.failWrites = null
		val waiting = slackRun()
		assertTrue(outcomes(waiting.runId).all { it == "add:skipped" }) // no more automatic retries
		assertEquals("dead_letter", record("Ada")!!.status)

		dsl.update(SYNC_RECORD).set(SYNC_RECORD.STATUS, "pending").set(SYNC_RECORD.ATTEMPTS, 0).execute() // a person resets it
		slackRun()
		assertEquals("in_sync", record("Ada")!!.status)
	}

	@Test
	fun `a resource that can't be created backs off, then waits on the admin queue until national retries it (second review L3)`() {
		applying("slack")
		slack.failWrites = Unavailable(Tool.SLACK, "down")
		slackRun()
		val tries = { dsl.select(RESOURCE.CREATE_ATTEMPTS, RESOURCE.CREATE_RETRY_AT).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle() }
		assertEquals(1, tries().value1())
		assertTrue(tries().value2()!!.isAfter(OffsetDateTime.now()))
		slack.writes.clear()
		assertTrue("create_resource:skipped" in outcomes(slackRun().runId)) // not due yet
		assertTrue(slack.writes.isEmpty(), slack.writes.toString())

		dsl.update(RESOURCE).set(RESOURCE.CREATE_ATTEMPTS, SyncApplier.MAX_ATTEMPTS - 1).set(RESOURCE.CREATE_RETRY_AT, OffsetDateTime.now().minusMinutes(1)).where(RESOURCE.ID.eq(channel)).execute()
		slackRun()
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq("create_resource")).fetchSingle()
		assertEquals(channel, task.resourceId)
		assertTrue("down" in task.description!!, task.description)
		slack.failWrites = null
		assertTrue("create_resource:skipped" in outcomes(slackRun().runId)) // not on its own any more
		slackRun()
		assertEquals(1, dsl.fetchCount(ADMIN_TASK, ADMIN_TASK.ACTION.eq("create_resource"))) // one task, not one per run

		queue.done(task.id!!, org.hack4impact.portal.auth.Viewer(null, "national", "National", true, emptyMap()))
		assertEquals(0, tries().value1())
		assertTrue("create_resource:applied" in outcomes(slackRun().runId))
	}

	@Test
	fun `bad credentials stop the tool's run at once`() {
		applying("slack")
		slack.failWrites = AuthFailed(Tool.SLACK, "invalid_auth")
		val run = slackRun()
		assertEquals("failed", run.status)
		assertEquals(listOf("add:skipped", "add:skipped", "create_resource:failed"), outcomes(run.runId))
	}

	@Test
	fun `closing a project empties its channel and archives it, and the removal limit doesn't hold it`() {
		applying("slack")
		slackRun()
		dsl.update(PROJECT).set(PROJECT.STATUS, "closed").where(PROJECT.ID.eq(project)).execute()
		// Even the strictest limit: a close is the lead's own act (decision 178), and it removes the whole team.
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slack.writes.clear()
		val run = slackRun()
		assertEquals("completed", run.status)
		assertEquals("archive new-umd-rise-dc", slack.writes.last()) // after both members are out
		assertEquals(setOf("revoke new-umd-rise-dc U-ada", "revoke new-umd-rise-dc U-bo"), slack.writes.dropLast(1).toSet())
		assertEquals(1, run.archives)
		assertNotNull(dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1())
	}

	@Test
	fun `access someone has through their role in the tool is left alone`() {
		applying("slack")
		slack.resources = mapOf(ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U-ada", Access.ADMIN, implicit = true)))
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "C1").where(RESOURCE.ID.eq(channel)).execute()
		val run = slackRun()
		assertEquals(listOf("grant C1 U-bo WRITE"), slack.writes) // Ada isn't added: her role already gives it
		assertEquals(0, run.drift)

		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Ada"))).execute()
		slack.writes.clear()
		val after = slackRun()
		assertEquals(emptyList(), slack.writes) // and never removed or reported as drift
		assertEquals(0, after.drift)
	}

	@Test
	fun `a create the tool refuses because the name is taken goes to national at once, without retries`() {
		applying("slack")
		// Slack's listing didn't show it (a private channel the app isn't in), but the name is taken.
		slack.failWrites = Rejected(Tool.SLACK, "conversations.create: name_taken (probably an existing channel the portal can't see)")
		val run = slackRun()
		assertEquals(listOf("add:skipped", "add:skipped", "resource_exists:null"), outcomes(run.runId))
		with(dsl.selectFrom(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle()) {
			assertNotNull(existsInToolAt)
			assertNull(foundExternalId)
			assertEquals(0, createAttempts)
		}
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq("resource_exists")).fetchSingle()
		assertTrue("invite the portal's Slack app" in task.description!!, task.description)

		slack.failWrites = null
		slack.writes.clear()
		val next = slackRun()
		assertEquals(emptyList(), slack.writes) // left alone until national decides
		assertEquals(emptyList(), outcomes(next.runId))
	}

	@Test
	fun `closing a project empties a channel national linked but doesn't archive it (decision 170)`() {
		applying("slack")
		slack.resources = mapOf(ToolResource("C-old", "umd-rise-dc") to listOf(ResourceMember("U-ada", Access.WRITE)))
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "C-old").set(RESOURCE.MANAGED, "adopted").where(RESOURCE.ID.eq(channel)).execute()
		slackRun() // Bo is added; Ada was already there
		dsl.update(PROJECT).set(PROJECT.STATUS, "closed").where(PROJECT.ID.eq(project)).execute()
		slack.writes.clear()

		val run = slackRun()
		assertEquals(listOf("revoke C-old U-bo"), slack.writes) // only who the portal added; Ada stays, the channel stays
		assertEquals(0, run.archives)
		assertNull(dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1())
	}

	private fun missingTasks() = dsl.fetchCount(
		org.hack4impact.portal.db.tables.references.ADMIN_TASK,
		org.hack4impact.portal.db.tables.references.ADMIN_TASK.ACTION.eq("missing_resource"), org.hack4impact.portal.db.tables.references.ADMIN_TASK.STATUS.eq("open"),
	)

	@Test
	fun `a channel deleted in Slack is left out of the plan and goes to national once, until it's back`() {
		applying("slack")
		slackRun()
		val created = slack.resources
		slack.resources = emptyMap() // deleted in Slack
		slack.writes.clear()

		val run = slackRun()
		assertEquals(emptyList(), slack.writes) // nobody is added to what isn't there: no failures, no dead letters
		assertEquals(0, run.adds)
		assertEquals(1, run.missingResources)
		slackRun()
		assertEquals(1, missingTasks())

		slack.resources = created
		slackRun()
		assertEquals(0, missingTasks())
	}

	@Test
	fun `someone whose only Slack account is deactivated is waited for, not invited by it`() {
		slack.accounts = slack.accounts.map { if (it.externalId == "U-bo") it.copy(state = AccountState.SUSPENDED) else it }
		applying("slack")
		slackRun()
		assertTrue(slack.writes.none { "U-bo" in it }, slack.writes.toString())
		assertEquals("waiting_on_member", record("Bo")!!.status)
	}

	@Test
	fun `the retry pass asks for tools with unfinished work, only when they're out of dry run`() {
		member("Cy") // no Slack account: waited for
		val later = java.time.OffsetDateTime.now().plusHours(2)
		slackRun()
		assertEquals(emptySet(), retries.due(later)) // in dry run: nothing a run could finish
		applying("slack")
		slackRun()
		assertEquals("waiting_on_member", record("Cy")!!.status)
		assertEquals(emptySet(), retries.due(java.time.OffsetDateTime.now())) // just looked
		assertEquals(setOf(Tool.SLACK), retries.due(later))
	}

	@Test
	fun `a member a lead removes isn't held, while other removals in the same run still are`() {
		applying("slack")
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slackRun()
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Bo"))).execute()
		slack.writes.clear()
		val lead = slackRun()
		assertEquals("completed", lead.status)
		assertEquals(listOf("revoke new-umd-rise-dc U-bo"), slack.writes)

		// Ada is still on the team but stopped being a member: nobody asked for that removal, so it waits.
		dsl.update(PERSON).set(PERSON.STATUS, "removed").where(PERSON.ID.eq(people.getValue("Ada"))).execute()
		slack.writes.clear()
		val other = slackRun()
		assertEquals("paused", other.status)
		assertEquals(emptyList(), slack.writes)
	}
}
