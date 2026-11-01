package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.national.AdminQueue
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Membership
import org.hack4impact.portal.resolver.Plan
import org.hack4impact.portal.resolver.Planner
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolution
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** What one tool's run found. Counts are for the whole tool; details are in sync_change. */
data class ToolRunReport(
	val tool: Tool,
	val runId: UUID?,
	val status: String,
	val adds: Int = 0,
	val changes: Int = 0,
	val removals: Int = 0,
	val drift: Int = 0,
	val unmatchedAccounts: Int = 0,
	val missingResources: Int = 0,
	/** Resources that exist only in the portal so far, which a real run would create first. */
	val creates: Int = 0,
	/** Why a real (writing) run would stop for national's confirmation: the blast-radius limit. */
	val wouldPause: String? = null,
	val error: String? = null,
	/** Apply runs: changes made (or invited) and access removed. */
	val applied: Int = 0,
	val removed: Int = 0,
	/** Members confirmed after accepting an invitation (Vaultwarden). */
	val confirmed: Int = 0,
	/** Resources of closed projects archived (or, in a dry run, to archive). */
	val archives: Int = 0,
)

/**
 * The sync engine: resolves what access should exist, reads what each tool actually has, plans the difference, and
 * records the plan (a dry run) or hands it to [SyncApplier] (a tool out of dry run with a write adapter). Each tool runs
 * and is recorded on its own, so one failing tool doesn't stop the others. Ported from the full portal for stage 2
 * (wiki: staged-rollout-stage-2) without Notion pages or GitHub repositories, and with look-before-create: a resource
 * whose name the tool already has goes to national instead of being created ([ExistingResources]).
 */
@Component
class SyncEngine(
	private val dsl: DSLContext,
	private val worlds: WorldLoader,
	private val adapters: List<ReadAdapter>,
	private val writers: List<WriteAdapter>,
	private val applier: SyncApplier,
	private val queue: AdminQueue,
	private val existing: ExistingResources,
	private val transactions: TransactionTemplate,
	private val locks: RunLocks,
	private val tools: org.hack4impact.portal.tools.ToolRegistry,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	private data class ManagedResource(val id: UUID, val externalId: String)

	/**
	 * What a tool actually has. [memberAccounts] is the account behind each person's membership (to revoke it), and
	 * [people] each person's account in the tool, matched like the adoption scan (ID, then login, then email).
	 */
	internal data class Actual(
		val memberships: List<Membership>,
		val unmatched: List<Pair<UUID, String>>,
		val missing: List<UUID>,
		val toCreate: List<UUID>,
		val memberAccounts: Map<Pair<UUID, UUID>, String> = emptyMap(),
		val people: Map<UUID, ToolAccount> = emptyMap(),
		/** Resources to archive: every project using them is closed, and no chapter uses them (Slack only). */
		val toArchive: List<UUID> = emptyList(),
		/** Access people have through their role in the tool (Vaultwarden owners and admins): never added, changed or removed. */
		val implicit: Set<Pair<UUID, UUID>> = emptySet(),
		/** Resources this run found already existing in the tool under the name the portal was about to create. */
		val existing: List<UUID> = emptyList(),
		/** Resources the portal doesn't touch: unmanaged, or waiting for national to decide about a name that exists. */
		val untouched: Set<UUID> = emptySet(),
	)


	/**
	 * A sync of every tool: tools whose setting is out of dry run (`tool_setting.dry_run = false`) and that have a
	 * write adapter are changed; the rest are planned only. Every tool starts in dry run.
	 */
	fun run(trigger: String, tools: Set<Tool>? = null): List<ToolRunReport> = sync(trigger, tools, forceDryRun = false)

	/** Plans every tool without changing any, whatever their settings (previews, the sandbox check). */
	fun dryRun(trigger: String, tools: Set<Tool>? = null): List<ToolRunReport> = sync(trigger, tools, forceDryRun = true)

	/** One sync at a time, here and in any other process on this database (review H2). */
	private fun sync(trigger: String, tools: Set<Tool>?, forceDryRun: Boolean): List<ToolRunReport> =
		locks.exclusive(RunLocks.SYNC) { syncNow(trigger, tools, forceDryRun) }

	private fun syncNow(trigger: String, tools: Set<Tool>?, forceDryRun: Boolean): List<ToolRunReport> {
		val loaded = worlds.load()
		loaded.warnings.forEach { log.warn("Sync: {}", it) }
		val resolution = Resolver.resolve(loaded.world)
		val resourceTool = loaded.world.resources.associate { it.id to it.tool }
		val reports = adapters
			.filter { tools == null || it.tool in tools }
			.sortedBy { it.tool }
			.map { adapter -> runTool(adapter, trigger, resolution, resourceTool, forceDryRun) }
		return reports
	}

	private fun runTool(adapter: ReadAdapter, trigger: String, resolution: Resolution, resourceTool: Map<UUID, Tool>, forceDryRun: Boolean): ToolRunReport {
		val tool = adapter.tool
		val setting = dsl.selectFrom(TOOL_SETTING).where(TOOL_SETTING.TOOL.eq(tool.name.lowercase())).fetchOne()
		if (setting?.enabled == false) {
			val runId = recordRun(tool, trigger, "paused", null, "paused by the kill switch${setting.pauseReason?.let { ": $it" } ?: ""}")
			return ToolRunReport(tool, runId, "paused", error = "paused by the kill switch")
		}
		val actual = try {
			readActual(adapter)
		} catch (e: AdapterException) {
			val runId = recordRun(tool, trigger, "failed", null, e.message)
			return ToolRunReport(tool, runId, "failed", error = e.message)
		}

		val ofTool = { id: UUID -> resourceTool[id] == tool }
		// Grants already met by someone's role in the tool (implicit access) are left out of the plan, and so are grants
		// to resources the portal doesn't touch (unmanaged, or waiting for national's decision about an existing name).
		val desired = Resolution(
			resolution.grants.filter { ofTool(it.resourceId) && (it.personId to it.resourceId) !in actual.implicit && it.resourceId !in actual.untouched },
			resolution.withheld.filter { ofTool(it.resourceId) },
		)
		val records = dsl.select(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.REASONS, SYNC_RECORD.APPLIED_ACCESS_LEVEL).from(SYNC_RECORD)
			.where(SYNC_RECORD.APPLIED_AT.isNotNull)
			.fetch()
			.filter { ofTool(it.value2()!!) }
		val previous = records.associate { (it.value1()!! to it.value2()!!) to reasonsFrom(it.value3()) }
		// The level the portal last applied: access is only lowered from a level the portal gave (decision 144).
		val applied = records.mapNotNull { r -> r.value4()?.let { level -> (r.value1()!! to r.value2()!!) to Access.valueOf(level.uppercase()) } }.toMap()
		val plan = Planner.plan(desired, actual.memberships, previous, applied)
		val wouldPause = blastRadius(plan, actual.memberships, setting?.maxRemovals ?: 25, setting?.maxRemovalRatio?.toDouble() ?: 0.2)

		val writer = writers.firstOrNull { it.tool == tool }
		if (!forceDryRun && setting?.dryRun == false && writer != null) {
			// National confirmed these very removals recently (admin queue): the run may make them.
			val taken = plan.removals.map { it.personId to it.resourceId } + plan.lowerings.map { it.grant.personId to it.grant.resourceId }
			val hold = wouldPause?.takeUnless { queue.removalsConfirmed(tool, taken) }
			return applier.apply(tool, trigger, writer, plan, actual, desired, hold)
		}
		val runId = transactions.execute { record(tool, trigger, plan, actual, desired, wouldPause) }
		return ToolRunReport(
			tool, runId, if (wouldPause == null) "completed" else "paused",
			plan.adds.size, plan.changes.size, plan.removals.size, plan.drift.size, actual.unmatched.size, actual.missing.size, actual.toCreate.size, wouldPause,
			archives = actual.toArchive.size,
		)
	}

	/** Reads members of every resource the portal manages in this tool, mapped to people through their tool accounts. */
	private fun readActual(adapter: ReadAdapter): Actual {
		// Always reach the tool, even with nothing to compare yet, so bad credentials or an outage show as a failed run.
		val accounts = adapter.accounts()
		val accountsById = accounts.filter { it.externalId != null }.associateBy { it.externalId!! }
		val matcher = AccountMatcher.load(dsl, adapter.tool)
		val matched = accounts.mapNotNull { a -> a.externalId?.let { id -> matcher.match(id, a.login, a.email)?.let { it.personId to a } } }.groupBy({ it.first }, { it.second })
		// Someone with several accounts in the tool (an old one, and the H4I one a registration created) gets access on
		// their H4I address; otherwise the first the tool lists, so the choice doesn't change from run to run.
		val orgEmails = dsl.select(PERSON.ID, PERSON.ORG_EMAIL).from(PERSON).where(PERSON.ID.`in`(matched.filterValues { it.size > 1 }.keys))
			.fetch().associate { it.value1()!! to it.value2() }
		val people = matched.mapValues { (person, options) -> options.firstOrNull { o -> orgEmails[person]?.let { o.email.equals(it, ignoreCase = true) } == true } ?: options.first() }
		val tool = adapter.tool.name.lowercase()
		val managed = dsl.select(RESOURCE.ID, RESOURCE.EXTERNAL_ID).from(RESOURCE)
			.where(RESOURCE.TOOL.eq(tool), RESOURCE.EXTERNAL_ID.isNotNull, RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.ne("unmanaged"))
			.fetch { ManagedResource(it.value1()!!, it.value2()!!) }
		val memberships = mutableListOf<Membership>()
		val memberAccounts = mutableMapOf<Pair<UUID, UUID>, String>()
		val implicit = mutableSetOf<Pair<UUID, UUID>>()
		val unmatched = mutableListOf<Pair<UUID, String>>()
		val missing = mutableListOf<UUID>()
		for (resource in managed) {
			val members = try {
				adapter.members(resource.externalId)
			} catch (e: NotFound) {
				missing += resource.id
				continue
			}
			for (member in members) {
				val account = accountsById[member.accountId]
				val person = matcher.match(member.accountId, member.login ?: account?.login, account?.email)?.personId
				if (member.implicit) {
					person?.let { implicit += it to resource.id } // not unmatched or drift either: it isn't the portal's to manage
				} else if (person == null) {
					unmatched += resource.id to member.accountId
				} else {
					memberships += Membership(person, resource.id, member.access)
					memberAccounts[person to resource.id] = member.accountId
				}
			}
		}
		// Portal-made resources not created in the tool yet, if a live project or the chapter uses them.
		val wanted = dsl.select(RESOURCE.ID, RESOURCE.NAME).from(RESOURCE)
			.where(
				RESOURCE.TOOL.eq(tool), RESOURCE.EXTERNAL_ID.isNull, RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.eq("portal"), RESOURCE.EXISTS_IN_TOOL_AT.isNull,
				DSL.exists(DSL.selectOne().from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
					.where(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID), PROJECT.STATUS.`in`("active", "paused")))
					.or(DSL.exists(DSL.selectOne().from(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID)))),
			)
			.fetch { it.value1()!! to it.value2()!! }
		// Look before creating: a name the tool already has is recorded once for national, not created or retried. Archived
		// ones count: an archived Slack channel keeps its name.
		val listing = if (wanted.isEmpty()) emptyList() else adapter.resources()
		val existingNow = wanted.mapNotNull { (id, name) -> ExistingResources.find(adapter.tool, name, listing)?.let { id to it } }
		existingNow.forEach { (id, found) -> existing.record(adapter.tool, id, wanted.first { it.first == id }.second, found.externalId) }
		val toCreate = wanted.map { it.first } - existingNow.map { it.first }.toSet()
		val untouched = dsl.select(RESOURCE.ID).from(RESOURCE)
			.where(RESOURCE.TOOL.eq(tool), RESOURCE.MANAGED.eq("unmanaged").or(RESOURCE.EXISTS_IN_TOOL_AT.isNotNull.and(RESOURCE.EXTERNAL_ID.isNull)))
			.fetch(RESOURCE.ID).filterNotNull().toSet()
		// PRD: closing a project archives its channels (registry: archiveOnClose); teams, groups and collections are only
		// emptied. Only channels the portal created: a channel national linked was there before and stays (decision 170).
		val toArchive = if (!tools[adapter.tool].archiveOnClose) emptyList() else dsl.select(RESOURCE.ID).from(RESOURCE)
			.where(
				RESOURCE.TOOL.eq(tool), RESOURCE.EXTERNAL_ID.isNotNull, RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.eq("portal"),
				DSL.exists(DSL.selectOne().from(PROJECT_RESOURCE).where(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))),
				DSL.notExists(DSL.selectOne().from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
					.where(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID), PROJECT.STATUS.ne("closed"))),
				DSL.notExists(DSL.selectOne().from(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))),
			)
			.fetch(RESOURCE.ID).filterNotNull()
		return Actual(memberships, unmatched, missing, toCreate, memberAccounts, people, toArchive, implicit, existingNow.map { it.first }, untouched)
	}

	/** Why a writing run would pause for national: too many removals overall, or too large a share of one resource. */
	private fun blastRadius(plan: Plan, actual: List<Membership>, maxRemovals: Int, maxRatio: Double): String? {
		// Lowering someone's access counts like taking it away (decision 144).
		val taken = plan.removals.map { it.resourceId } + plan.lowerings.map { it.grant.resourceId }
		if (taken.size > maxRemovals) return "${taken.size} removals is over the limit of $maxRemovals"
		val membersPerResource = actual.groupingBy { it.resourceId }.eachCount()
		return taken.groupingBy { it }.eachCount().entries.firstNotNullOfOrNull { (resource, removed) ->
			val total = membersPerResource[resource] ?: return@firstNotNullOfOrNull null
			if (total >= 5 && removed.toDouble() / total > maxRatio) "$removed of $total members of one resource would be removed (over ${(maxRatio * 100).toInt()}%)" else null
		}
	}

	private fun record(tool: Tool, trigger: String, plan: Plan, actual: Actual, desired: Resolution, wouldPause: String?): UUID {
		val runId = recordRun(tool, trigger, if (wouldPause == null) "completed" else "paused", plan, wouldPause)
		fun change(kind: String, person: UUID?, resource: UUID?, account: String? = null, from: Access? = null, to: Access? = null, reasons: List<Reason> = emptyList()) =
			dsl.insertInto(SYNC_CHANGE)
				.set(SYNC_CHANGE.RUN_ID, runId).set(SYNC_CHANGE.KIND, kind).set(SYNC_CHANGE.PERSON_ID, person).set(SYNC_CHANGE.RESOURCE_ID, resource)
				.set(SYNC_CHANGE.ACCOUNT_ID, account).set(SYNC_CHANGE.FROM_ACCESS, from?.name?.lowercase()).set(SYNC_CHANGE.TO_ACCESS, to?.name?.lowercase())
				.set(SYNC_CHANGE.REASONS, reasonsJson(reasons))
		val queries = buildList {
			plan.adds.forEach { add(change("add", it.personId, it.resourceId, to = it.access, reasons = it.reasons)) }
			plan.changes.forEach { add(change("change", it.grant.personId, it.grant.resourceId, from = it.from, to = it.grant.access, reasons = it.grant.reasons)) }
			plan.removals.forEach { add(change("remove", it.personId, it.resourceId, from = it.access, reasons = it.previousReasons)) }
			plan.drift.forEach { add(change("drift", it.personId, it.resourceId, from = it.access)) }
			actual.unmatched.forEach { (resource, account) -> add(change("unmatched_account", null, resource, account = account)) }
			actual.missing.forEach { add(change("missing_resource", null, it)) }
			actual.toCreate.forEach { add(change("create_resource", null, it)) }
			actual.toArchive.forEach { add(change("archive_resource", null, it)) }
			actual.existing.forEach { add(change("resource_exists", null, it)) }
		}
		dsl.batch(queries).execute()

		// One sync record per desired grant: what should be, what is, and why. Dry runs never set applied_at.
		val have = actual.memberships.associateBy { it.personId to it.resourceId }
		val now = OffsetDateTime.now()
		desired.grants.forEach { grant ->
			val current = have[grant.personId to grant.resourceId]
			val inSync = current?.access == grant.access
			dsl.insertInto(SYNC_RECORD)
				.set(SYNC_RECORD.PERSON_ID, grant.personId).set(SYNC_RECORD.RESOURCE_ID, grant.resourceId)
				.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
				.set(SYNC_RECORD.REASONS, reasonsJson(grant.reasons))
				.set(SYNC_RECORD.ACTUAL, if (current == null) "absent" else "present")
				.set(SYNC_RECORD.STATUS, if (inSync) "in_sync" else "pending")
				.set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
				.onConflict(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID).doUpdate()
				.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
				.set(SYNC_RECORD.REASONS, reasonsJson(grant.reasons))
				.set(SYNC_RECORD.ACTUAL, if (current == null) "absent" else "present")
				// A dry run doesn't undo what an applying run learned: a dead letter, an invite waiting, a manual task (review L6).
				.set(SYNC_RECORD.STATUS, if (inSync) DSL.inline("in_sync") else DSL.case_()
					.`when`(SYNC_RECORD.STATUS.`in`("dead_letter", "waiting_on_member", "manual_task", "failed"), SYNC_RECORD.STATUS).else_(DSL.inline("pending")))
				.set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
				.execute()
		}
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, "sync.dry_run")
			.set(AUDIT_EVENT.TARGET_TYPE, "sync_run").set(AUDIT_EVENT.TARGET_ID, runId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"tool":"${tool.name.lowercase()}","adds":${plan.adds.size},"changes":${plan.changes.size},"removals":${plan.removals.size},"drift":${plan.drift.size}}"""))
			.execute()
		return runId
	}

	private fun recordRun(tool: Tool, trigger: String, status: String, plan: Plan?, error: String?): UUID =
		dsl.insertInto(SYNC_RUN)
			.set(SYNC_RUN.TOOL, tool.name.lowercase()).set(SYNC_RUN.MODE, "dry_run").set(SYNC_RUN.TRIGGER, trigger).set(SYNC_RUN.STATUS, status)
			.set(SYNC_RUN.FINISHED_AT, OffsetDateTime.now())
			.set(SYNC_RUN.PLANNED_ADDS, (plan?.adds?.size ?: 0) + (plan?.changes?.size ?: 0))
			.set(SYNC_RUN.PLANNED_REMOVES, plan?.removals?.size ?: 0)
			.set(SYNC_RUN.ERROR, error)
			.returningResult(SYNC_RUN.ID).fetchSingle().value1()!!

	internal fun reasonsJson(reasons: List<Reason>): JSONB = JSONB.valueOf(json.writeValueAsString(reasons.map(::reasonMap)))

	private fun reasonMap(reason: Reason): Map<String, String> = when (reason) {
		is Reason.ChapterMember -> mapOf("type" to "chapter_member", "chapterId" to reason.chapterId.toString())
		is Reason.ChapterLead -> mapOf("type" to "chapter_lead", "chapterId" to reason.chapterId.toString())
		is Reason.ProjectMember -> mapOf("type" to "project_member", "projectId" to reason.projectId.toString())
		is Reason.FromRule -> mapOf("type" to "rule", "ruleId" to reason.ruleId.toString())
		is Reason.NationalDefault -> mapOf("type" to "national_default", "ruleId" to reason.ruleId.toString())
	}

	private fun reasonsFrom(value: JSONB?): List<Reason> {
		if (value == null) return emptyList()
		return buildList {
			json.readTree(value.data()).forEach { r ->
				val id = { field: String -> UUID.fromString(r.path(field).asString()) }
				when (r.path("type").asString()) {
					"chapter_member" -> add(Reason.ChapterMember(id("chapterId")))
					"chapter_lead" -> add(Reason.ChapterLead(id("chapterId")))
					"project_member" -> add(Reason.ProjectMember(id("projectId")))
					"rule" -> add(Reason.FromRule(id("ruleId")))
					"national_default" -> add(Reason.NationalDefault(id("ruleId")))
				}
			}
		}
	}
}
