package org.hack4impact.portal.national

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The national admin queue (PRD): things the portal couldn't do itself, written as actions for a person to make, e.g.
 * "Create a Google account for Ada Lovelace" when a chapter has no org unit yet, or a GitHub account linked to two
 * people. National marks each done or cancels it.
 */
@Component
class AdminQueue(private val dsl: DSLContext) {
	fun tasks(): List<QueueTask> =
		dsl.select(ADMIN_TASK.ID, ADMIN_TASK.TOOL, ADMIN_TASK.ACTION, ADMIN_TASK.DESCRIPTION, ADMIN_TASK.CREATED_AT, ADMIN_TASK.STATUS,
			PERSON.FIRST_NAME, PERSON.LAST_NAME, ADMIN_TASK.DONE_AT)
			.from(ADMIN_TASK).leftJoin(PERSON).on(PERSON.ID.eq(ADMIN_TASK.DONE_BY))
			.where(ADMIN_TASK.STATUS.eq("open").or(ADMIN_TASK.UPDATED_AT.gt(OffsetDateTime.now().minusDays(7))))
			.orderBy(ADMIN_TASK.STATUS.desc(), ADMIN_TASK.TOOL, ADMIN_TASK.CREATED_AT)
			.limit(500)
			.fetch { QueueTask(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!, it.value7()?.let { f -> "$f ${it.value8()}" }, it.value9()) }

	@Transactional
	fun done(id: UUID, viewer: Viewer) {
		val task = openTask(id)
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "done").set(ADMIN_TASK.DONE_BY, viewer.personId).set(ADMIN_TASK.DONE_AT, OffsetDateTime.now())
			.where(ADMIN_TASK.ID.eq(id)).execute()
		audit(viewer, "queue.done", id, task.description)
	}

	@Transactional
	fun cancel(id: UUID, viewer: Viewer, reason: String?) {
		val task = openTask(id)
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, reason ?: "cancelled by national")
			.where(ADMIN_TASK.ID.eq(id)).execute()
		audit(viewer, "queue.cancel", id, task.description)
	}

	private fun openTask(id: UUID) =
		dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ID.eq(id)).forUpdate().fetchOne()?.also {
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
