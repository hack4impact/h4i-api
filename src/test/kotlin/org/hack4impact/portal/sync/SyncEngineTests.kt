package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.EVENT_PUBLICATION
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
class SyncEngineTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val worlds: WorldLoader,
	@Autowired private val requests: SyncRequests,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
) {
	private lateinit var umd: UUID
	private lateinit var lena: UUID
	private lateinit var ada: UUID
	private lateinit var alan: UUID
	private lateinit var riseChannel: UUID
	private lateinit var generalChannel: UUID

	private fun person(name: String, status: String = "active"): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, name).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute() }

	private fun account(person: UUID, tool: String, id: String) =
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, tool).set(TOOL_ACCOUNT.EXTERNAL_ID, id).execute()

	private fun resource(tool: String, externalId: String?, name: String, managed: String = "portal", archived: Boolean = false): UUID =
		dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, tool).set(RESOURCE.EXTERNAL_ID, externalId).set(RESOURCE.NAME, name).set(RESOURCE.CHAPTER_ID, umd)
			.set(RESOURCE.MANAGED, managed).set(RESOURCE.ARCHIVED_AT, if (archived) OffsetDateTime.now() else null)
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!

	private fun changes(runId: UUID?) = dsl.selectFrom(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(runId)).fetch()

	private fun channel(id: String, vararg members: Pair<String, Access>) = ToolResource(id, id) to members.map { ResourceMember(it.first, it.second) }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.ENABLED, true).execute()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		lena = person("Lena").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		ada = person("Ada")
		alan = person("Alan", status = "alumni")
		account(lena, "slack", "U-lena")
		account(ada, "slack", "U-ada")
		account(alan, "slack", "U-alan")
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, ada).set(PROJECT_MEMBER.PROJECT_ROLE_ID, developer)
			.set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").execute()
		riseChannel = resource("slack", "C-rise", "#umd-rise-dc")
		generalChannel = resource("slack", "C-general", "#umd-general")
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, riseChannel)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, umd).set(CHAPTER_RESOURCE.RESOURCE_ID, generalChannel)
			.set(CHAPTER_RESOURCE.AUDIENCE, "members").set(CHAPTER_RESOURCE.ACCESS_LEVEL, "write").execute()
		resource("slack", "C-handmade", "#umd-random", managed = "unmanaged")
		resource("slack", "C-old", "#umd-old", archived = true)
		slack.failure = null
		github.failure = null
		github.accounts = emptyList()
		github.resources = emptyMap()
		// Actual Slack: Alan (alumni) is still in #umd-rise-dc, plus someone the portal doesn't know.
		// #umd-general has Lena only. Unmanaged and archived channels have members that must be ignored.
		slack.accounts = listOf(ToolAccount("U-lena", "lena", null, null, AccountState.ACTIVE))
		slack.resources = mapOf(
			channel("C-rise", "U-alan" to Access.WRITE, "U-stranger" to Access.WRITE),
			channel("C-general", "U-lena" to Access.WRITE),
			channel("C-handmade", "U-ada" to Access.WRITE),
			channel("C-old", "U-ada" to Access.WRITE),
		)
	}

	@Test
	fun `a dry run produces exactly the expected diff and never plans removals of access it didn't grant`() {
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals("completed", report.status)
		val found = changes(report.runId).map { Triple(it.kind, it.personId, it.resourceId) }.toSet()
		assertEquals(
			setOf(
				Triple("add", ada, riseChannel), // project member, missing from the project channel
				Triple("add", ada, generalChannel), // active chapter member, missing from the chapter channel
				Triple("drift", alan, riseChannel), // alumnus still in the channel; the portal never granted it, so no removal
				Triple("unmatched_account", null, riseChannel), // a Slack account nobody in the portal is linked to
			),
			found,
		)
		assertEquals(Pair(2, 0), report.adds to report.removals)
		assertEquals("U-stranger", changes(report.runId).single { it.kind == "unmatched_account" }.accountId)
		assertTrue(changes(report.runId).none { it.resourceId in dsl.select(RESOURCE.ID).from(RESOURCE).where(RESOURCE.NAME.`in`("#umd-random", "#umd-old")).fetch(RESOURCE.ID) })
	}

	@Test
	fun `sync records say what should be, what is, and why, and a dry run never marks anything applied`() {
		engine.dryRun("manual", setOf(Tool.SLACK))
		val records = dsl.selectFrom(SYNC_RECORD).fetch().associateBy { it.personId to it.resourceId }
		assertEquals("in_sync", records.getValue(lena to generalChannel).status)
		assertEquals("pending", records.getValue(ada to riseChannel).status)
		assertEquals("absent", records.getValue(ada to riseChannel).actual)
		assertTrue(records.getValue(ada to riseChannel).reasons!!.data().contains("project_member"))
		assertTrue(records.values.all { it.appliedAt == null })
	}

	@Test
	fun `access the portal granted earlier and no longer wants is planned for removal, with its old reasons`() {
		dsl.insertInto(SYNC_RECORD).set(SYNC_RECORD.PERSON_ID, alan).set(SYNC_RECORD.RESOURCE_ID, riseChannel).set(SYNC_RECORD.DESIRED, "present")
			.set(SYNC_RECORD.REASONS, JSONB.valueOf("""[{"type":"project_member","projectId":"${UUID.randomUUID()}"}]"""))
			.set(SYNC_RECORD.APPLIED_AT, OffsetDateTime.now()).execute()
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		val removal = changes(report.runId).single { it.kind == "remove" }
		assertEquals(alan to riseChannel, removal.personId to removal.resourceId)
		assertTrue(removal.reasons!!.data().contains("project_member"))
		assertEquals(1, report.removals)
		assertEquals(0, report.drift)
	}

	@Test
	fun `a grant a dry run only recorded, never applied, is drift when no longer wanted, not a removal`() {
		engine.dryRun("manual", setOf(Tool.SLACK)) // records Ada -> #umd-rise-dc as pending, never applied
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(ada)).execute()
		slack.resources = slack.resources + channel("C-rise", "U-ada" to Access.WRITE) // someone added her by hand
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		val ofAda = changes(report.runId).filter { it.personId == ada && it.resourceId == riseChannel }.map { it.kind }
		assertEquals(listOf("drift"), ofAda)
		assertEquals(0, report.removals)
	}

	@Test
	fun `running twice plans the same and doesn't duplicate sync records`() {
		val first = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		val second = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals(first.copy(runId = null), second.copy(runId = null))
		assertEquals(2 + 1, dsl.fetchCount(SYNC_RECORD)) // Ada x2, Lena x1
	}

	@Test
	fun `a resource deleted in the tool is reported missing`() {
		slack.resources = slack.resources.filterKeys { it.externalId != "C-general" }
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals(1, report.missingResources)
		assertEquals(generalChannel, changes(report.runId).single { it.kind == "missing_resource" }.resourceId)
	}

	@Test
	fun `the kill switch pauses a tool, and one failing tool doesn't stop the others`() {
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.ENABLED, false).where(TOOL_SETTING.TOOL.eq("github")).execute()
		assertEquals("paused", engine.dryRun("manual").single { it.tool == Tool.GITHUB }.status)

		dsl.update(TOOL_SETTING).set(TOOL_SETTING.ENABLED, true).execute()
		github.failure = Unavailable(Tool.GITHUB, "down")
		val reports = engine.dryRun("manual").associateBy { it.tool }
		assertEquals("failed", reports.getValue(Tool.GITHUB).status)
		assertEquals("completed", reports.getValue(Tool.SLACK).status)
		assertEquals("failed", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(reports.getValue(Tool.GITHUB).runId)).fetchSingle().value1())
	}

	@Test
	fun `a plan over the blast-radius limit is flagged as one a real run would pause`() {
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 2).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		val extra = (1..3).map { i -> person("Gone$i", status = "alumni").also { account(it, "slack", "U-gone$i") } }
		extra.forEach {
			dsl.insertInto(SYNC_RECORD).set(SYNC_RECORD.PERSON_ID, it).set(SYNC_RECORD.RESOURCE_ID, generalChannel).set(SYNC_RECORD.DESIRED, "present")
				.set(SYNC_RECORD.APPLIED_AT, OffsetDateTime.now()).set(SYNC_RECORD.REASONS, JSONB.valueOf("""[{"type":"chapter_member","chapterId":"$umd"}]""")).execute()
		}
		slack.resources = slack.resources + channel("C-general", "U-lena" to Access.WRITE, "U-gone1" to Access.WRITE, "U-gone2" to Access.WRITE, "U-gone3" to Access.WRITE)
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals("paused", report.status)
		assertEquals("3 removals is over the limit of 2", report.wouldPause)
	}

	@Test
	fun `a sync requested in a transaction goes through the outbox and runs outside any transaction`() {
		var inTransaction: Boolean? = null
		slack.onRead = { inTransaction = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() }
		requests.request("manual", setOf(Tool.SLACK))
		val deadline = System.currentTimeMillis() + 15_000
		while (dsl.fetchCount(SYNC_RUN) == 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
		assertEquals("manual", dsl.select(SYNC_RUN.TRIGGER).from(SYNC_RUN).fetchSingle().value1())
		while (dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.COMPLETION_DATE.isNull) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
		assertNotNull(dsl.select(EVENT_PUBLICATION.COMPLETION_DATE).from(EVENT_PUBLICATION).orderBy(EVENT_PUBLICATION.PUBLICATION_DATE.desc()).limit(1).fetchSingle().value1())
		// Each recorded change commits as it happens, not when the whole run ends (second review H2).
		assertEquals(false, inTransaction)
		slack.onRead = null
	}

	@Test
	fun `stage 2 has no rules, and a malformed access level is skipped with a warning`() {
		val project = dsl.select(PROJECT.ID).from(PROJECT).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, generalChannel)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "owner").execute()
		val loaded = worlds.load()
		assertTrue(loaded.world.rules.isEmpty())
		assertEquals(listOf(riseChannel), loaded.world.projectResources.map { it.resourceId })
		assertTrue(loaded.warnings.any { "owner" in it }, loaded.warnings.toString())
	}

	@Test
	fun `looking before creating, a name the tool already has goes to national once, and is neither created nor planned`() {
		val made = resource("slack", null, "umd-new-project")
		val project = dsl.select(PROJECT.ID).from(PROJECT).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, made)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		// Slack has it already, archived (an archived channel keeps its name), under a different case and a leading #.
		slack.resources = slack.resources + (ToolResource("C-existing", "#UMD-New-Project", archived = true) to emptyList())

		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals(0, report.creates)
		assertEquals(listOf("resource_exists"), changes(report.runId).filter { it.resourceId == made }.map { it.kind })
		with(dsl.selectFrom(RESOURCE).where(RESOURCE.ID.eq(made)).fetchSingle()) {
			assertNotNull(existsInToolAt)
			assertEquals("C-existing", foundExternalId)
		}
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq("resource_exists")).fetchSingle()
		assertEquals(made, task.resourceId)
		assertTrue("umd-new-project already exists in Slack" in task.description!!, task.description)

		// The next run leaves it alone: no second task, no create, no adds for its team.
		val next = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertEquals(1, dsl.fetchCount(ADMIN_TASK, ADMIN_TASK.ACTION.eq("resource_exists")))
		assertTrue(changes(next.runId).none { it.resourceId == made })
	}

	@Test
	fun `an unmanaged resource gets no planned changes even while a project still uses it`() {
		dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(riseChannel)).execute()
		val report = engine.dryRun("manual", setOf(Tool.SLACK)).single()
		assertTrue(changes(report.runId).none { it.resourceId == riseChannel })
	}

	@Test
	fun `chapter resources reach active members, and a lead's resource only leads`() {
		val leadsChannel = resource("slack", "C-leads", "#umd-leads")
		dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, umd).set(CHAPTER_RESOURCE.RESOURCE_ID, leadsChannel)
			.set(CHAPTER_RESOURCE.AUDIENCE, "leads").set(CHAPTER_RESOURCE.ACCESS_LEVEL, "write").execute()
		slack.resources = slack.resources + channel("C-leads")
		val grants = { org.hack4impact.portal.resolver.Resolver.resolve(worlds.load().world).grants.map { it.personId to it.resourceId }.toSet() }
		assertEquals(setOf(lena to generalChannel, ada to generalChannel, lena to leadsChannel), grants().filter { it.second != riseChannel }.toSet())

		// The lead's role ends: no leads channel. Ada becomes alumni: no chapter channel (Alan never had one).
		dsl.update(CHAPTER_ROLE).set(CHAPTER_ROLE.ENDS_AT, OffsetDateTime.now()).execute()
		dsl.update(PERSON).set(PERSON.STATUS, "alumni").where(PERSON.ID.eq(ada)).execute()
		assertEquals(setOf(lena to generalChannel), grants().filter { it.second != riseChannel }.toSet())
	}
}
