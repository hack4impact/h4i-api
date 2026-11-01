package org.hack4impact.portal.national

import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.sync.SyncRequests
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

data class QueueTask(
	val id: UUID,
	val tool: String,
	/**
	 * create_account (a Google account to make by hand), check_account (a GitHub account linked to two people),
	 * confirm_removals (a run held at the removal limit), add_member or remove_member (a change to make by hand),
	 * create_resource (creation kept failing), resource_exists (decided with the resource's link endpoints), …
	 */
	val action: String,
	val description: String,
	val createdAt: OffsetDateTime,
	val status: String,
	val doneBy: String?,
	val doneAt: OffsetDateTime?,
	/** For confirm_removals: the held run it shows; sent back with "done". */
	val runId: UUID? = null,
	/** The resource a sync task is about (resource_exists: preview it at /api/national/resources/{id}/link). */
	val resourceId: UUID? = null,
)

/** [action]: done (made by hand, or removals confirmed) or cancel. [runId]: the run a confirm_removals task showed. */
data class TaskDecision(val action: String, val reason: String? = null, val runId: UUID? = null)

data class ToolStatus(
	val tool: String,
	/** The kill switch: off = the tool isn't synced at all. */
	val enabled: Boolean,
	/** Planned only, never changed. Every tool starts in dry run. */
	val dryRun: Boolean,
	/** Whether this deployment can change the tool (its `…_WRITE` switch). Leaving dry run needs it. */
	val writable: Boolean,
	val pauseReason: String?,
	val maxRemovals: Int,
	val lastRun: LastRun?,
)

data class LastRun(val status: String, val mode: String, val ranAt: OffsetDateTime, val applied: Int, val removed: Int, val error: String?)

data class ToolChange(val enabled: Boolean, val dryRun: Boolean, val reason: String? = null)

data class DeadLetter(
	val personId: UUID,
	val person: String,
	val resourceId: UUID,
	val tool: String,
	val resource: String,
	val attempts: Int,
	val lastError: String?,
	val lastAttemptAt: OffsetDateTime?,
)

data class DeadLetterRef(val personId: UUID, val resourceId: UUID)

/**
 * National controls: the admin queue, and for the sync (stage 2) each syncing tool's kill switch and dry run, dead
 * letters, and running every chapter now. National admins only (claim links are in the registration API, linking
 * resources in [NationalResourceController]).
 */
@RestController
@RequestMapping("/api/national")
class NationalController(
	private val viewers: Viewers,
	private val dsl: DSLContext,
	private val writers: List<WriteAdapter>,
	private val syncs: SyncRequests,
	private val queue: AdminQueue,
	private val tools: ToolRegistry,
) {
	private val json = JsonMapper.builder().build()

	private fun national(user: OidcUser): Viewer =
		viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping("/queue")
	fun queue(@AuthenticationPrincipal user: OidcUser): List<QueueTask> {
		national(user)
		return queue.tasks()
	}

	@PostMapping("/queue/{id}")
	fun decide(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestBody decision: TaskDecision): List<QueueTask> {
		val viewer = national(user)
		when (decision.action) {
			"done" -> queue.done(id, viewer, decision.runId)
			"cancel" -> queue.cancel(id, viewer, decision.reason)
			else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be done or cancel")
		}
		return queue.tasks()
	}

	/** The syncing tools (Slack, Google, GitHub, Vaultwarden) and their switches, in list order. */
	@GetMapping("/tools")
	fun tools(@AuthenticationPrincipal user: OidcUser): List<ToolStatus> {
		national(user)
		val writable = writable()
		val runs = dsl.selectFrom(SYNC_RUN).orderBy(SYNC_RUN.STARTED_AT.desc()).limit(500).fetch().filter { it.tool != null }.distinctBy { it.tool }.associateBy { it.tool }
		val syncing = tools.syncTools.map { it.key }.toSet()
		return dsl.selectFrom(TOOL_SETTING).fetch().filter { it.tool in syncing }.sortedBy { tools.find(it.tool)!!.order }.map { s ->
			val run = runs[s.tool]
			ToolStatus(
				s.tool!!, s.enabled!!, s.dryRun!!, s.tool in writable, s.pauseReason, s.maxRemovals!!,
				run?.let { LastRun(it.status!!, it.mode!!, it.startedAt!!, it.appliedAdds!!, it.appliedRemoves!!, it.error) },
			)
		}
	}

	@PutMapping("/tools/{tool}")
	@Transactional
	fun setTool(@AuthenticationPrincipal user: OidcUser, @PathVariable tool: String, @RequestBody change: ToolChange): List<ToolStatus> {
		val viewer = national(user)
		if (tools.find(tool)?.syncs != true) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No syncing tool $tool")
		val setting = dsl.selectFrom(TOOL_SETTING).where(TOOL_SETTING.TOOL.eq(tool)).fetchOne() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No tool $tool")
		if (!change.dryRun && tool !in writable()) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "This deployment can't change $tool: its write switch (PORTAL_ADAPTERS_${tool.uppercase()}_WRITE) is off")
		}
		if (!change.enabled && change.reason.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the tool is paused")
		dsl.update(TOOL_SETTING)
			.set(TOOL_SETTING.ENABLED, change.enabled).set(TOOL_SETTING.DRY_RUN, change.dryRun)
			.set(TOOL_SETTING.PAUSED_BY, if (change.enabled) null else viewer.personId)
			.set(TOOL_SETTING.PAUSE_REASON, if (change.enabled) null else change.reason?.trim())
			.where(TOOL_SETTING.TOOL.eq(tool)).execute()
		audit(viewer, "tool.setting", "tool_setting", null, mapOf(
			"tool" to tool, "enabled" to change.enabled, "dryRun" to change.dryRun, "wasEnabled" to setting.enabled, "wasDryRun" to setting.dryRun, "reason" to change.reason,
		))
		return tools(user)
	}

	/** Tools this deployment can change: those with a write adapter (their `…_WRITE` switch is on). */
	private fun writable(): Set<String> = writers.map { it.tool.name.lowercase() }.toSet()

	@GetMapping("/dead-letters")
	fun deadLetters(@AuthenticationPrincipal user: OidcUser): List<DeadLetter> {
		national(user)
		return dsl.select(
			SYNC_RECORD.PERSON_ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, SYNC_RECORD.RESOURCE_ID, RESOURCE.TOOL, RESOURCE.NAME,
			SYNC_RECORD.ATTEMPTS, SYNC_RECORD.LAST_ERROR, SYNC_RECORD.LAST_ATTEMPT_AT,
		).from(SYNC_RECORD).join(PERSON).on(PERSON.ID.eq(SYNC_RECORD.PERSON_ID)).join(RESOURCE).on(RESOURCE.ID.eq(SYNC_RECORD.RESOURCE_ID))
			.where(SYNC_RECORD.STATUS.eq("dead_letter"))
			.orderBy(SYNC_RECORD.LAST_ATTEMPT_AT.desc())
			.fetch {
				DeadLetter(
					it.value1()!!, "${it.value4()?.takeIf { p -> p.isNotBlank() } ?: it.value2()} ${it.value3()}", it.value5()!!, it.value6()!!, it.value7()!!,
					it.value8()!!, it.value9(), it.value10(),
				)
			}
	}

	/** Puts dead letters back in line: the next run tries them again. Without IDs, all of them. */
	@PostMapping("/dead-letters/retry")
	@Transactional
	fun retry(@AuthenticationPrincipal user: OidcUser, @RequestBody(required = false) only: List<DeadLetterRef>?): List<DeadLetter> {
		val viewer = national(user)
		var condition = SYNC_RECORD.STATUS.eq("dead_letter")
		if (!only.isNullOrEmpty()) {
			condition = condition.and(DSL.or(only.map { SYNC_RECORD.PERSON_ID.eq(it.personId).and(SYNC_RECORD.RESOURCE_ID.eq(it.resourceId)) }))
		}
		val count = dsl.update(SYNC_RECORD).set(SYNC_RECORD.STATUS, "pending").set(SYNC_RECORD.ATTEMPTS, 0).setNull(SYNC_RECORD.NEXT_RETRY_AT)
			.where(condition).execute()
		audit(viewer, "sync.retry_dead_letters", "sync_record", null, mapOf("count" to count))
		return deadLetters(user)
	}

	/** Runs every tool for every chapter now, as its settings say (dry run or apply), through the outbox. */
	@PostMapping("/sync/run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun run(@AuthenticationPrincipal user: OidcUser) {
		national(user)
		syncs.request("manual")
	}

	private fun audit(viewer: Viewer, action: String, targetType: String, targetId: UUID?, after: Map<String, Any?>) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, targetType).set(AUDIT_EVENT.TARGET_ID, targetId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(after)))
			.execute()
	}
}
