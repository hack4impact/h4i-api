package org.hack4impact.portal.memberdb

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.MEMBER_DB_PROJECT_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.hack4impact.portal.projects.MemberInput
import org.hack4impact.portal.projects.ProjectInput
import org.hack4impact.portal.projects.ProjectService
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The way to the member database, which a test can cut: the app connects here and is passed on to the real one.
 * [down] refuses new connections and drops the open ones, as a stopped database does.
 */
class Gate(private val host: String, private val targetPort: Int) {
	private val open = ConcurrentHashMap.newKeySet<Socket>()
	private var server = listen(0)
	val port: Int = server.localPort

	private fun listen(port: Int): ServerSocket {
		val listening = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", port)) }
		thread(isDaemon = true) {
			while (true) {
				val client = try { listening.accept() } catch (_: IOException) { break }
				val upstream = try { Socket(host, targetPort) } catch (_: IOException) { client.close(); continue }
				open += client; open += upstream
				pipe(client, upstream); pipe(upstream, client)
			}
		}
		return listening
	}

	private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
		try { from.getInputStream().copyTo(to.getOutputStream()) } catch (_: IOException) {} finally { runCatching { from.close() }; runCatching { to.close() }; open -= from; open -= to }
	}

	fun down() {
		server.close()
		open.toList().forEach { runCatching { it.close() } }
	}

	fun up() {
		if (server.isClosed) server = listen(port)
	}
}

/**
 * A member-database outage (2026-11-09 review M5): while it can't be reached no write counts as failed, however long
 * it lasts, and everything goes out once it's back. And someone long off a team, whose record the member database
 * has since deleted, doesn't hold up the project's write.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MemberDbOutageTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val people: MemberDbWriter,
	@Autowired private val projectWriter: MemberDbProjectWriter,
	@Autowired private val writes: MemberDbWrites,
	@Autowired private val pull: MemberDbPull,
	@Autowired private val projects: ProjectService,
) {
	companion object {
		private val gate by lazy { MemberDbFixture.container.let { Gate(it.host, it.getMappedPort(5432)) } }

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val db = MemberDbFixture.writable
			registry.add("portal.member-db.url") { db.url.replace(Regex("//[^/]+/"), "//127.0.0.1:${gate.port}/") }
			registry.add("portal.member-db.username") { db.username }
			registry.add("portal.member-db.password") { db.password }
			registry.add("portal.member-db.write") { "true" }
			registry.add("portal.member-db.connect-timeout") { "1s" } // an attempt during the outage gives up quickly
		}

		val ADA: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")
		val ALAN: UUID = UUID.fromString("50000000-0000-0000-0000-000000000002")
		val UMD: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
		val RISE: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
		val ENGINEER: UUID = UUID.fromString("80000000-0000-0000-0000-000000000001")
	}

	/** Straight to the member database, past the gate. */
	private val memberDb by lazy { MemberDbFixture.writable.let { JdbcClient.create(org.springframework.jdbc.datasource.DriverManagerDataSource(it.url, it.username, it.password)) } }
	private val system = Viewer(null, "system", "System", true, emptyMap())
	private lateinit var umd: UUID

	private fun count(sql: String, vararg args: Any): Long = memberDb.sql(sql).params(args.toList()).query(Long::class.java).single()
	private fun portalId(table: org.jooq.Table<*>, source: UUID): UUID =
		dsl.select(table.field("id", UUID::class.java)).from(table).where(table.field("source_id", UUID::class.java)!!.eq(source)).fetchSingle().value1()!!
	private fun record(id: UUID) = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(id)).fetchSingle()
	private fun personWrite(id: UUID) = dsl.selectFrom(MEMBER_DB_WRITE).where(MEMBER_DB_WRITE.PERSON_ID.eq(id)).fetchSingle()
	private fun projectWrite(id: UUID) = dsl.selectFrom(MEMBER_DB_PROJECT_WRITE).where(MEMBER_DB_PROJECT_WRITE.PROJECT_ID.eq(id)).fetchSingle()

	/** The worker's next round, with every wait between tries already over. */
	private fun nextRound(): Int {
		dsl.update(MEMBER_DB_WRITE).setNull(MEMBER_DB_WRITE.NEXT_TRY_AT).execute()
		dsl.update(MEMBER_DB_PROJECT_WRITE).setNull(MEMBER_DB_PROJECT_WRITE.NEXT_TRY_AT).execute()
		return people.runDue() + projectWriter.runDue()
	}

	private fun started(name: String): UUID {
		val project = projects.create(umd, ProjectInput(name, partnerId = portalId(PARTNER, RISE), startsOn = LocalDate.parse("2026-09-01")), system).id!!
		projects.setStatus(umd, record(project), "active", system)
		return project
	}

	@BeforeEach
	fun seed() {
		gate.up()
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		dsl.insertInto(MEMBER_DB_STATE).defaultValues().execute()
		assertEquals("completed", pull.pull(force = true).status) // chapters, volunteers, partners and project roles come in
		umd = portalId(CHAPTER, UMD)
		dsl.update(MEMBER_DB_STATE).set(MEMBER_DB_STATE.DRY_RUN, false).set(MEMBER_DB_STATE.PAUSED, false).set(MEMBER_DB_STATE.PROJECTS_ENABLED, true).execute()
	}

	@AfterEach
	fun reopen() = gate.up()

	@Test
	fun `an outage longer than the retry limit fails no write, and everything is written once it's back`() {
		val nia = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nia").set(PERSON.LAST_NAME, "New").set(PERSON.STATUS, "active")
			.set(PERSON.PERSONAL_EMAIL, "nia@personal.test").set(PERSON.ORG_EMAIL, "nia@umd.hack4impact.org").set(PERSON.CLAIMED_AT, OffsetDateTime.now())
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, nia).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		writes.request(nia)
		val project = started("Food Drive")
		projects.addMember(umd, record(project), MemberInput(portalId(PERSON, ADA), portalId(PROJECT_ROLE, ENGINEER)), system)

		gate.down()
		repeat(MemberDbWriter.MAX_ATTEMPTS + 2) { round ->
			assertEquals(0, nextRound(), "round $round: nothing can be written")
			with(personWrite(nia)) {
				assertEquals("pending", state, "round $round: the person's write")
				assertEquals(0, attempts, "round $round: an unreachable database isn't a try")
				assertNotNull(lastError)
				assertNotNull(nextTryAt) // and it waits before the next try, as after any failure
			}
			with(projectWrite(project)) {
				assertEquals("pending", state, "round $round: the project's write")
				assertEquals(0, attempts, "round $round")
				assertContains(lastError!!, "couldn't be reached")
			}
		}
		assertEquals(0L, count("SELECT count(*) FROM volunteers WHERE id = ?", nia))

		gate.up()
		// The connection pool takes a moment to notice: the worker's later rounds get through.
		val until = System.currentTimeMillis() + 30_000
		while ((personWrite(nia).state != "done" || projectWrite(project).state != "done") && System.currentTimeMillis() < until) nextRound()
		assertEquals("done", personWrite(nia).state)
		assertEquals("done", projectWrite(project).state)
		assertNull(projectWrite(project).lastError)
		assertEquals(1L, count("SELECT count(*) FROM volunteers WHERE id = ? AND first_name = 'Nia'", nia))
		assertEquals(1L, count("SELECT count(*) FROM project_engagements WHERE id = ?", project))
		assertEquals(1L, count("SELECT count(*) FROM project_assignments WHERE project_engagement_id = ? AND volunteer_id = ?", project, ADA))
	}

	@Test
	fun `a former team member whose record the member database deleted doesn't block the project's write`() {
		val project = started("Food Drive")
		projects.addMember(umd, record(project), MemberInput(portalId(PERSON, ADA), portalId(PROJECT_ROLE, ENGINEER)), system)
		projects.addMember(umd, record(project), MemberInput(portalId(PERSON, ALAN), portalId(PROJECT_ROLE, ENGINEER)), system)
		nextRound()
		assertEquals(2L, count("SELECT count(*) FROM project_assignments WHERE project_engagement_id = ?", project))

		// Alan leaves the team; later the member database deletes his record; then the project changes again.
		projects.removeMember(umd, record(project), portalId(PERSON, ALAN), system)
		nextRound()
		memberDb.sql("UPDATE volunteers SET deleted_at = now() WHERE id = ?").param(ALAN).update()
		projects.setStatus(umd, record(project), "paused", system)
		nextRound()

		assertEquals("done", projectWrite(project).state, projectWrite(project).lastError)
		// National's preview says who was left out and why.
		assertContains(projectWriter.planProject(project, preview = true).notes.joinToString(), "1 former team members have no record")
		assertEquals(1L, count("SELECT count(*) FROM project_engagements WHERE id = ? AND status = 'paused'", project)) // the change went through
	}
}
