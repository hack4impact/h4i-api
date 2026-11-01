package org.hack4impact.portal.memberdb

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_LEADERSHIP_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_PROJECT_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Someone whose own person write has to land before this write can: their volunteer row isn't in the member database yet. */
class WaitingOnPeople(val people: List<UUID>) : RuntimeException("waiting for ${people.size} people's records to be written first")

/**
 * What one project write sends (wiki: staged-rollout-stage-2, member database writes). [skip]: nothing to write, and why
 * (a draft). [fields] is the summary national previews and the portal keeps as written.
 */
data class ProjectWritePlan(
	val project: UUID,
	val skip: String? = null,
	val fields: Map<String, String?> = emptyMap(),
	val notes: List<String> = emptyList(),
	internal val ops: (JdbcClient) -> Stamps = { Stamps() },
)

/** What the portal stamps on its own rows once a write committed: the member database's IDs for what it created. */
data class Stamps(val projectsRow: UUID? = null, val partner: Pair<UUID, UUID>? = null, val assignments: Map<UUID, UUID> = emptyMap())

data class LeadershipWritePlan(val chapterRole: UUID, val skip: String? = null, val fields: Map<String, String?> = emptyMap(), internal val ops: (JdbcClient) -> Unit = {})

/**
 * Writes projects and chapter leads to the member database (stage 2, decision 171): a project's projects row, its
 * engagement (the portal project's own ID), every team member's assignment and, the first time, its partner, in one
 * member-database transaction; a chapter lead's or co-lead's leadership assignment (the chapter_role's ID). Never
 * deletes: a removal or closure sets an end date. Writes follow stage 1's switches (write switch, dry run, pause) plus
 * `projects_enabled`; people go first, so a write waits while someone's person write is pending and goes to national when
 * it failed. Failures back off (1 minute to 2 hours) and wait for national after five.
 */
@Component
class MemberDbProjectWriter(private val dsl: DSLContext, private val memberDb: MemberDb, private val people: MemberDbWriter, private val writes: MemberDbWrites) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	companion object {
		const val MAX_ATTEMPTS = MemberDbWriter.MAX_ATTEMPTS
		/** How often a write waiting on someone's person write looks again. */
		private val WAIT = java.time.Duration.ofMinutes(5)
		private val LEADERSHIP_ROLES = mapOf("lead" to "Chapter lead", "co_lead" to "Co-lead")

		/** The portal's project status as projects.status and project_engagements.status (projects has no paused). */
		fun statuses(status: String): Pair<String, String> = when (status) {
			"active" -> "active" to "active"
			"paused" -> "active" to "paused"
			else -> "completed" to "completed"
		}
	}

	/** Whether project and leadership writes go out now: person writes are live, and national turned these on too. */
	fun live(): Boolean = people.live() && dsl.select(MEMBER_DB_STATE.PROJECTS_ENABLED).from(MEMBER_DB_STATE).fetchOne()?.value1() == true

	// ---- Projects ---------------------------------------------------------------------------------------------------

	/** What would be written for [projectId] now (also the preview). Reads the member database. */
	fun planProject(projectId: UUID): ProjectWritePlan {
		val p = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(projectId)).fetchOne() ?: throw MemberDbConflict("No such project")
		if (p.status == "draft") return ProjectWritePlan(projectId, skip = "A draft project isn't written until it starts")
		val chapter = dsl.selectFrom(CHAPTER).where(CHAPTER.ID.eq(p.chapterId)).fetchSingle()
		val chapterId = chapter.sourceId ?: throw MemberDbConflict("${chapter.name} isn't a chapter from the member database")
		val partner = dsl.selectFrom(PARTNER).where(PARTNER.ID.eq(p.partnerId)).fetchOne() ?: throw MemberDbConflict("${p.name} has no partner")
		val partnerId = partner.sourceId ?: partner.id!!
		val partnerExists = memberDb.jdbc.sql("SELECT deleted_at IS NOT NULL FROM partners WHERE id = ?").param(partnerId).query(Boolean::class.java).optional().orElse(null)
		if (partnerExists == true) throw MemberDbConflict("The member database deleted the partner ${partner.name}: pick another")
		val (projectStatus, engagementStatus) = statuses(p.status!!)
		val start = (p.startsOn ?: p.createdAt!!.toLocalDate()).atStartOfDay().atOffset(ZoneOffset.UTC)
		val end = if (p.status == "closed") (p.endsOn?.atStartOfDay()?.atOffset(ZoneOffset.UTC) ?: p.closedAt) else p.endsOn?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
		val notes = mutableListOf<String>()

		// The projects row: a past one the lead linked (only the engagement is written), the one the portal made, or a new one.
		val linked = p.memberDbLinked == true
		val ownRow = if (linked) null else p.memberDbProjectId
		val name = if (linked) null else uniqueName(p.name!!, chapter.code!!, start.year, ownRow)
		if (name != null && name != p.name) notes += "\"${p.name}\" is taken in the member database: written as \"$name\""
		val projectsRow = if (linked) p.memberDbProjectId!! else ownRow ?: UUID.randomUUID()

		// The team, every row the project ever had: each member's volunteer row must be there first.
		val members = dsl.select(PROJECT_MEMBER.ID, PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.MEMBER_DB_ASSIGNMENT_ID, PROJECT_MEMBER.ADDED_AT, PROJECT_MEMBER.REMOVED_AT,
			PROJECT_ROLE.NAME, PROJECT_ROLE.SOURCE_ID)
			.from(PROJECT_MEMBER).join(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID))
			.where(PROJECT_MEMBER.PROJECT_ID.eq(projectId)).orderBy(PROJECT_MEMBER.ADDED_AT).fetch()
		val volunteers = volunteersOf(members.map { it.value2()!! }.toSet())
		val assignments = members.map { m ->
			val role = m.value7() ?: throw MemberDbConflict("The project role ${m.value6()} isn't in the member database")
			Assignment(m.value1()!!, m.value3() ?: m.value1()!!, volunteers.getValue(m.value2()!!), role, m.value4()!!, m.value5())
		}

		val fields = buildMap {
			put("projects.id", projectsRow.toString())
			if (name != null) { put("projects.name", name); put("projects.status", projectStatus) }
			put("project_engagements.id", projectId.toString())
			put("project_engagements.chapter_id", chapterId.toString())
			put("project_engagements.partner_id", partnerId.toString())
			put("project_engagements.status", engagementStatus)
			put("project_engagements.start_date", start.toLocalDate().toString())
			put("project_engagements.end_date", end?.toLocalDate()?.toString())
			if (partnerExists == null) put("partners.name", partner.name)
			put("project_assignments", assignments.joinToString { "${it.volunteer}:${it.role}${if (it.end != null) " (ended)" else ""}" })
		}
		if (linked) notes += "Linked to a past project: only the engagement and its team are written"
		if (partnerExists == null) notes += "The partner ${partner.name} is new to the member database: written with this project"

		return ProjectWritePlan(projectId, fields = fields, notes = notes) { db ->
			if (partnerExists == null) {
				db.sql("INSERT INTO partners (id, name, website, status) VALUES (?, ?, ?, 'active')").args(partnerId, partner.name, partner.website).update()
			}
			db.sql("INSERT INTO chapter_partners (chapter_id, partner_id) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM chapter_partners WHERE chapter_id = ? AND partner_id = ?)")
				.args(chapterId, partnerId, chapterId, partnerId).update()
			if (!linked) {
				if (ownRow == null) db.sql("INSERT INTO projects (id, name, status) VALUES (?, ?, ?)").args(projectsRow, name, projectStatus).update()
				else db.sql("UPDATE projects SET name = ?, status = ? WHERE id = ?").args(name, projectStatus, projectsRow).update()
			}
			db.sql(
				"""
				INSERT INTO project_engagements (id, chapter_id, partner_id, project_id, status, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (id) DO UPDATE SET chapter_id = excluded.chapter_id, partner_id = excluded.partner_id, project_id = excluded.project_id,
				  status = excluded.status, start_date = excluded.start_date, end_date = excluded.end_date
				""".trimIndent(),
			).args(projectId, chapterId, partnerId, projectsRow, engagementStatus, start, end).update()
			val opened = assignments.mapNotNull { a -> writeAssignment(db, a, projectId)?.let { a.memberRow to it } }.toMap()
			Stamps(projectsRow.takeIf { !linked && ownRow == null }, if (partnerExists == null) partner.id!! to partnerId else null, opened)
		}
	}

	private data class Assignment(val memberRow: UUID, val id: UUID, val volunteer: UUID, val role: UUID, val start: OffsetDateTime, val end: OffsetDateTime?)

	/**
	 * Adds or updates one assignment. A role changed since it was written ends that assignment and opens another, so the
	 * member database keeps who held which role when. Returns the assignment ID to keep on the member's row.
	 */
	private fun writeAssignment(db: JdbcClient, a: Assignment, engagement: UUID): UUID? {
		val existing = db.sql("SELECT project_role_id, end_date FROM project_assignments WHERE id = ?").param(a.id)
			.query { rs, _ -> rs.getObject("project_role_id", UUID::class.java) to rs.getObject("end_date", OffsetDateTime::class.java) }.optional().orElse(null)
		val insert = { id: UUID, start: OffsetDateTime ->
			db.sql("INSERT INTO project_assignments (id, volunteer_id, project_engagement_id, project_role_id, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?)")
				.args(id, a.volunteer, engagement, a.role, start, a.end).update()
		}
		return when {
			existing == null -> { insert(a.id, a.start); a.id }
			existing.first != a.role && existing.second == null -> {
				val now = OffsetDateTime.now()
				db.sql("UPDATE project_assignments SET end_date = ? WHERE id = ?").args(now, a.id).update()
				UUID.randomUUID().also { insert(it, now) }
			}
			else -> { db.sql("UPDATE project_assignments SET end_date = ? WHERE id = ?").args(a.end, a.id).update(); a.id }
		}
	}

	/**
	 * A name the member database doesn't have yet (its project names are unique, decision 168): the name, then
	 * "<name> (<CHAPTER>)", then "<name> (<CHAPTER> <year>)". [own]: the row the portal made, which may keep its name.
	 */
	private fun uniqueName(name: String, code: String, year: Int, own: UUID?): String {
		val candidates = listOf(name, "$name (${code.uppercase()})", "$name (${code.uppercase()} $year)")
		return candidates.firstOrNull { candidate ->
			memberDb.jdbc.sql("SELECT count(*) FROM projects WHERE name = ? AND id IS DISTINCT FROM ?::uuid").args(candidate, own).query(Long::class.java).single() == 0L
		} ?: throw MemberDbConflict("\"$name\", \"${candidates[1]}\" and \"${candidates[2]}\" are all taken in the member database: rename the project")
	}

	/**
	 * Each person's volunteer ID. People go first: someone whose person write is pending is waited for (their person
	 * write is asked for if it never was); a failed person write, or a volunteer the member database deleted, goes to
	 * national.
	 */
	private fun volunteersOf(persons: Set<UUID>): Map<UUID, UUID> {
		if (persons.isEmpty()) return emptyMap()
		val rows = dsl.select(PERSON.ID, PERSON.SOURCE_ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, MEMBER_DB_WRITE.STATE)
			.from(PERSON).leftJoin(MEMBER_DB_WRITE).on(MEMBER_DB_WRITE.PERSON_ID.eq(PERSON.ID)).where(PERSON.ID.`in`(persons)).fetch()
		val waiting = mutableListOf<UUID>()
		val ids = mutableMapOf<UUID, UUID>()
		for (r in rows) {
			val name = "${r.value3()} ${r.value4()}"
			when {
				r.value5() == "failed" -> throw MemberDbConflict("$name's own record couldn't be written to the member database: retry theirs first")
				r.value2() == null -> {
					waiting += r.value1()!!
					if (r.value5() == null) writes.request(r.value1()!!)
				}
				else -> ids[r.value1()!!] = r.value2()!!
			}
		}
		if (waiting.isNotEmpty()) throw WaitingOnPeople(waiting)
		val deleted = ids.values.filter { v ->
			memberDb.jdbc.sql("SELECT count(*) FROM volunteers WHERE id = ? AND deleted_at IS NOT NULL").param(v).query(Long::class.java).single() > 0
		}.toSet()
		ids.entries.firstOrNull { it.value in deleted }?.let { (person, _) ->
			val p = rows.first { it.value1() == person }
			throw MemberDbConflict("The member database deleted ${p.value3()} ${p.value4()}'s record: national decides whether it comes back")
		}
		return ids
	}

	// ---- Chapter leads ----------------------------------------------------------------------------------------------

	/** What would be written for a chapter role now (also the preview). Viewers aren't written. */
	fun planLeadership(chapterRoleId: UUID): LeadershipWritePlan {
		val role = dsl.selectFrom(CHAPTER_ROLE).where(CHAPTER_ROLE.ID.eq(chapterRoleId)).fetchOne() ?: throw MemberDbConflict("No such chapter role")
		val leadershipRole = LEADERSHIP_ROLES[role.role] ?: return LeadershipWritePlan(chapterRoleId, skip = "Viewers aren't written to the member database")
		val chapter = dsl.selectFrom(CHAPTER).where(CHAPTER.ID.eq(role.chapterId)).fetchSingle()
		val chapterId = chapter.sourceId ?: throw MemberDbConflict("${chapter.name} isn't a chapter from the member database")
		val roleId = memberDb.jdbc.sql("SELECT id FROM leadership_roles WHERE name = ? ORDER BY created_at LIMIT 1").param(leadershipRole)
			.query(UUID::class.java).optional().orElse(null) ?: throw MemberDbConflict("The member database has no \"$leadershipRole\" leadership role: Khoa's script seeds it")
		val volunteer = volunteersOf(setOf(role.personId!!)).getValue(role.personId!!)
		val fields = mapOf(
			"leadership_assignments.id" to chapterRoleId.toString(),
			"leadership_assignments.volunteer_id" to volunteer.toString(),
			"leadership_assignments.chapter_id" to chapterId.toString(),
			"leadership_assignments.leadership_role" to leadershipRole,
			"leadership_assignments.start_date" to role.startsAt!!.toLocalDate().toString(),
			"leadership_assignments.end_date" to role.endsAt?.toLocalDate()?.toString(),
		)
		return LeadershipWritePlan(chapterRoleId, fields = fields) { db ->
			db.sql(
				"""
				INSERT INTO leadership_assignments (id, volunteer_id, chapter_id, leadership_role_id, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (id) DO UPDATE SET volunteer_id = excluded.volunteer_id, chapter_id = excluded.chapter_id,
				  leadership_role_id = excluded.leadership_role_id, start_date = excluded.start_date, end_date = excluded.end_date
				""".trimIndent(),
			).args(chapterRoleId, volunteer, chapterId, roleId, role.startsAt, role.endsAt).update()
		}
	}

	// ---- The worker -------------------------------------------------------------------------------------------------

	/** Writes every project and leadership write waiting and due, when writes are on. Returns how many were written. */
	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	fun runDue(): Int {
		if (!live()) return 0
		val now = OffsetDateTime.now()
		val projects = dsl.select(MEMBER_DB_PROJECT_WRITE.PROJECT_ID).from(MEMBER_DB_PROJECT_WRITE)
			.where(MEMBER_DB_PROJECT_WRITE.STATE.eq("pending"), MEMBER_DB_PROJECT_WRITE.NEXT_TRY_AT.isNull.or(MEMBER_DB_PROJECT_WRITE.NEXT_TRY_AT.le(now)))
			.orderBy(MEMBER_DB_PROJECT_WRITE.REQUESTED_AT).limit(200).fetch(MEMBER_DB_PROJECT_WRITE.PROJECT_ID).filterNotNull()
		val roles = dsl.select(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID).from(MEMBER_DB_LEADERSHIP_WRITE)
			.where(MEMBER_DB_LEADERSHIP_WRITE.STATE.eq("pending"), MEMBER_DB_LEADERSHIP_WRITE.NEXT_TRY_AT.isNull.or(MEMBER_DB_LEADERSHIP_WRITE.NEXT_TRY_AT.le(now)))
			.orderBy(MEMBER_DB_LEADERSHIP_WRITE.REQUESTED_AT).limit(200).fetch(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID).filterNotNull()
		return projects.count(::writeProject) + roles.count(::writeLeadership)
	}

	/** Writes one project now; records the outcome. */
	fun writeProject(projectId: UUID): Boolean {
		val q = MEMBER_DB_PROJECT_WRITE
		val requested = dsl.select(q.REQUESTED_AT, q.ATTEMPTS).from(q).where(q.PROJECT_ID.eq(projectId)).fetchOne() ?: return false
		return outcome(projectId, requested.value2()!!, { ids -> waiting(q, q.PROJECT_ID, projectId, ids) }, { attempts, error, giveUp -> failed(q, q.PROJECT_ID, projectId, attempts, error, giveUp) }) {
			val plan = planProject(projectId)
			val stamps = if (plan.skip == null) memberDb.inTransaction(plan.ops) else Stamps()
			dsl.transaction { tx ->
				val t = tx.dsl()
				stamps.projectsRow?.let { t.update(PROJECT).set(PROJECT.MEMBER_DB_PROJECT_ID, it).where(PROJECT.ID.eq(projectId)).execute() }
				stamps.partner?.let { (partner, id) -> t.update(PARTNER).set(PARTNER.SOURCE_ID, id).set(PARTNER.IMPORTED_AT, OffsetDateTime.now()).where(PARTNER.ID.eq(partner), PARTNER.SOURCE_ID.isNull).execute() }
				stamps.assignments.forEach { (row, id) -> t.update(PROJECT_MEMBER).set(PROJECT_MEMBER.MEMBER_DB_ASSIGNMENT_ID, id).where(PROJECT_MEMBER.ID.eq(row)).execute() }
				val written = JSONB.valueOf(json.writeValueAsString(plan.fields + ("skipped" to plan.skip)))
				t.update(q).set(q.STATE, "done").set(q.ATTEMPTS, 0).setNull(q.NEXT_TRY_AT).setNull(q.LAST_ERROR).setNull(q.WAITING_ON)
					.where(q.PROJECT_ID.eq(projectId), q.REQUESTED_AT.eq(requested.value1())).execute()
				t.update(q).set(q.WRITTEN, written).set(q.WRITTEN_AT, OffsetDateTime.now()).where(q.PROJECT_ID.eq(projectId)).execute()
			}
		}
	}

	/** Writes one chapter role now; records the outcome. */
	fun writeLeadership(chapterRoleId: UUID): Boolean {
		val q = MEMBER_DB_LEADERSHIP_WRITE
		val requested = dsl.select(q.REQUESTED_AT, q.ATTEMPTS).from(q).where(q.CHAPTER_ROLE_ID.eq(chapterRoleId)).fetchOne() ?: return false
		return outcome(chapterRoleId, requested.value2()!!, { ids -> waiting(q, q.CHAPTER_ROLE_ID, chapterRoleId, ids) }, { attempts, error, giveUp -> failed(q, q.CHAPTER_ROLE_ID, chapterRoleId, attempts, error, giveUp) }) {
			val plan = planLeadership(chapterRoleId)
			if (plan.skip == null) memberDb.inTransaction { plan.ops(it) }
			dsl.transaction { tx ->
				val t = tx.dsl()
				t.update(q).set(q.STATE, "done").set(q.ATTEMPTS, 0).setNull(q.NEXT_TRY_AT).setNull(q.LAST_ERROR).setNull(q.WAITING_ON)
					.where(q.CHAPTER_ROLE_ID.eq(chapterRoleId), q.REQUESTED_AT.eq(requested.value1())).execute()
				t.update(q).set(q.WRITTEN, JSONB.valueOf(json.writeValueAsString(plan.fields + ("skipped" to plan.skip)))).set(q.WRITTEN_AT, OffsetDateTime.now())
					.where(q.CHAPTER_ROLE_ID.eq(chapterRoleId)).execute()
			}
		}
	}

	/** Runs [write]: done, waiting on people (not counted as a failure), or failed (backs off; national after five, or at once for a conflict). */
	private fun outcome(id: UUID, attempts: Int, onWaiting: (List<UUID>) -> Unit, onFailed: (Int, String, Boolean) -> Unit, write: () -> Unit): Boolean =
		try {
			write()
			true
		} catch (e: WaitingOnPeople) {
			onWaiting(e.people)
			false
		} catch (e: Exception) {
			val n = attempts + 1
			log.warn("Member database write for {} failed (attempt {}): {}", id, n, e.message)
			onFailed(n, (e.message ?: e.javaClass.simpleName).take(500), e is MemberDbConflict || n >= MAX_ATTEMPTS)
			false
		}

	private fun <R : org.jooq.Record> waiting(table: org.jooq.Table<R>, key: org.jooq.TableField<R, UUID?>, id: UUID, people: List<UUID>) {
		dsl.update(table)
			.set(table.field("waiting_on", JSONB::class.java), JSONB.valueOf(json.writeValueAsString(people.map { it.toString() })))
			.set(table.field("next_try_at", OffsetDateTime::class.java), OffsetDateTime.now().plus(WAIT))
			.set(table.field("last_error", String::class.java), "Waiting for ${people.size} people's own records to be written first")
			.where(key.eq(id)).execute()
	}

	private fun <R : org.jooq.Record> failed(table: org.jooq.Table<R>, key: org.jooq.TableField<R, UUID?>, id: UUID, attempts: Int, error: String, giveUp: Boolean) {
		dsl.update(table)
			.set(table.field("attempts", Int::class.java), attempts)
			.set(table.field("last_error", String::class.java), error)
			.set(table.field("state", String::class.java), if (giveUp) "failed" else "pending")
			.set(table.field("next_try_at", OffsetDateTime::class.java), if (giveUp) null else OffsetDateTime.now().plus(MemberDbWriter.backoff(attempts)))
			.setNull(table.field("waiting_on"))
			.where(key.eq(id)).execute()
	}
}
