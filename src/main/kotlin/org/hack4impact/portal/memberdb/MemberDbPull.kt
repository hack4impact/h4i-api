package org.hack4impact.portal.memberdb

import org.hack4impact.portal.db.tables.references.MEMBER_DB_DRIFT
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.memberimport.MemberDbImporter
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** One pull: [status] completed, unchanged (nothing new since the last one) or failed. */
data class PullResult(val status: String, val counts: Map<String, Int> = emptyMap(), val drift: Int = 0, val error: String? = null)

/**
 * Pulls the member database into the portal (wiki: staged-rollout, decision 162): the importer brings in what the
 * member database owns and new volunteers (as claimable records), never overwriting a record its person confirmed;
 * then each person the portal wrote is compared with what it wrote, and a field changed there is reported as drift
 * (Q49), not taken. Every [MemberDbProperties.pullInterval] when `PORTAL_MEMBERDB_PULL` is set, and not while paused.
 * Nothing is read when no table changed: each table's row count and newest change are compared first.
 */
@Component
class MemberDbPull(
	private val dsl: DSLContext,
	private val memberDb: MemberDb,
	private val properties: MemberDbProperties,
	private val importer: MemberDbImporter,
	private val transactions: TransactionTemplate,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	@Scheduled(fixedDelayString = "\${portal.member-db.pull-interval:PT15M}", initialDelayString = "PT2M")
	fun scheduled() {
		if (!properties.pull || !memberDb.configured) return
		if (dsl.selectFrom(MEMBER_DB_STATE).fetchOne()?.paused == true) return
		val result = pull()
		if (result.status != "unchanged") log.info("Member database pull: {}", result)
	}

	/** Pulls now; [force]: even when nothing seems to have changed. */
	fun pull(force: Boolean = false): PullResult {
		val result = try {
			val signature = signature()
			val previous = dsl.select(MEMBER_DB_STATE.SIGNATURE).from(MEMBER_DB_STATE).fetchOne()?.value1()
			if (!force && signature == previous) {
				PullResult("unchanged")
			} else {
				val report = transactions.execute { importer.import(memberDb.jdbc) }!!
				val drift = checkDrift()
				saveSignature(signature)
				PullResult("completed", report.counts.mapValues { (_, c) -> c.inserted + c.updated }, drift)
			}
		} catch (e: Exception) {
			log.warn("Member database pull failed: {}", e.message)
			PullResult("failed", error = (e.message ?: e.javaClass.simpleName).take(500))
		}
		dsl.insertInto(MEMBER_DB_STATE).set(MEMBER_DB_STATE.ID, true).set(MEMBER_DB_STATE.LAST_PULL_AT, OffsetDateTime.now())
			.set(MEMBER_DB_STATE.LAST_PULL_STATUS, result.status).set(MEMBER_DB_STATE.LAST_PULL_ERROR, result.error)
			.set(MEMBER_DB_STATE.LAST_PULL_COUNTS, JSONB.valueOf(json.writeValueAsString(result.counts + ("drift" to result.drift))))
			.onConflict(MEMBER_DB_STATE.ID).doUpdate()
			.set(MEMBER_DB_STATE.LAST_PULL_AT, OffsetDateTime.now()).set(MEMBER_DB_STATE.LAST_PULL_STATUS, result.status)
			.set(MEMBER_DB_STATE.LAST_PULL_ERROR, result.error)
			.set(MEMBER_DB_STATE.LAST_PULL_COUNTS, JSONB.valueOf(json.writeValueAsString(result.counts + ("drift" to result.drift))))
			.execute()
		return result
	}

	/** Each table's row count and newest change (academic_terms has no updated_at: its newest row). */
	internal fun signature(): String = memberDb.jdbc.sql(
		"""
		SELECT string_agg(t || ':' || n || ':' || coalesce(m::text, ''), ',' ORDER BY t) FROM (
		  SELECT 'volunteers' t, count(*) n, max(updated_at) m FROM volunteers UNION ALL
		  SELECT 'academic_profiles', count(*), max(updated_at) FROM academic_profiles UNION ALL
		  SELECT 'volunteer_accounts', count(*), max(updated_at) FROM volunteer_accounts UNION ALL
		  SELECT 'chapters', count(*), max(updated_at) FROM chapters UNION ALL
		  SELECT 'academic_institutions', count(*), max(updated_at) FROM academic_institutions UNION ALL
		  SELECT 'academic_terms', count(*), max(created_at) FROM academic_terms
		) x
		""".trimIndent(),
	).query(String::class.java).single()

	private fun saveSignature(signature: String) {
		dsl.insertInto(MEMBER_DB_STATE).set(MEMBER_DB_STATE.ID, true).set(MEMBER_DB_STATE.SIGNATURE, signature)
			.onConflict(MEMBER_DB_STATE.ID).doUpdate().set(MEMBER_DB_STATE.SIGNATURE, signature).execute()
	}

	/**
	 * Compares what the portal last wrote for each person with the member database now. A difference is drift: kept
	 * (first seen, last seen) until the two agree again or the portal writes the person again. Returns how many.
	 */
	fun checkDrift(): Int {
		val written = dsl.select(MEMBER_DB_WRITE.PERSON_ID, MEMBER_DB_WRITE.WRITTEN).from(MEMBER_DB_WRITE).where(MEMBER_DB_WRITE.WRITTEN.isNotNull).fetch()
			.associate { it.value1()!! to json.readTree(it.value2()!!.data()).properties().associate { (k, v) -> k to (if (v.isNull) null else v.asString()) } }
		var found = 0
		val now = OffsetDateTime.now()
		for ((person, fields) in written) {
			val volunteerId = fields["volunteer_id"]?.let(UUID::fromString) ?: continue
			val valueThere = current(volunteerId, fields["academic_profiles.id"]?.let(UUID::fromString))
			val differing = fields.filterKeys { it !in setOf("academic_profiles.id", "volunteer_id") }.mapNotNull { (key, portal) ->
				val there = valueThere(key, portal)
				if (same(key, portal, there)) null else Triple(key, portal, there)
			}
			for ((key, portal, there) in differing) {
				dsl.insertInto(MEMBER_DB_DRIFT).set(MEMBER_DB_DRIFT.PERSON_ID, person).set(MEMBER_DB_DRIFT.FIELD, key)
					.set(MEMBER_DB_DRIFT.PORTAL_VALUE, portal).set(MEMBER_DB_DRIFT.MEMBER_DB_VALUE, there)
					.onConflict(MEMBER_DB_DRIFT.PERSON_ID, MEMBER_DB_DRIFT.FIELD).doUpdate()
					.set(MEMBER_DB_DRIFT.PORTAL_VALUE, portal).set(MEMBER_DB_DRIFT.MEMBER_DB_VALUE, there).set(MEMBER_DB_DRIFT.LAST_SEEN_AT, now)
					.execute()
			}
			dsl.deleteFrom(MEMBER_DB_DRIFT).where(MEMBER_DB_DRIFT.PERSON_ID.eq(person), MEMBER_DB_DRIFT.FIELD.notIn(differing.map { it.first })).execute()
			found += differing.size
		}
		return found + deletedThere(now)
	}

	/**
	 * People whose volunteer the member database soft-deleted (Q49, 2026-10-15): reported as [DELETED], not acted on. The
	 * import skips deleted volunteers, so the portal keeps the person as they were; national decides (remove them here,
	 * or have the member database restore the record). Cleared once it's restored or the person is removed.
	 */
	private fun deletedThere(now: OffsetDateTime): Int {
		val deleted = memberDb.jdbc.sql("SELECT id, deleted_at::date::text FROM volunteers WHERE deleted_at IS NOT NULL")
			.query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list().toMap()
		val people = if (deleted.isEmpty()) emptyList() else dsl.select(PERSON.ID, PERSON.SOURCE_ID, PERSON.STATUS).from(PERSON)
			.where(PERSON.SOURCE_ID.`in`(deleted.keys), PERSON.STATUS.ne("removed")).fetch()
		for ((person, volunteer, status) in people) {
			val there = "deleted on ${deleted.getValue(volunteer!!)}"
			dsl.insertInto(MEMBER_DB_DRIFT).set(MEMBER_DB_DRIFT.PERSON_ID, person).set(MEMBER_DB_DRIFT.FIELD, DELETED)
				.set(MEMBER_DB_DRIFT.PORTAL_VALUE, status).set(MEMBER_DB_DRIFT.MEMBER_DB_VALUE, there)
				.onConflict(MEMBER_DB_DRIFT.PERSON_ID, MEMBER_DB_DRIFT.FIELD).doUpdate()
				.set(MEMBER_DB_DRIFT.PORTAL_VALUE, status).set(MEMBER_DB_DRIFT.MEMBER_DB_VALUE, there).set(MEMBER_DB_DRIFT.LAST_SEEN_AT, now)
				.execute()
		}
		dsl.deleteFrom(MEMBER_DB_DRIFT).where(MEMBER_DB_DRIFT.FIELD.eq(DELETED), MEMBER_DB_DRIFT.PERSON_ID.notIn(people.map { it.value1() })).execute()
		return people.size
	}

	/** Reads one volunteer's values: for an account key, whether the written account is still theirs. */
	private fun current(volunteer: UUID, profile: UUID?): (String, String?) -> String? {
		val db = memberDb.jdbc
		val row = db.sql("SELECT first_name, last_name, preferred_name, email, org_email, chapter_id::text, status, type FROM volunteers WHERE id = ?")
			.param(volunteer).query { rs, _ -> (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getString(it) } }.optional().orElse(emptyMap())
		val profileRow = profile?.let {
			db.sql("SELECT email, major, degree_type, graduation_term_id::text AS graduation_term_id FROM academic_profiles WHERE id = ?").param(it)
				.query { rs, _ -> (1..rs.metaData.columnCount).associate { i -> rs.metaData.getColumnName(i) to rs.getString(i) } }.optional().orElse(emptyMap())
		} ?: emptyMap()
		val accounts = db.sql("SELECT provider, external_id FROM volunteer_accounts WHERE volunteer_id = ?").param(volunteer)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
		return { key, portal ->
			val (table, column) = key.substringBefore('.') to key.substringAfter('.')
			when (table) {
				"volunteers" -> row[column]
				"academic_profiles" -> profileRow[column]
				// The account the portal wrote, if it's still theirs; else what they have for that tool now.
				"accounts" -> accounts.filter { it.first == column }.map { it.second }
					.let { theirs -> theirs.firstOrNull { it.equals(portal, ignoreCase = true) } ?: theirs.joinToString(", ").ifEmpty { null } }
				else -> null
			}
		}
	}

	companion object {
		const val DELETED = "volunteers.deleted_at"
	}

	/** Spaces (char columns) and the case of addresses don't count as changes. */
	private fun same(key: String, portal: String?, there: String?): Boolean {
		val a = portal?.trim()?.ifEmpty { null }
		val b = there?.trim()?.ifEmpty { null }
		return if (key.endsWith("email") || key.startsWith("accounts.")) a.equals(b, ignoreCase = true) else a == b
	}
}
