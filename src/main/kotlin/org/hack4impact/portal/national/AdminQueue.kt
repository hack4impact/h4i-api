package org.hack4impact.portal.national

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The national admin queue (PRD): things the portal couldn't do itself, written as actions for a person to make (a
 * Google account when a chapter has no org unit yet, a GitHub account linked to two people), runs held at the removal
 * limit, resources that couldn't be created, and names that already exist in a tool. Marking a member task done is the
 * record that it was applied; tasks that are no longer wanted cancel themselves at the next run. Ported from the full
 * portal for stage 2, with the `resource_exists` task (wiki: staged-rollout-stage-2, linking existing resources).
 */
@Component
class AdminQueue(private val dsl: DSLContext, private val syncs: org.hack4impact.portal.sync.SyncRequests) {
	companion object {
		/** Why a tool's listing may not show something it has: Slack shows a private channel only once the portal's app is in it (decision 90). */
		fun unlistedHint(tool: Tool) = if (tool == Tool.SLACK) " If it's a private channel, invite the portal's Slack app to it first." else ""

		/** A confirmation of held removals lets runs within this window make those removals (and no others). */
		val CONFIRMATION_WINDOW: Duration = Duration.ofHours(24)
	}

	fun tasks(): List<QueueTask> =
		dsl.select(ADMIN_TASK.ID, ADMIN_TASK.TOOL, ADMIN_TASK.ACTION, ADMIN_TASK.DESCRIPTION, ADMIN_TASK.CREATED_AT, ADMIN_TASK.STATUS,
			PERSON.FIRST_NAME, PERSON.LAST_NAME, ADMIN_TASK.DONE_AT, ADMIN_TASK.RUN_ID, ADMIN_TASK.RESOURCE_ID)
			.from(ADMIN_TASK).leftJoin(PERSON).on(PERSON.ID.eq(ADMIN_TASK.DONE_BY))
			.where(ADMIN_TASK.STATUS.eq("open").or(ADMIN_TASK.UPDATED_AT.gt(OffsetDateTime.now().minusDays(7))))
			.orderBy(ADMIN_TASK.STATUS.desc(), ADMIN_TASK.TOOL, ADMIN_TASK.CREATED_AT)
			.limit(500)
			.fetch { QueueTask(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!, it.value7()?.let { f -> "$f ${it.value8()}" }, it.value9(), it.value10(), it.value11()) }

	/** Opens a member task unless the same one is already open. */
	fun open(tool: Tool, action: String, person: UUID, resource: UUID, description: String) {
		dsl.insertInto(ADMIN_TASK)
			.set(ADMIN_TASK.TOOL, tool.name.lowercase()).set(ADMIN_TASK.ACTION, action).set(ADMIN_TASK.PERSON_ID, person).set(ADMIN_TASK.RESOURCE_ID, resource)
			.set(ADMIN_TASK.DESCRIPTION, description)
			.onConflictDoNothing()
			.execute()
	}

	/**
	 * Marks a task done. For held removals, [runId] is the run the page showed: a later run rewrites the task, and
	 * national confirms only what they saw (second review L4).
	 */
	@Transactional
	fun done(id: UUID, viewer: Viewer, runId: UUID? = null) {
		val task = openTask(id, lock = true)
		decidedElsewhere(task.action)
		if (task.action == "confirm_removals" && runId != task.runId) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "Newer removals were held since this page loaded. Reload and check them before confirming.")
		}
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "done").set(ADMIN_TASK.DONE_BY, viewer.personId).set(ADMIN_TASK.DONE_AT, OffsetDateTime.now())
			.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(id)).execute()
		val record = SYNC_RECORD.PERSON_ID.eq(task.personId).and(SYNC_RECORD.RESOURCE_ID.eq(task.resourceId))
		when (task.action) {
			// The person did it by hand: from now on it counts as the portal's, like an applied change.
			"add_member" -> dsl.update(SYNC_RECORD).set(SYNC_RECORD.ACTUAL, "present").set(SYNC_RECORD.STATUS, "in_sync")
				.set(SYNC_RECORD.APPLIED_AT, OffsetDateTime.now()).set(SYNC_RECORD.APPLIED_ACCESS_LEVEL, SYNC_RECORD.DESIRED_ACCESS_LEVEL)
				.set(SYNC_RECORD.ATTEMPTS, 0).where(record).execute()
			"remove_member" -> dsl.update(SYNC_RECORD).set(SYNC_RECORD.ACTUAL, "absent").set(SYNC_RECORD.STATUS, "in_sync")
				.setNull(SYNC_RECORD.APPLIED_AT).setNull(SYNC_RECORD.APPLIED_ACCESS_LEVEL).where(record).execute()
			// The held removals go ahead now rather than at the next scheduled run (review M9).
			"confirm_removals" -> Tool.fromKey(task.tool)?.let { syncs.request("manual", setOf(it)) }
			// National fixed what stopped it: tried again at once, with a fresh count (second review L3).
			"create_resource" -> {
				dsl.update(RESOURCE).set(RESOURCE.CREATE_ATTEMPTS, 0).setNull(RESOURCE.CREATE_RETRY_AT).where(RESOURCE.ID.eq(task.resourceId)).execute()
				Tool.fromKey(task.tool)?.let { syncs.request("manual", setOf(it)) }
			}
		}
		audit(viewer, "queue.done", id, task.description)
	}

	@Transactional
	fun cancel(id: UUID, viewer: Viewer?, reason: String?) {
		val task = openTask(id)
		decidedElsewhere(task.action)
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, reason ?: "cancelled by national")
			.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(id)).execute()
		if (viewer != null) audit(viewer, "queue.cancel", id, task.description)
	}

	/**
	 * Cancels open member tasks the plan no longer wants: adds for pairs not desired any more, removals for pairs that
	 * are desired again (PRD: "if the desired state changes before a task is done, the task cancels itself").
	 */
	fun reconcile(tool: Tool, desired: Set<Pair<UUID, UUID>>) {
		// Only membership tasks: others name a person without a resource (step 10's create_account, GitHub conflicts).
		val open = dsl.selectFrom(ADMIN_TASK).where(
			ADMIN_TASK.TOOL.eq(tool.name.lowercase()), ADMIN_TASK.STATUS.eq("open"), ADMIN_TASK.ACTION.`in`("add_member", "remove_member"),
			ADMIN_TASK.PERSON_ID.isNotNull, ADMIN_TASK.RESOURCE_ID.isNotNull,
		).fetch()
		for (task in open) {
			val wanted = (task.personId!! to task.resourceId!!) in desired
			if ((task.action == "add_member" && !wanted) || (task.action == "remove_member" && wanted)) {
				dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, "no longer needed")
					.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(task.id)).execute()
			}
		}
	}

	/**
	 * Whether national confirmed these removals (person, resource) for [tool] within the window: every one of them was
	 * among those a confirmed run held. A run with other removals waits for its own confirmation (review M9).
	 */
	fun removalsConfirmed(tool: Tool, removals: Collection<Pair<UUID, UUID>>): Boolean {
		val runs = dsl.select(ADMIN_TASK.RUN_ID).from(ADMIN_TASK)
			.where(
				ADMIN_TASK.TOOL.eq(tool.name.lowercase()), ADMIN_TASK.ACTION.eq("confirm_removals"), ADMIN_TASK.STATUS.eq("done"),
				ADMIN_TASK.DONE_AT.gt(OffsetDateTime.now().minus(CONFIRMATION_WINDOW)), ADMIN_TASK.RUN_ID.isNotNull,
			).fetch().map { it.value1()!! }
		return runs.any { run ->
			val held = dsl.select(SYNC_CHANGE.PERSON_ID, SYNC_CHANGE.RESOURCE_ID).from(SYNC_CHANGE)
				.where(SYNC_CHANGE.RUN_ID.eq(run), SYNC_CHANGE.OUTCOME.eq("held"), SYNC_CHANGE.KIND.`in`("remove", "change"))
				.fetch().map { it.value1()!! to it.value2()!! }.toSet()
			held.containsAll(removals)
		}
	}

	/**
	 * Asks national to confirm a run's held removals: one open task per tool, brought up to date with the latest run
	 * rather than a new task every run (review M9).
	 */
	fun confirmRemovals(tool: Tool, runId: UUID, description: String) {
		val updated = dsl.update(ADMIN_TASK).set(ADMIN_TASK.RUN_ID, runId).set(ADMIN_TASK.DESCRIPTION, description).set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now())
			.where(ADMIN_TASK.TOOL.eq(tool.name.lowercase()), ADMIN_TASK.ACTION.eq("confirm_removals"), ADMIN_TASK.STATUS.eq("open")).execute()
		if (updated == 0) {
			dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, tool.name.lowercase()).set(ADMIN_TASK.ACTION, "confirm_removals").set(ADMIN_TASK.RUN_ID, runId)
				.set(ADMIN_TASK.DESCRIPTION, description).execute()
		}
	}

	/** A resource the portal couldn't create after its tries: one open task for it, until national marks it done. */
	fun creationFailed(tool: Tool, resource: UUID, name: String, error: String?) {
		if (dsl.fetchExists(ADMIN_TASK, ADMIN_TASK.ACTION.eq("create_resource"), ADMIN_TASK.RESOURCE_ID.eq(resource), ADMIN_TASK.STATUS.eq("open"))) return
		val label = tool.name.lowercase().replaceFirstChar { it.uppercase() }
		dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, tool.name.lowercase()).set(ADMIN_TASK.ACTION, "create_resource").set(ADMIN_TASK.RESOURCE_ID, resource)
			.set(ADMIN_TASK.DESCRIPTION, "Couldn't create $name in $label: ${error ?: "unknown error"}. Fix the cause, then mark this done to try again.".take(1000))
			.execute()
	}

	/** A name that already exists in a tool: one open task per resource, closed by national's decision on it. */
	fun resourceExists(tool: Tool, resource: UUID, name: String, listed: Boolean) {
		if (dsl.fetchExists(ADMIN_TASK, ADMIN_TASK.ACTION.eq("resource_exists"), ADMIN_TASK.RESOURCE_ID.eq(resource), ADMIN_TASK.STATUS.eq("open"))) return
		val label = tool.name.lowercase().replaceFirstChar { it.uppercase() }
		val hint = if (listed) "" else " The portal can't see it in the tool's listing.${unlistedHint(tool)}"
		dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, tool.name.lowercase()).set(ADMIN_TASK.ACTION, "resource_exists").set(ADMIN_TASK.RESOURCE_ID, resource)
			.set(ADMIN_TASK.DESCRIPTION, "$name already exists in $label. Link it, rename the portal's, or leave it unmanaged.$hint".take(1000))
			.execute()
	}

	/**
	 * Resources of [tool] that are no longer in it (deleted there): one open task each, and the tasks of resources that
	 * are back (or archived, unmanaged or relinked since) cancel themselves.
	 */
	fun resourcesMissing(tool: Tool, missing: Set<UUID>) {
		val key = tool.name.lowercase()
		val open = dsl.select(ADMIN_TASK.ID, ADMIN_TASK.RESOURCE_ID).from(ADMIN_TASK)
			.where(ADMIN_TASK.TOOL.eq(key), ADMIN_TASK.ACTION.eq("missing_resource"), ADMIN_TASK.STATUS.eq("open")).fetch()
		open.filter { it.value2() !in missing }.forEach {
			dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, "no longer needed")
				.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(it.value1())).execute()
		}
		val label = key.replaceFirstChar { it.uppercase() }
		for (resource in missing - open.mapNotNull { it.value2() }.toSet()) {
			val name = dsl.select(RESOURCE.NAME).from(RESOURCE).where(RESOURCE.ID.eq(resource)).fetchOne()?.value1() ?: continue
			dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, key).set(ADMIN_TASK.ACTION, "missing_resource").set(ADMIN_TASK.RESOURCE_ID, resource)
				.set(ADMIN_TASK.DESCRIPTION, "$name is no longer in $label (deleted there?). Nobody is added to it until it's back: restore it in $label, or detach it from its projects.")
				.execute()
		}
	}

	/** Closes a resource's open `resource_exists` task once national has decided about it (link, rename, unmanaged). */
	fun resourceDecided(resource: UUID, viewer: Viewer) {
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "done").set(ADMIN_TASK.DONE_BY, viewer.personId).set(ADMIN_TASK.DONE_AT, OffsetDateTime.now())
			.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now())
			.where(ADMIN_TASK.ACTION.eq("resource_exists"), ADMIN_TASK.RESOURCE_ID.eq(resource), ADMIN_TASK.STATUS.eq("open")).execute()
	}

	/** Tasks closed by a decision made elsewhere can't be marked done or cancelled on the queue: the resource would stay stuck. */
	private fun decidedElsewhere(action: String?) {
		if (action == "resource_exists") {
			throw ResponseStatusException(HttpStatus.CONFLICT, "Decide about the resource instead: link it, rename the portal's, or leave it unmanaged")
		}
	}

	private fun openTask(id: UUID, lock: Boolean = false) =
		dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ID.eq(id)).let { if (lock) it.forUpdate() else it }.fetchOne()?.also {
			if (it.status != "open") throw ResponseStatusException(HttpStatus.CONFLICT, "This task is already ${it.status}")
		} ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such task")

	private fun audit(viewer: Viewer, action: String, id: UUID, description: String?) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "admin_task").set(AUDIT_EVENT.TARGET_ID, id)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(mapOf("task" to description))))
			.execute()
	}
}
