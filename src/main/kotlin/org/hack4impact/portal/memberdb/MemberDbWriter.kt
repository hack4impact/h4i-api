package org.hack4impact.portal.memberdb

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.MEMBER_DB_DRIFT
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What the portal writes for one person (wiki: staged-rollout, "who owns which data"). [fields] are the values it owns,
 * keyed `table.column` (`volunteers.email`, `academic_profiles.major`, `accounts.github`, …); the same keys are kept
 * as what was written, for the pull to compare.
 */
data class MemberDbPlan(
	val person: UUID,
	val volunteerId: UUID,
	/** A new volunteer (someone who joined through the portal), else an update to theirs. */
	val newVolunteer: Boolean,
	/** Their school profile to update, or null to add one. */
	val profileId: UUID?,
	val fields: Map<String, String?>,
	/** Why something won't be written (no major yet, no seeded graduation term): written without it, and shown. */
	val notes: List<String>,
)

/** Binds [values] in order, nulls included (JdbcClient's own varargs don't take nulls from Kotlin). */
internal fun JdbcClient.StatementSpec.args(vararg values: Any?): JdbcClient.StatementSpec = params(values.toList())

/** A write the member database refused for a reason a retry won't fix (an account linked to someone else). */
class MemberDbConflict(message: String) : RuntimeException(message)

/**
 * Writes people's records to the member database (wiki: staged-rollout, decision 162): their volunteer row, their
 * school profile, and their GitHub and Google accounts. Only the fields the portal owns; never deletes. Each write
 * is one member-database transaction. A worker takes the waiting ones every minute while national has the writes on
 * (not dry run, not paused) and the write switch is set; failures back off (1 minute to 2 hours) and wait for
 * national after five, or at once when the member database refuses the data.
 */
@Component
class MemberDbWriter(private val dsl: DSLContext, private val memberDb: MemberDb, private val properties: MemberDbProperties) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	companion object {
		const val MAX_ATTEMPTS = 5
		private val BACKOFF = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2))
		fun backoff(attempts: Int): Duration = BACKOFF[(attempts - 1).coerceIn(0, BACKOFF.lastIndex)]

		/** The portal's status as the member database's status and type (decision 40, written back). Null: not written. */
		fun statusAndType(status: String, kind: String): Pair<String, String?>? = when (status) {
			"active", "removal_requested" -> "active" to if (kind == "community") "community" else "student"
			"alumni" -> "inactive" to "alumni"
			"removed" -> "suspended" to null // the type stays as it was
			else -> null // applicants aren't volunteers until approved
		}
	}

	/** Whether writes go out now: the switch is set, and national has them on. */
	fun live(): Boolean {
		if (!memberDb.configured || !properties.write) return false
		val state = dsl.selectFrom(MEMBER_DB_STATE).fetchOne() ?: return false
		return state.dryRun == false && state.paused == false
	}

	/** What would be written for [person] now (also the dry run's preview). Reads the member database. */
	fun plan(person: UUID): MemberDbPlan {
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(person)).fetchOne() ?: throw MemberDbConflict("No such person")
		val (status, type) = statusAndType(p.status!!, p.kind!!) ?: throw MemberDbConflict("${p.firstName} isn't a member yet (${p.status})")
		val email = p.personalEmail ?: p.schoolEmail ?: throw MemberDbConflict("${p.firstName} has no personal or school address, and the member database needs one")
		val volunteerId = p.sourceId ?: p.id!!
		val existing = memberDb.jdbc.sql("SELECT deleted_at IS NOT NULL AS deleted FROM volunteers WHERE id = ?").param(volunteerId)
			.query { rs, _ -> rs.getBoolean("deleted") }.optional().orElse(null)
		if (existing == true) throw MemberDbConflict("The member database deleted ${p.firstName} ${p.lastName}'s record: national decides whether it comes back")
		// Their first chapter that came from the member database (it holds one chapter per person).
		val chapter = dsl.select(CHAPTER.SOURCE_ID).from(CHAPTER_MEMBERSHIP).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(person), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, CHAPTER.SOURCE_ID.isNotNull)
			.orderBy(CHAPTER_MEMBERSHIP.CREATED_AT).limit(1).fetchOne()?.value1()
		val term = p.graduationTermId?.let { dsl.select(TERM.SOURCE_ID).from(TERM).where(TERM.ID.eq(it)).fetchOne()?.value1() }
		val accounts = dsl.select(TOOL_ACCOUNT.TOOL, TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.EXTERNAL_LOGIN).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.STATE.eq("confirmed")).fetch()
		val github = accounts.firstOrNull { it.value1() == "github" && it.value2()?.all(Char::isDigit) == true }?.value2()
		val google = p.orgEmail ?: accounts.firstOrNull { it.value1() == "google" }?.value3()

		// The profile the portal wrote last time, else their newest one.
		val written = dsl.select(MEMBER_DB_WRITE.WRITTEN).from(MEMBER_DB_WRITE).where(MEMBER_DB_WRITE.PERSON_ID.eq(person)).fetchOne()?.value1()
			?.let { json.readTree(it.data()).path("academic_profiles.id").takeIf { n -> n.isString }?.asString()?.let(UUID::fromString) }
		val profileId = written?.takeIf { id -> memberDb.jdbc.sql("SELECT count(*) FROM academic_profiles WHERE id = ?").param(id).query(Long::class.java).single() > 0 }
			?: memberDb.jdbc.sql("SELECT id FROM academic_profiles WHERE volunteer_id = ? ORDER BY updated_at DESC LIMIT 1").param(volunteerId)
				.query(UUID::class.java).optional().orElse(null)

		val notes = buildList {
			if (p.major == null || p.degreeType == null) {
				add(if (profileId == null) "No school profile: major and degree type aren't known yet" else "Major and degree type aren't known: left as they are")
			}
			if (p.graduationSeason != null && term == null) add("No ${p.graduationSeason} ${p.graduationYear} term at their school yet: graduation left out until it's seeded")
			if (chapter == null) add("Their chapter isn't one from the member database")
		}
		val fields = buildMap {
			put("volunteers.first_name", p.firstName)
			put("volunteers.last_name", p.lastName)
			put("volunteers.preferred_name", p.preferredName)
			put("volunteers.email", email)
			put("volunteers.org_email", p.orgEmail)
			put("volunteers.chapter_id", chapter?.toString())
			put("volunteers.status", status)
			type?.let { put("volunteers.type", it) }
			if (profileId != null || (p.major != null && p.degreeType != null)) {
				put("academic_profiles.email", p.schoolEmail)
				p.major?.let { put("academic_profiles.major", it) }
				p.degreeType?.let { put("academic_profiles.degree_type", it) }
				term?.let { put("academic_profiles.graduation_term_id", it.toString()) }
			}
			github?.let { put("accounts.github", it) }
			google?.let { put("accounts.google", it.lowercase()) }
		}
		return MemberDbPlan(person, volunteerId, existing == null, profileId, fields, notes)
	}

	/** Writes [plan] in one member-database transaction; returns the fields as written (with the profile's ID). */
	fun apply(plan: MemberDbPlan): Map<String, String?> = memberDb.inTransaction { db ->
		val f = plan.fields
		val v = { key: String -> f["volunteers.$key"] }
		if (plan.newVolunteer) {
			db.sql(
				"""
				INSERT INTO volunteers (id, first_name, last_name, preferred_name, email, org_email, chapter_id, status, type)
				VALUES (?, ?, ?, ?, ?, ?, ?::uuid, ?, coalesce(?, 'student'))
				""".trimIndent(),
			).args(plan.volunteerId, v("first_name"), v("last_name"), v("preferred_name"), v("email"), v("org_email"), v("chapter_id"), v("status"), v("type")).update()
		} else {
			db.sql(
				"""
				UPDATE volunteers SET first_name = ?, last_name = ?, preferred_name = ?, email = ?, org_email = ?,
				  chapter_id = coalesce(?::uuid, chapter_id), status = ?, type = coalesce(?, type)
				WHERE id = ?
				""".trimIndent(),
			).args(v("first_name"), v("last_name"), v("preferred_name"), v("email"), v("org_email"), v("chapter_id"), v("status"), v("type"), plan.volunteerId).update()
		}

		var profileId = plan.profileId
		if (f.keys.any { it.startsWith("academic_profiles.") }) {
			val p = { key: String -> f["academic_profiles.$key"] }
			if (profileId == null) {
				profileId = db.sql(
					"INSERT INTO academic_profiles (volunteer_id, email, major, degree_type, graduation_term_id) VALUES (?, ?, ?, ?, ?::uuid) RETURNING id",
				).args(plan.volunteerId, p("email"), p("major"), p("degree_type"), p("graduation_term_id")).query(UUID::class.java).single()
			} else {
				db.sql(
					"""
					UPDATE academic_profiles SET email = coalesce(?, email), major = coalesce(?, major), degree_type = coalesce(?, degree_type),
					  graduation_term_id = coalesce(?::uuid, graduation_term_id)
					WHERE id = ?
					""".trimIndent(),
				).args(p("email"), p("major"), p("degree_type"), p("graduation_term_id"), profileId).update()
			}
		}

		f["accounts.github"]?.let { id -> linkGitHub(db, plan.volunteerId, id) }
		f["accounts.google"]?.let { address -> link(db, plan.volunteerId, "google", address) }
		f + ("academic_profiles.id" to profileId?.toString()) + ("volunteer_id" to plan.volunteerId.toString())
	}

	/** Their numeric GitHub ID: replaces a username the member database has for them (imported rows hold usernames). */
	private fun linkGitHub(db: JdbcClient, volunteer: UUID, id: String) {
		val owner = db.sql("SELECT volunteer_id FROM volunteer_accounts WHERE provider = 'github' AND external_id = ?").param(id)
			.query(UUID::class.java).optional().orElse(null)
		when {
			owner == volunteer -> return
			owner != null -> throw MemberDbConflict("GitHub account $id is linked to another volunteer in the member database ($owner)")
		}
		val username = db.sql("SELECT id FROM volunteer_accounts WHERE volunteer_id = ? AND provider = 'github' AND external_id !~ '^[0-9]+$' ORDER BY updated_at DESC LIMIT 1")
			.param(volunteer).query(UUID::class.java).optional().orElse(null)
		if (username != null) db.sql("UPDATE volunteer_accounts SET external_id = ? WHERE id = ?").args(id, username).update()
		else db.sql("INSERT INTO volunteer_accounts (volunteer_id, provider, external_id) VALUES (?, 'github', ?)").args(volunteer, id).update()
	}

	private fun link(db: JdbcClient, volunteer: UUID, provider: String, externalId: String) {
		val owner = db.sql("SELECT volunteer_id FROM volunteer_accounts WHERE provider = ? AND lower(external_id) = lower(?)").args(provider, externalId)
			.query(UUID::class.java).optional().orElse(null)
		when {
			owner == volunteer -> return
			owner != null -> throw MemberDbConflict("$provider account $externalId is linked to another volunteer in the member database ($owner)")
		}
		db.sql("INSERT INTO volunteer_accounts (volunteer_id, provider, external_id) VALUES (?, ?, ?)").args(volunteer, provider, externalId).update()
	}

	/** Writes everyone waiting and due, when writes are on. Returns how many were written. */
	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	fun runDue(): Int {
		if (!live()) return 0
		val due = dsl.select(MEMBER_DB_WRITE.PERSON_ID).from(MEMBER_DB_WRITE)
			.where(MEMBER_DB_WRITE.STATE.eq("pending"), MEMBER_DB_WRITE.NEXT_TRY_AT.isNull.or(MEMBER_DB_WRITE.NEXT_TRY_AT.le(OffsetDateTime.now())))
			.orderBy(MEMBER_DB_WRITE.REQUESTED_AT).limit(200).fetch().map { it.value1()!! }
		return due.count(::write)
	}

	/** Writes one person now; records the outcome. */
	fun write(person: UUID): Boolean {
		val requested = dsl.select(MEMBER_DB_WRITE.REQUESTED_AT, MEMBER_DB_WRITE.ATTEMPTS).from(MEMBER_DB_WRITE).where(MEMBER_DB_WRITE.PERSON_ID.eq(person)).fetchOne()
			?: return false
		return try {
			val plan = plan(person)
			val written = apply(plan)
			dsl.transaction { tx ->
				val t = tx.dsl()
				// Someone who joined through the portal is linked to the volunteer it just made.
				t.update(PERSON).set(PERSON.SOURCE_ID, plan.volunteerId).where(PERSON.ID.eq(person), PERSON.SOURCE_ID.isNull).execute()
				// Done, unless they were asked for again while this one ran (then the next round writes the newer data).
				t.update(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.STATE, "done").set(MEMBER_DB_WRITE.ATTEMPTS, 0).setNull(MEMBER_DB_WRITE.NEXT_TRY_AT)
					.setNull(MEMBER_DB_WRITE.LAST_ERROR).set(MEMBER_DB_WRITE.WRITTEN, JSONB.valueOf(json.writeValueAsString(written)))
					.set(MEMBER_DB_WRITE.WRITTEN_AT, OffsetDateTime.now())
					.where(MEMBER_DB_WRITE.PERSON_ID.eq(person), MEMBER_DB_WRITE.REQUESTED_AT.eq(requested.value1())).execute()
				t.update(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.WRITTEN, JSONB.valueOf(json.writeValueAsString(written))).set(MEMBER_DB_WRITE.WRITTEN_AT, OffsetDateTime.now())
					.where(MEMBER_DB_WRITE.PERSON_ID.eq(person)).execute()
				t.deleteFrom(MEMBER_DB_DRIFT).where(MEMBER_DB_DRIFT.PERSON_ID.eq(person)).execute() // what's there is the portal's again
			}
			true
		} catch (e: Exception) {
			val attempts = requested.value2()!! + 1
			val giveUp = e is MemberDbConflict || attempts >= MAX_ATTEMPTS
			log.warn("Member database write for {} failed (attempt {}): {}", person, attempts, e.message)
			dsl.update(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.ATTEMPTS, attempts).set(MEMBER_DB_WRITE.LAST_ERROR, (e.message ?: e.javaClass.simpleName).take(500))
				.set(MEMBER_DB_WRITE.STATE, if (giveUp) "failed" else "pending")
				.set(MEMBER_DB_WRITE.NEXT_TRY_AT, if (giveUp) null else OffsetDateTime.now().plus(backoff(attempts)))
				.where(MEMBER_DB_WRITE.PERSON_ID.eq(person)).execute()
			false
		}
	}
}
