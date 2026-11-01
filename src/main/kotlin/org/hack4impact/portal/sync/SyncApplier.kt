package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.national.AdminQueue
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Grant
import org.hack4impact.portal.resolver.Plan
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolution
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Applies a tool's plan (build plan step 9): creates portal-made resources, gives and changes access, then takes
 * away access the portal granted and no longer wants. Each change is recorded with its outcome.
 *
 *  - A failed change is retried by later runs with backoff (1 min, 5 min, 30 min, 2 h); after [MAX_ATTEMPTS]
 *    it's a dead letter: no more automatic retries until a person resets it. Bad credentials stop the tool's run at once.
 *  - Over the blast-radius limit, removals are held and national gets a task to confirm them; adds still happen.
 *  - Only access the portal applied is ever removed (decisions 52, 66).
 *  - A create the tool refuses because the name is taken goes to national at once, with no retries (stage 2).
 */
@Component
class SyncApplier(private val dsl: DSLContext, @Lazy private val engine: SyncEngine, private val queue: AdminQueue, private val existing: ExistingResources) {
	private val log = LoggerFactory.getLogger(javaClass)

	companion object {
		const val MAX_ATTEMPTS = 5
		/** A pending email invitation is sent again after this long (GitHub's expire after seven days). */
		private val REINVITE_AFTER: Duration = Duration.ofDays(7)
		private val BACKOFF = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2))
		fun backoff(attempts: Int): Duration = BACKOFF[(attempts - 1).coerceIn(0, BACKOFF.lastIndex)]
	}

	private class Stop(val error: String) : RuntimeException(error)

	private data class RecordState(val attempts: Int, val nextRetryAt: OffsetDateTime?, val status: String, val lastAttemptAt: OffsetDateTime? = null)

	internal fun apply(
		tool: Tool, trigger: String, writer: WriteAdapter, plan: Plan, actual: SyncEngine.Actual, desired: Resolution, wouldPause: String?,
	): ToolRunReport {
		val now = OffsetDateTime.now()
		val runId = dsl.insertInto(SYNC_RUN)
			.set(SYNC_RUN.TOOL, tool.name.lowercase()).set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, trigger).set(SYNC_RUN.STATUS, "running")
			.set(SYNC_RUN.PLANNED_ADDS, plan.adds.size + plan.changes.size).set(SYNC_RUN.PLANNED_REMOVES, plan.removals.size)
			.returningResult(SYNC_RUN.ID).fetchSingle().value1()!!
		val external = dsl.select(RESOURCE.ID, RESOURCE.EXTERNAL_ID, RESOURCE.NAME).from(RESOURCE).where(RESOURCE.TOOL.eq(tool.name.lowercase())).fetch()
			.associate { it.value1()!! to (it.value2() to it.value3()!!) }.toMutableMap()
		val records = dsl.select(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.ATTEMPTS, SYNC_RECORD.NEXT_RETRY_AT, SYNC_RECORD.STATUS, SYNC_RECORD.LAST_ATTEMPT_AT).from(SYNC_RECORD).fetch()
			.associate { (it.value1()!! to it.value2()!!) to RecordState(it.value3()!!, it.value4(), it.value5()!!, it.value6()) }
		var applied = 0
		var removed = 0
		var stopped: String? = null
		// Queue tasks the plan no longer wants cancel themselves (PRD: admin queue).
		queue.reconcile(tool, desired.grants.map { it.personId to it.resourceId }.toSet())

		fun change(kind: String, person: UUID?, resource: UUID?, outcome: String?, error: String? = null, account: String? = null, from: Access? = null, to: Access? = null, reasons: List<Reason> = emptyList()) {
			dsl.insertInto(SYNC_CHANGE)
				.set(SYNC_CHANGE.RUN_ID, runId).set(SYNC_CHANGE.KIND, kind).set(SYNC_CHANGE.PERSON_ID, person).set(SYNC_CHANGE.RESOURCE_ID, resource)
				.set(SYNC_CHANGE.ACCOUNT_ID, account).set(SYNC_CHANGE.FROM_ACCESS, from?.name?.lowercase()).set(SYNC_CHANGE.TO_ACCESS, to?.name?.lowercase())
				.set(SYNC_CHANGE.REASONS, engine.reasonsJson(reasons)).set(SYNC_CHANGE.OUTCOME, outcome).set(SYNC_CHANGE.ERROR, error?.take(500))
				.execute()
		}

		/** Runs one change. Returns the outcome; a tool-wide problem (bad credentials) stops the rest of the run. */
		fun attempt(person: UUID?, resource: UUID, write: () -> String): Pair<String, String?> {
			if (stopped != null) return "skipped" to "run stopped: $stopped"
			val state = person?.let { records[it to resource] }
			if (state?.status == "dead_letter") return "skipped" to "dead letter: waiting for a person to reset it"
			if (state?.nextRetryAt != null && state.nextRetryAt.isAfter(now)) return "skipped" to "next retry ${state.nextRetryAt}"
			return try {
				write() to null
			} catch (e: AuthFailed) {
				stopped = e.message
				"failed" to e.message
			} catch (e: AdapterException) {
				val attempts = (state?.attempts ?: 0) + 1
				(if (attempts >= MAX_ATTEMPTS) "dead_letter" else "failed") to e.message
			}
		}

		// 1. Create portal-made resources, so their people can be added right after. A failure backs off like a member's
		// change, and the fifth goes to the admin queue rather than being tried on every run (second review L3).
		val creating = dsl.select(RESOURCE.ID, RESOURCE.CREATE_ATTEMPTS, RESOURCE.CREATE_RETRY_AT).from(RESOURCE).where(RESOURCE.ID.`in`(actual.toCreate)).fetch()
			.associate { it.value1()!! to (it.value2()!! to it.value3()) }
		for (resourceId in actual.toCreate) {
			val name = external[resourceId]?.second ?: continue
			val (tries, retryAt) = creating[resourceId] ?: (0 to null)
			val (outcome, error) = when {
				tries >= MAX_ATTEMPTS -> "skipped" to "dead letter: on the admin queue"
				retryAt != null && retryAt.isAfter(now) -> "skipped" to "next retry $retryAt"
				else -> attempt(null, resourceId) {
					try {
						val id = writer.create(name)
						dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, id).set(RESOURCE.CREATE_ATTEMPTS, 0).setNull(RESOURCE.CREATE_RETRY_AT).where(RESOURCE.ID.eq(resourceId)).execute()
						external[resourceId] = id to name
						"applied"
					} catch (e: AdapterException) {
						// The listing didn't show it (a private Slack channel the app isn't in), but the tool has the name.
						if (!ExistingResources.alreadyExists(e)) throw e
						existing.record(tool, resourceId, name, null)
						"exists"
					}
				}
			}
			if (outcome == "exists") {
				change("resource_exists", null, resourceId, null, "already exists in the tool: waiting for national")
				continue
			}
			if (outcome == "failed" || outcome == "dead_letter") {
				val attempts = tries + 1
				dsl.update(RESOURCE).set(RESOURCE.CREATE_ATTEMPTS, attempts).set(RESOURCE.CREATE_RETRY_AT, if (attempts >= MAX_ATTEMPTS) null else now.plus(backoff(attempts)))
					.where(RESOURCE.ID.eq(resourceId)).execute()
				if (attempts >= MAX_ATTEMPTS) queue.creationFailed(tool, resourceId, name, error)
			}
			change("create_resource", null, resourceId, outcome, error)
		}

		// 2. Give and change access.
		val grants = plan.adds.map { it to null as Access? } + plan.changes.map { it.grant to it.from }
		for ((grant, from) in grants) {
			val ext = external[grant.resourceId]?.first
			val ref = accountRef(tool, grant.personId, actual)
			val lowering = from != null && grant.access < from
			// An email invitation already sent and not accepted yet isn't sent again on every run (second review M2); once a
			// week it is, as GitHub's expire after seven days. Someone who has since joined the tool has an ID and goes ahead.
			val state = records[grant.personId to grant.resourceId]
			val invitePending = ref.id == null && state?.status == "waiting_on_member" && state.lastAttemptAt?.isAfter(now.minus(REINVITE_AFTER)) == true
			val (outcome, error) = if (ext == null) "skipped" to "the resource doesn't exist in the tool yet"
			else if (invitePending) "waiting" to "invitation sent ${state!!.lastAttemptAt}, not accepted yet"
			else if (lowering && wouldPause != null) "held" to wouldPause // lowering access waits with the removals (review H3)
			else attempt(grant.personId, grant.resourceId) {
				when (writer.grant(ext, ref, grant.access)) {
					GrantResult.DONE -> "applied"
					GrantResult.INVITED -> "invited"
					GrantResult.WAITING -> "waiting"
					GrantResult.QUEUED -> "queued"
				}
			}
			if (outcome == "applied" || outcome == "invited") applied++
			change(if (from == null) "add" else "change", grant.personId, grant.resourceId, outcome, error, account = ref.id ?: ref.login ?: ref.email,
				from = from, to = grant.access, reasons = grant.reasons)
			// A pending invitation that wasn't sent again leaves its record as it was, so the week counts from the real send.
			if (!invitePending) saveGrant(grant, outcome, error, runId, now, records[grant.personId to grant.resourceId])
		}

		// 3. Take away access the portal granted and no longer wants, unless the blast-radius limit holds it.
		for (removal in plan.removals) {
			val ext = external[removal.resourceId]?.first
			val account = actual.memberAccounts[removal.personId to removal.resourceId]
			val (outcome, error) = when {
				wouldPause != null -> "held" to wouldPause
				ext == null || account == null -> "skipped" to "no account to remove"
				else -> attempt(removal.personId, removal.resourceId) { if (writer.revoke(ext, account) == GrantResult.QUEUED) "queued" else "applied" }
			}
			if (outcome == "applied") removed++
			change("remove", removal.personId, removal.resourceId, outcome, error, account = account, from = removal.access, reasons = removal.previousReasons)
			saveRemoval(removal.personId, removal.resourceId, outcome, error, runId, now, records[removal.personId to removal.resourceId])
		}
		val held = plan.removals.size + plan.lowerings.size
		if (wouldPause != null && held > 0) {
			queue.confirmRemovals(tool, runId, "Confirm $held removals in ${tool.name.lowercase().replaceFirstChar { it.uppercase() }}: $wouldPause")
		}

		// 3b. Archive closed projects' channels, once their members are out (not while removals are held).
		var archived = 0
		for (resourceId in actual.toArchive) {
			val ext = external[resourceId]?.first ?: continue
			val (outcome, error) = if (wouldPause != null) "held" to wouldPause else attempt(null, resourceId) {
				writer.archive(ext)
				dsl.update(RESOURCE).set(RESOURCE.ARCHIVED_AT, OffsetDateTime.now()).where(RESOURCE.ID.eq(resourceId)).execute()
				"applied"
			}
			if (outcome == "applied") archived++
			change("archive_resource", null, resourceId, outcome, error)
		}

		// 4. Finish invitations people have accepted (Vaultwarden: confirm them, so they see the items).
		var confirmed = 0
		if (stopped == null) {
			val managed = external.values.mapNotNull { it.first }.toSet()
			confirmed = try { writer.confirmPending(managed) } catch (e: AdapterException) { log.warn("Sync {}: confirming members failed: {}", tool, e.message); 0 }
		}

		// 5. What the run saw but doesn't change.
		plan.drift.forEach { change("drift", it.personId, it.resourceId, null, from = it.access) }
		actual.unmatched.forEach { (resource, account) -> change("unmatched_account", null, resource, null, account = account) }
		actual.missing.forEach { change("missing_resource", null, it, null) }

		val status = when {
			stopped != null -> "failed"
			wouldPause != null -> "paused"
			else -> "completed"
		}
		dsl.update(SYNC_RUN).set(SYNC_RUN.STATUS, status).set(SYNC_RUN.FINISHED_AT, OffsetDateTime.now())
			.set(SYNC_RUN.APPLIED_ADDS, applied).set(SYNC_RUN.APPLIED_REMOVES, removed).set(SYNC_RUN.ERROR, stopped ?: wouldPause)
			.where(SYNC_RUN.ID.eq(runId)).execute()
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, "sync.apply")
			.set(AUDIT_EVENT.TARGET_TYPE, "sync_run").set(AUDIT_EVENT.TARGET_ID, runId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"tool":"${tool.name.lowercase()}","applied":$applied,"removed":$removed,"confirmed":$confirmed,"status":"$status"}"""))
			.execute()
		if (stopped != null) log.warn("Sync {} stopped: {}", tool, stopped)
		return ToolRunReport(
			tool, runId, status, plan.adds.size, plan.changes.size, plan.removals.size, plan.drift.size, actual.unmatched.size, actual.missing.size,
			actual.toCreate.size, wouldPause, stopped, applied = applied, removed = removed, confirmed = confirmed, archives = archived,
		)
	}

	/**
	 * The person's account in the tool if they have one there; else what the tool can invite: their verified account
	 * ID (GitHub) or stored login (the others), and their H4I address, falling back to their school address.
	 */
	private fun accountRef(tool: Tool, person: UUID, actual: SyncEngine.Actual): AccountRef {
		val who = dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.eq(person)).fetchOne()
		val name = who?.let { "${it.value3()?.takeIf { p -> p.isNotBlank() } ?: it.value1()} ${it.value2()}" }
		// Their H4I account first, even before the tool lists it: Google's listing lags a just-created account, and a
		// returning member's old account would otherwise get the access (step 10).
		val listed = actual.people[person]
		val orgEmail = dsl.select(PERSON.ORG_EMAIL).from(PERSON).where(PERSON.ID.eq(person)).fetchOne()?.value1()
		if (orgEmail != null && !orgEmail.equals(listed?.email, ignoreCase = true)) {
			dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT)
				.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase()), DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(orgEmail.lowercase()))
				.limit(1).fetchOne()?.let { return AccountRef(it.value1(), orgEmail, orgEmail, person, name) }
		}
		listed?.let { return AccountRef(it.externalId, it.login, it.email, person, name) }
		val email = dsl.select(PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL).from(PERSON).where(PERSON.ID.eq(person)).fetchOne()
			?.let { it.value1() ?: it.value2() }
		if (tool == Tool.GITHUB) {
			// Only a verified account ID (from a GitHub sign-in or the org's own listing): GitHub turns it into the account's
			// current login. A stored login may be unverified or since taken by someone else, so without an ID the
			// invitation goes to their address instead (review M2).
			val id = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT)
				.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.isNotNull)
				.orderBy(TOOL_ACCOUNT.VERIFIED_AT.desc().nullsLast(), TOOL_ACCOUNT.CREATED_AT).limit(1).fetchOne()?.value1()
			return AccountRef(id, null, email, person, name)
		}
		val login = dsl.select(TOOL_ACCOUNT.EXTERNAL_LOGIN).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase()), TOOL_ACCOUNT.EXTERNAL_LOGIN.isNotNull)
			.orderBy(TOOL_ACCOUNT.CREATED_AT).limit(1).fetchOne()?.value1()
		return AccountRef(null, login, email, person, name)
	}

	private fun saveGrant(grant: Grant, outcome: String, error: String?, runId: UUID, now: OffsetDateTime, state: RecordState?) {
		val failed = outcome == "failed" || outcome == "dead_letter"
		val attempts = if (failed) (state?.attempts ?: 0) + 1 else if (outcome == "skipped") state?.attempts ?: 0 else 0
		val status = when (outcome) {
			"applied" -> "in_sync"
			"invited", "waiting" -> "waiting_on_member"
			"queued" -> "manual_task"
			"failed" -> "failed"
			"dead_letter" -> "dead_letter"
			else -> state?.status ?: "pending" // skipped: unchanged
		}
		val appliedAt = if (outcome == "applied" || outcome == "invited") now else null
		val nextRetry = if (outcome == "failed") now.plus(backoff(attempts)) else if (outcome == "skipped") state?.nextRetryAt else null
		dsl.insertInto(SYNC_RECORD)
			.set(SYNC_RECORD.PERSON_ID, grant.personId).set(SYNC_RECORD.RESOURCE_ID, grant.resourceId)
			.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
			.set(SYNC_RECORD.REASONS, engine.reasonsJson(grant.reasons)).set(SYNC_RECORD.ACTUAL, if (outcome == "applied") "present" else "absent")
			.set(SYNC_RECORD.STATUS, status).set(SYNC_RECORD.ATTEMPTS, attempts).set(SYNC_RECORD.NEXT_RETRY_AT, nextRetry)
			.set(SYNC_RECORD.LAST_ERROR, error).set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
			.set(SYNC_RECORD.APPLIED_AT, appliedAt).set(SYNC_RECORD.APPLIED_ACCESS_LEVEL, if (appliedAt != null) grant.access.name.lowercase() else null)
			.onConflict(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID).doUpdate()
			.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
			.set(SYNC_RECORD.REASONS, engine.reasonsJson(grant.reasons)).set(SYNC_RECORD.ACTUAL, if (outcome == "applied") "present" else "absent")
			.set(SYNC_RECORD.STATUS, status).set(SYNC_RECORD.ATTEMPTS, attempts).set(SYNC_RECORD.NEXT_RETRY_AT, nextRetry)
			.set(SYNC_RECORD.LAST_ERROR, error).set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
			// An earlier application stands while a later attempt fails or waits.
			.set(SYNC_RECORD.APPLIED_AT, if (appliedAt != null) org.jooq.impl.DSL.`val`(appliedAt) else SYNC_RECORD.APPLIED_AT)
			.set(SYNC_RECORD.APPLIED_ACCESS_LEVEL, if (appliedAt != null) org.jooq.impl.DSL.`val`(grant.access.name.lowercase()) else SYNC_RECORD.APPLIED_ACCESS_LEVEL)
			.execute()
	}

	private fun saveRemoval(person: UUID, resource: UUID, outcome: String, error: String?, runId: UUID, now: OffsetDateTime, state: RecordState?) {
		val failed = outcome == "failed" || outcome == "dead_letter"
		val update = dsl.update(SYNC_RECORD)
			.set(SYNC_RECORD.DESIRED, "absent").set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId).set(SYNC_RECORD.LAST_ERROR, error)
		val done = when {
			outcome == "applied" -> update.set(SYNC_RECORD.ACTUAL, "absent").set(SYNC_RECORD.STATUS, "in_sync").set(SYNC_RECORD.ATTEMPTS, 0)
				.setNull(SYNC_RECORD.NEXT_RETRY_AT).setNull(SYNC_RECORD.APPLIED_AT).setNull(SYNC_RECORD.APPLIED_ACCESS_LEVEL) // no longer the portal's to remove again
			failed -> {
				val attempts = (state?.attempts ?: 0) + 1
				update.set(SYNC_RECORD.STATUS, if (outcome == "dead_letter") "dead_letter" else "failed").set(SYNC_RECORD.ATTEMPTS, attempts)
					.set(SYNC_RECORD.NEXT_RETRY_AT, if (outcome == "failed") now.plus(backoff(attempts)) else null)
			}
			// Queued: still there until national marks the task done (AdminQueue.done).
			outcome == "queued" -> update.set(SYNC_RECORD.STATUS, "manual_task")
			else -> update.set(SYNC_RECORD.STATUS, "pending")
		}
		done.where(SYNC_RECORD.PERSON_ID.eq(person), SYNC_RECORD.RESOURCE_ID.eq(resource)).execute()
	}
}
