package org.hack4impact.portal.chapters

import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.sync.SyncRequests
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/** The latest sync run per tool, limited to this chapter's resources. */
data class ChapterSync(val tools: List<ToolSync>, val changes: List<PlannedChange>)

data class ToolSync(
	val tool: String,
	/** completed, paused (a real run would stop: removal limit or kill switch), or failed. */
	val status: String,
	val mode: String,
	val ranAt: OffsetDateTime,
	val error: String?,
	val adds: Int,
	val changes: Int,
	val removals: Int,
	val drift: Int,
	val unmatchedAccounts: Int,
	val missingResources: Int,
	val creates: Int,
	/** Resources whose name the tool already has: waiting for national to link, rename or leave them. */
	val existing: Int,
)

/**
 * A change from a tool's latest run. [kind] is add, change, remove, drift, unmatched_account, missing_resource,
 * create_resource, archive_resource or resource_exists. [mode] dry_run: it would be made; apply: the run made or tried
 * it, with [outcome] (applied, invited, waiting, held, failed, dead_letter, skipped, queued).
 */
data class PlannedChange(
	val kind: String,
	val tool: String,
	val resource: String,
	val person: String?,
	val accountId: String?,
	val fromAccess: String?,
	val toAccess: String?,
	val mode: String = "dry_run",
	val outcome: String? = null,
)

/** From the full portal's `ChapterQueries.sync` (stage 2). */
@Component
class ChapterSyncQueries(private val dsl: DSLContext) {
	fun sync(chapterId: UUID, limit: Int = 200): ChapterSync {
		val latest = dsl.selectFrom(SYNC_RUN).orderBy(SYNC_RUN.STARTED_AT.desc()).limit(500).fetch()
			.filter { it.tool != null }.distinctBy { it.tool }
		val runIds = latest.map { it.id!! }
		val rows = dsl.select(SYNC_CHANGE.RUN_ID, SYNC_CHANGE.KIND, RESOURCE.TOOL, RESOURCE.NAME, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME,
			SYNC_CHANGE.ACCOUNT_ID, SYNC_CHANGE.FROM_ACCESS, SYNC_CHANGE.TO_ACCESS, SYNC_CHANGE.OUTCOME)
			.from(SYNC_CHANGE)
			.join(RESOURCE).on(RESOURCE.ID.eq(SYNC_CHANGE.RESOURCE_ID))
			.leftJoin(PERSON).on(PERSON.ID.eq(SYNC_CHANGE.PERSON_ID))
			.where(SYNC_CHANGE.RUN_ID.`in`(runIds), RESOURCE.CHAPTER_ID.eq(chapterId))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME, SYNC_CHANGE.KIND)
			.fetch()
		val modes = latest.associate { it.id!! to it.mode!! }
		val counts = rows.groupBy { it.value1()!! }.mapValues { (_, r) -> r.groupingBy { it.value2()!! }.eachCount() }
		return ChapterSync(
			tools = latest.map { run ->
				val c = counts[run.id].orEmpty()
				ToolSync(
					run.tool!!, run.status!!, run.mode!!, run.startedAt!!, run.error,
					c["add"] ?: 0, c["change"] ?: 0, c["remove"] ?: 0, c["drift"] ?: 0, c["unmatched_account"] ?: 0, c["missing_resource"] ?: 0,
					c["create_resource"] ?: 0, c["resource_exists"] ?: 0,
				)
			}.sortedBy { it.tool },
			changes = rows.take(limit).map {
				val first = it.value7()?.takeIf { p -> p.isNotBlank() } ?: it.value5()
				PlannedChange(it.value2()!!, it.value3()!!, it.value4()!!, first?.let { f -> "$f ${it.value6()}" }, it.value8(), it.value9(), it.value10(), modes.getValue(it.value1()!!), it.value11())
			},
		)
	}
}

/** A chapter's sync status and a lead's dry run (decision 157). Leads, co-leads and national. */
@RestController
@RequestMapping("/api/chapters/{code}/sync")
class ChapterSyncController(private val access: ChapterAccess, private val queries: ChapterSyncQueries, private val syncs: SyncRequests) {
	@GetMapping
	fun sync(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): ChapterSync {
		val (_, chapter) = access.manage(user, code)
		return queries.sync(chapter.id)
	}

	/** Plans every tool now without changing any, whatever their settings, after this request commits. */
	@PostMapping("/run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun dryRun(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String) {
		access.manage(user, code)
		syncs.dryRun()
	}
}
