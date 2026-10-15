package org.hack4impact.portal.memberimport

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Copies the national member database into the portal's tables: schools, terms, chapters, people and their tool
 * accounts (stage 1; wiki: staged-rollout). Projects and partners come with stage 2.
 *
 * The member DB is read-only and incomplete; the portal's data wins. Each row is upserted by
 * `source_id`, and an existing row is only updated while nobody has edited it in the portal
 * (`updated_at <= imported_at`) and, for people, before they've claimed their account.
 * Every row runs in its own savepoint, so one bad row is reported instead of failing the run.
 * Call inside a transaction.
 */
@Component
class MemberDbImporter(private val dsl: DSLContext) {

	fun import(source: JdbcClient): ImportReport {
		val report = ImportReport()
		val institutions = importInstitutions(source, report)
		val terms = importTerms(source, report, institutions)
		val chapters = importChapters(source, report, institutions)
		val people = importPeople(source, report, chapters, terms)
		importToolAccounts(source, report, people)
		reportLeadership(source, report)
		return report
	}

	// Lookups -------------------------------------------------------------------------------

	private fun importInstitutions(source: JdbcClient, report: ImportReport): Map<UUID, UUID> {
		val counts = report.counts("institution")
		source.sql("SELECT id, name FROM academic_institutions ORDER BY id").query { rs, _ ->
			counts.read++
			upsert(report, "institution", INSTITUTION, rs.uuid("id"), mapOf(INSTITUTION.NAME to rs.getString("name").trim()))
		}.list()
		return sourceMap(INSTITUTION)
	}

	private fun importTerms(source: JdbcClient, report: ImportReport, institutions: Map<UUID, UUID>): Map<UUID, UUID> {
		val counts = report.counts("term")
		source.sql("SELECT id, academic_institution_id, season, year, code, start_date, end_date FROM academic_terms ORDER BY id")
			.query { rs, _ ->
				counts.read++
				val id = rs.uuid("id")
				val season = rs.getString("season").trim().lowercase()
				val institution = institutions[rs.uuid("academic_institution_id")]
				when {
					season !in setOf("spring", "summer", "fall", "winter") ->
						skip(report, "term", id, "unknown season '$season'")
					institution == null -> skip(report, "term", id, "institution not imported")
					else -> {
						upsert(
							report, "term", TERM, id,
							mapOf(
								TERM.INSTITUTION_ID to institution,
								TERM.SEASON to season,
								TERM.YEAR to rs.getShort("year"),
								TERM.CODE to rs.getString("code")?.trim(),
								TERM.STARTS_ON to rs.date("start_date"),
								TERM.ENDS_ON to rs.date("end_date"),
							),
						)
					}
				}
			}.list()
		return sourceMap(TERM)
	}

	private fun importChapters(source: JdbcClient, report: ImportReport, institutions: Map<UUID, UUID>): Map<UUID, UUID> {
		val counts = report.counts("chapter")
		source.sql("SELECT id, name, code, academic_institution_id, status, website, email, deleted_at FROM chapters ORDER BY id")
			.query { rs, _ ->
				counts.read++
				val id = rs.uuid("id")
				val code = rs.getString("code").trim().lowercase() // char(20): padded with spaces
				when {
					rs.getObject("deleted_at") != null -> counts.skipped++
					!code.matches(Regex("^[a-z0-9][a-z0-9_-]*$")) -> skip(report, "chapter", id, "code '$code' isn't a valid slug")
					else -> upsert(
						report, "chapter", CHAPTER, id,
						mapOf(
							CHAPTER.NAME to rs.getString("name").trim(),
							CHAPTER.CODE to code,
							CHAPTER.INSTITUTION_ID to institutions[rs.uuid("academic_institution_id")],
							CHAPTER.STATUS to rs.getString("status"),
							CHAPTER.WEBSITE to rs.getString("website"),
							CHAPTER.EMAIL to rs.getString("email").email(),
						),
					)
				}
			}.list()
		return sourceMap(CHAPTER)
	}

	private data class Profile(val email: String?, val termId: UUID?, val updatedAt: OffsetDateTime)

	private fun importPeople(
		source: JdbcClient,
		report: ImportReport,
		chapters: Map<UUID, UUID>,
		terms: Map<UUID, UUID>,
	): Map<UUID, UUID> {
		val counts = report.counts("person")
		val profiles = source.sql("SELECT volunteer_id, email, graduation_term_id, updated_at FROM academic_profiles")
			.query { rs, _ -> rs.uuid("volunteer_id") to Profile(rs.getString("email").email(), rs.uuidOrNull("graduation_term_id"), rs.offsetDateTime("updated_at")) }
			.list()
			.groupBy({ it.first }, { it.second })
		val byEmail = mutableMapOf<String, MutableList<String>>()
		val memberships = mutableListOf<Pair<UUID, UUID>>()

		source.sql(
			"""
			SELECT id, first_name, last_name, preferred_name, email, org_email, chapter_id, status, type, deleted_at
			FROM volunteers ORDER BY id
			""".trimIndent(),
		).query { rs, _ ->
			counts.read++
			val id = rs.uuid("id")
			if (rs.getObject("deleted_at") != null) {
				counts.skipped++
				return@query
			}
			val name = "${rs.getString("first_name").trim()} ${rs.getString("last_name").trim()}"
			val status = rs.getString("status")
			val type = rs.getString("type")
			val personal = rs.getString("email").email()
			val org = rs.getString("org_email").email()
			val mine = profiles[id].orEmpty().sortedByDescending { it.updatedAt }
			val school = mine.firstNotNullOfOrNull { it.email }
			if (mine.mapNotNull { it.email }.distinct().size > 1) {
				report.issue("person", id, "needs review", "$name has ${mine.size} school records with different emails; used the newest ($school)")
			}
			if (personal == null && school == null && org == null) {
				report.issue("person", id, "needs review", "$name has no email, so they can't receive a claim link")
			}
			listOfNotNull(personal, school, org).distinct().forEach { byEmail.getOrPut(it) { mutableListOf() } += "$id ($name)" }

			upsert(
				report, "person", PERSON, id,
				mapOf(
					PERSON.FIRST_NAME to rs.getString("first_name").trim(),
					PERSON.LAST_NAME to rs.getString("last_name").trim(),
					PERSON.PREFERRED_NAME to rs.getString("preferred_name")?.trim()?.ifEmpty { null },
					PERSON.PERSONAL_EMAIL to personal,
					PERSON.SCHOOL_EMAIL to school,
					PERSON.ORG_EMAIL to org,
					PERSON.GRADUATION_TERM_ID to mine.firstNotNullOfOrNull { it.termId }?.let { terms[it] },
					PERSON.STATUS to personStatus(status, type),
					PERSON.KIND to if (type == "community") "community" else "student",
					PERSON.SOURCE_STATUS to "$status/$type",
				),
				extraGuard = PERSON.CLAIMED_AT.isNull,
			)

			val chapterId = rs.uuidOrNull("chapter_id")
			when {
				chapterId == null -> {}
				chapters[chapterId] == null -> report.issue("person", id, "needs review", "$name belongs to a chapter that wasn't imported")
				else -> memberships += id to chapters.getValue(chapterId)
			}
		}.list()

		byEmail.filterValues { it.size > 1 }.forEach { (email, who) ->
			report.issue("person", null, "possible duplicate", "$email is used by ${who.joinToString("; ")}")
		}

		val people = sourceMap(PERSON)
		val membershipCounts = report.counts("chapter_membership")
		memberships.forEach { (volunteer, chapter) ->
			membershipCounts.read++
			val inserted = dsl.insertInto(CHAPTER_MEMBERSHIP)
				.set(CHAPTER_MEMBERSHIP.PERSON_ID, people.getValue(volunteer))
				.set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter)
				.onConflictDoNothing()
				.execute()
			if (inserted == 1) membershipCounts.inserted++ else membershipCounts.kept++
		}
		return people
	}

	private fun importToolAccounts(source: JdbcClient, report: ImportReport, people: Map<UUID, UUID>) {
		val counts = report.counts("tool_account")
		val seenRows = mutableSetOf<Triple<UUID, String, String>>()
		val ownerOf = mutableMapOf<Pair<String, String>, UUID>()
		val unmanaged = mutableMapOf<String, Int>()

		source.sql("SELECT id, volunteer_id, provider, external_id FROM volunteer_accounts ORDER BY id, volunteer_id").query { rs, _ ->
			counts.read++
			val id = rs.uuid("id")
			val volunteer = rs.uuid("volunteer_id")
			val tool = rs.getString("provider")
			val raw = rs.getString("external_id").trim()
			val github = if (tool == "github") GitHubValue.parse(raw) else null
			val value = github?.value ?: raw
			val person = people[volunteer]
			when {
				!seenRows.add(Triple(id, tool, value)) -> counts.skipped++ // exact duplicate row; the table has no primary key
				tool !in setOf("google", "github", "slack", "notion") -> {
					counts.skipped++
					unmanaged.merge(tool, 1, Int::plus)
				}
				person == null -> counts.skipped++ // volunteer was soft-deleted or not imported
				github?.problem != null -> skip(report, "tool_account", id, "GitHub value '$raw' ${github.problem}")
				ownerOf[tool to value] == volunteer -> counts.skipped++ // same link stored twice under different row IDs
				ownerOf[tool to value] != null ->
					skip(report, "tool_account", id, "$tool account '$value' is also linked to volunteer ${ownerOf[tool to value]}")
				else -> {
					ownerOf[tool to value] = volunteer
					// A person may have several accounts per tool (e.g. Slack IDs in two workspaces); all are kept.
					// GitHub: a bare number is a user ID, a username is only a login. Elsewhere '@' means an email (a login).
					val isId = github?.isId ?: ('@' !in value)
					upsert(
						report, "tool_account", TOOL_ACCOUNT, id,
						mapOf(
							TOOL_ACCOUNT.PERSON_ID to person,
							TOOL_ACCOUNT.TOOL to tool,
							TOOL_ACCOUNT.EXTERNAL_ID to value.takeIf { isId },
							TOOL_ACCOUNT.EXTERNAL_LOGIN to value.takeUnless { isId },
							TOOL_ACCOUNT.STATE to "unverified",
						),
					)
				}
			}
		}.list()
		unmanaged.forEach { (tool, n) -> report.issue("tool_account", null, "not imported", "$n $tool links (the portal doesn't manage $tool)") }
	}

	// Projects ------------------------------------------------------------------------------

	private fun reportLeadership(source: JdbcClient, report: ImportReport) {
		val n = source.sql("SELECT count(*) FROM leadership_assignments").query(Int::class.java).single()
		if (n > 0) {
			report.issue("chapter_role", null, "not imported", "$n leadership assignments: the member DB doesn't record who or which chapter; enter leads in the portal")
		}
	}

	// Helpers -------------------------------------------------------------------------------

	/**
	 * Inserts or updates the row with this source_id. An update only happens while the portal row is
	 * unedited since the last import (plus [extraGuard]); otherwise the row is counted as kept.
	 */
	private fun <R : Record> upsert(
		report: ImportReport,
		entity: String,
		table: Table<R>,
		sourceId: UUID,
		values: Map<out Field<*>, Any?>,
		extraGuard: Condition = DSL.noCondition(),
	) {
		val counts = report.counts(entity)
		val sourceIdField = table.field("source_id", UUID::class.java)!!
		val importedAt = table.field("imported_at", OffsetDateTime::class.java)!!
		val updatedAt = table.field("updated_at", OffsetDateTime::class.java)!!
		val now = DSL.currentOffsetDateTime()
		try {
			val result = dsl.transactionResult { tx ->
				tx.dsl().insertInto(table)
					.set(values)
					.set(sourceIdField, sourceId)
					.set(importedAt, now)
					.onConflict(sourceIdField)
					.doUpdate()
					.set(values)
					.set(importedAt, now)
					.where(updatedAt.le(importedAt).and(extraGuard))
					.returningResult(DSL.field("xmax = 0", Boolean::class.java))
					.fetchOne()
			}
			when {
				result == null -> counts.kept++
				result.value1() == true -> counts.inserted++
				else -> counts.updated++
			}
		} catch (e: org.springframework.dao.DataIntegrityViolationException) {
			skip(report, entity, sourceId, "rejected by the portal schema: ${e.mostSpecificCause.message?.lineSequence()?.first()}")
		} catch (e: org.jooq.exception.DataAccessException) {
			skip(report, entity, sourceId, "rejected by the portal schema: ${e.cause?.message?.lineSequence()?.first() ?: e.message}")
		}
	}

	private fun skip(report: ImportReport, entity: String, sourceId: UUID, why: String) {
		report.counts(entity).skipped++
		report.issue(entity, sourceId, "skipped", why)
	}

	private fun sourceMap(table: Table<*>): Map<UUID, UUID> {
		val sourceId = table.field("source_id", UUID::class.java)!!
		val id = table.field("id", UUID::class.java)!!
		return dsl.select(sourceId, id).from(table).where(sourceId.isNotNull).fetchMap(sourceId, id)
	}

	private fun personStatus(status: String, type: String) = when {
		status == "suspended" -> "removed"
		status == "active" && type == "student" -> "active"
		else -> "alumni" // type alumni or community, status inactive or hiatus: alumni until decided (wiki decision 40)
	}
}

/**
 * A member-DB GitHub value: a bare number (user ID), a username, or a URL. Real data has profile URLs
 * (`https://github.com/name`, sometimes with a repo path) and usernames with `https://` stuck in front.
 */
internal data class GitHubValue(val value: String, val isId: Boolean, val problem: String? = null) {
	companion object {
		private val username = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")

		fun parse(raw: String): GitHubValue {
			if (raw.all { it.isDigit() } && raw.isNotEmpty()) return GitHubValue(raw, isId = true)
			val rest = raw.replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
				.replaceFirst(Regex("^(www\\.)?github\\.com/?", RegexOption.IGNORE_CASE), "")
			val first = rest.substringBefore('/').substringBefore('?').substringBefore('#')
			return when {
				first.contains('.') -> GitHubValue(raw, isId = false, problem = "isn't a GitHub profile")
				!username.matches(first) -> GitHubValue(raw, isId = false, problem = "has no valid GitHub username")
				else -> GitHubValue(first, isId = false)
			}
		}
	}
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.offsetDateTime(column: String): OffsetDateTime = getObject(column, OffsetDateTime::class.java)

private fun ResultSet.date(column: String): LocalDate = offsetDateTime(column).toLocalDate()

private fun ResultSet.dateOrNull(column: String): LocalDate? = getObject(column, OffsetDateTime::class.java)?.toLocalDate()

private fun String?.email(): String? = this?.trim()?.lowercase()?.ifEmpty { null }
