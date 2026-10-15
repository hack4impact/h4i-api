package org.hack4impact.portal.memberdb

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.MEMBER_DB_DRIFT
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.hack4impact.portal.db.tables.references.PERSON
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** The member database integration as national sees it (wiki: staged-rollout). */
data class MemberDbStatus(
	/** MEMBER_DB_URL is set. */
	val configured: Boolean,
	/** The write switch (PORTAL_MEMBERDB_WRITE): without it nothing is written, whatever is set here. */
	val writeSwitch: Boolean,
	/** Pulls on a schedule (PORTAL_MEMBERDB_PULL). */
	val scheduledPull: Boolean,
	val dryRun: Boolean,
	val paused: Boolean,
	val pauseReason: String?,
	val lastPullAt: OffsetDateTime?,
	/** completed, unchanged or failed. */
	val lastPullStatus: String?,
	val lastPullError: String?,
	val waiting: List<MemberDbWriteRow>,
	val failed: List<MemberDbWriteRow>,
	val drift: List<MemberDbDriftRow>,
)

data class MemberDbWriteRow(val personId: UUID, val name: String, val requestedAt: OffsetDateTime, val attempts: Int, val nextTryAt: OffsetDateTime?, val lastError: String?)

/** [field] as `table.column` (`volunteers.email`); [since] when it was first seen. */
data class MemberDbDriftRow(val personId: UUID, val name: String, val field: String, val portalValue: String?, val memberDbValue: String?, val since: OffsetDateTime)

/** Leaving dry run needs the write switch; pausing needs a reason. */
data class MemberDbChange(val dryRun: Boolean, val paused: Boolean, val reason: String? = null)

/** What would be written for one person: [fields] as `table.column`, [notes] what's left out and why. */
data class MemberDbPreview(val newVolunteer: Boolean, val fields: Map<String, String?>, val notes: List<String>)

@RestController
@RequestMapping("/api/national/member-db")
class MemberDbController(
	private val viewers: Viewers,
	private val dsl: DSLContext,
	private val memberDb: MemberDb,
	private val properties: MemberDbProperties,
	private val writer: MemberDbWriter,
	private val pull: MemberDbPull,
	private val writes: MemberDbWrites,
) {
	private val json = JsonMapper.builder().build()

	private fun national(user: OidcUser): Viewer = viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping
	fun status(@AuthenticationPrincipal user: OidcUser): MemberDbStatus {
		national(user)
		return status()
	}

	@PutMapping
	fun change(@AuthenticationPrincipal user: OidcUser, @RequestBody change: MemberDbChange): MemberDbStatus {
		val viewer = national(user)
		if (!change.dryRun && !properties.write) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "The write switch (PORTAL_MEMBERDB_WRITE) isn't set on this server, so it stays in dry run")
		}
		val reason = change.reason?.trim()?.ifEmpty { null }
		if (change.paused && reason == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why it's paused")
		dsl.insertInto(MEMBER_DB_STATE).set(MEMBER_DB_STATE.ID, true).set(MEMBER_DB_STATE.DRY_RUN, change.dryRun).set(MEMBER_DB_STATE.PAUSED, change.paused)
			.set(MEMBER_DB_STATE.PAUSE_REASON, if (change.paused) reason else null)
			.onConflict(MEMBER_DB_STATE.ID).doUpdate()
			.set(MEMBER_DB_STATE.DRY_RUN, change.dryRun).set(MEMBER_DB_STATE.PAUSED, change.paused).set(MEMBER_DB_STATE.PAUSE_REASON, if (change.paused) reason else null)
			.execute()
		audit(viewer, "member_db.settings", mapOf("dryRun" to change.dryRun, "paused" to change.paused, "reason" to reason))
		return status()
	}

	/** Pulls now, even if nothing seems to have changed. */
	@PostMapping("/pull")
	fun pullNow(@AuthenticationPrincipal user: OidcUser): PullResult {
		val viewer = national(user)
		if (!memberDb.configured) throw ResponseStatusException(HttpStatus.CONFLICT, "The member database isn't configured on this server (MEMBER_DB_URL)")
		audit(viewer, "member_db.pull", emptyMap())
		return pull.pull(force = true)
	}

	/** Puts a failed write back in line, with a fresh count. */
	@PostMapping("/writes/{personId}/retry")
	fun retry(@AuthenticationPrincipal user: OidcUser, @PathVariable personId: UUID): MemberDbStatus {
		val viewer = national(user)
		if (!dsl.fetchExists(MEMBER_DB_WRITE, MEMBER_DB_WRITE.PERSON_ID.eq(personId))) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing to write for them")
		writes.request(personId)
		audit(viewer, "member_db.retry", mapOf("person" to personId.toString()))
		return status()
	}

	@GetMapping("/writes/{personId}/preview")
	fun preview(@AuthenticationPrincipal user: OidcUser, @PathVariable personId: UUID): MemberDbPreview {
		national(user)
		if (!memberDb.configured) throw ResponseStatusException(HttpStatus.CONFLICT, "The member database isn't configured on this server (MEMBER_DB_URL)")
		val plan = try {
			writer.plan(personId)
		} catch (e: MemberDbConflict) {
			throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
		}
		return MemberDbPreview(plan.newVolunteer, plan.fields, plan.notes)
	}

	private fun status(): MemberDbStatus {
		val state = dsl.selectFrom(MEMBER_DB_STATE).fetchOne()
		val name = { first: String?, last: String?, preferred: String? -> "${preferred?.takeIf { it.isNotBlank() } ?: first} $last" }
		val rows = dsl.select(MEMBER_DB_WRITE.PERSON_ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, MEMBER_DB_WRITE.STATE,
			MEMBER_DB_WRITE.REQUESTED_AT, MEMBER_DB_WRITE.ATTEMPTS, MEMBER_DB_WRITE.NEXT_TRY_AT, MEMBER_DB_WRITE.LAST_ERROR)
			.from(MEMBER_DB_WRITE).join(PERSON).on(PERSON.ID.eq(MEMBER_DB_WRITE.PERSON_ID))
			.where(MEMBER_DB_WRITE.STATE.`in`("pending", "failed")).orderBy(MEMBER_DB_WRITE.REQUESTED_AT).limit(500).fetch()
		val toRow = { r: org.jooq.Record9<UUID?, String?, String?, String?, String?, OffsetDateTime?, Int?, OffsetDateTime?, String?> ->
			MemberDbWriteRow(r.value1()!!, name(r.value2(), r.value3(), r.value4()), r.value6()!!, r.value7()!!, r.value8(), r.value9())
		}
		val drift = dsl.select(MEMBER_DB_DRIFT.PERSON_ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, MEMBER_DB_DRIFT.FIELD,
			MEMBER_DB_DRIFT.PORTAL_VALUE, MEMBER_DB_DRIFT.MEMBER_DB_VALUE, MEMBER_DB_DRIFT.FIRST_SEEN_AT)
			.from(MEMBER_DB_DRIFT).join(PERSON).on(PERSON.ID.eq(MEMBER_DB_DRIFT.PERSON_ID)).orderBy(MEMBER_DB_DRIFT.FIRST_SEEN_AT).limit(500)
			.fetch { MemberDbDriftRow(it.value1()!!, name(it.value2(), it.value3(), it.value4()), it.value5()!!, it.value6(), it.value7(), it.value8()!!) }
		return MemberDbStatus(
			memberDb.configured, properties.write, properties.pull,
			state?.dryRun ?: true, state?.paused ?: false, state?.pauseReason,
			state?.lastPullAt, state?.lastPullStatus, state?.lastPullError,
			rows.filter { it.value5() == "pending" }.map(toRow), rows.filter { it.value5() == "failed" }.map(toRow), drift,
		)
	}

	private fun audit(viewer: Viewer, action: String, after: Map<String, Any?>) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "member_db").set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(after)))
			.execute()
	}
}
