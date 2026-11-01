package org.hack4impact.portal.projects

import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** One line in a project's activity feed. */
data class ActivityItem(
	val at: OffsetDateTime,
	/** change (someone edited the project), sync (the tools were changed), agreement (signed or declined). */
	val kind: String,
	val text: String,
	/** Who did it; null for the sync and for people's own signatures. */
	val actor: String?,
	/** More lines behind it (a big sync, by person), shown when it's expanded. */
	val details: List<String> = emptyList(),
)

/** A page of activity, newest first; [more] when older items exist (ask again with `before` = the last item's time). */
data class ActivityPage(val items: List<ActivityItem>, val more: Boolean)

/**
 * A project's activity: its edits (audit log, quick repeats by one person merged), what the sync did to its resources,
 * and agreements signed or declined. Newest first. From the full portal's.
 */
@Component
class ProjectActivity(private val dsl: DSLContext, private val tools: org.hack4impact.portal.tools.ToolRegistry) {
	private val json = JsonMapper.builder().build()

	companion object {
		/** Edits by the same person, of the same kind, this close together read as one ("Added Ada and Bo"). */
		private val MERGE_WITHIN: Duration = Duration.ofMinutes(10)
		/** A sync that touched more people than this is one line, expandable per person. */
		private const val GROUP_ABOVE = 3
	}

	fun page(projectId: UUID, before: OffsetDateTime?, limit: Int): ActivityPage {
		val n = limit.coerceIn(1, 100)
		val all = (edits(projectId, before, n * 4) + syncs(projectId, before, n * 4) + agreements(projectId, before, n * 2))
			.sortedByDescending { it.at }
		return ActivityPage(all.take(n), all.size > n)
	}

	private fun edits(projectId: UUID, before: OffsetDateTime?, limit: Int): List<ActivityItem> {
		val rows = dsl.select(AUDIT_EVENT.AT, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, AUDIT_EVENT.ACTION, AUDIT_EVENT.AFTER)
			.from(AUDIT_EVENT).leftJoin(PERSON).on(PERSON.ID.eq(AUDIT_EVENT.ACTOR_PERSON_ID))
			.where(AUDIT_EVENT.TARGET_TYPE.eq("project"), AUDIT_EVENT.TARGET_ID.eq(projectId), before?.let { AUDIT_EVENT.AT.lt(it) } ?: org.jooq.impl.DSL.noCondition())
			.orderBy(AUDIT_EVENT.AT.desc()).limit(limit)
			.fetch { r ->
				Edit(r.value1()!!, r.value2()?.let { ProjectQueries.name(r.value4(), it, r.value3()!!) }, r.value5()!!,
					r.value6()?.data()?.let { json.readTree(it).path("summary").takeIf { s -> s.isString }?.asString() } ?: r.value5()!!)
			}
		// Merge runs of the same action by the same person: "Added Ada Test", "Added Bo Test" → "Added Ada Test and Bo Test".
		val merged = mutableListOf<MutableList<Edit>>()
		for (e in rows) {
			val last = merged.lastOrNull()?.last()
			if (last != null && last.action == e.action && last.actor == e.actor && Duration.between(e.at, last.at) <= MERGE_WITHIN && verb(e) != null && verb(e) == verb(last)) {
				merged.last() += e
			} else merged += mutableListOf(e)
		}
		return merged.map { group ->
			val first = group.first()
			val text = if (group.size == 1) first.summary
			else "${verb(first)} ${list(group.reversed().map { it.summary.removePrefix(verb(first)!!).trim() })}"
			ActivityItem(first.at, "change", text, first.actor)
		}
	}

	private data class Edit(val at: OffsetDateTime, val actor: String?, val action: String, val summary: String)

	/** The leading verb of a mergeable summary ("Added", "Removed", "Attached", "Detached"). */
	private fun verb(e: Edit): String? = e.summary.substringBefore(' ').takeIf { it in setOf("Added", "Removed", "Attached", "Detached") }

	private fun list(items: List<String>) = when (items.size) {
		0 -> ""
		1 -> items[0]
		else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
	}

	/** What applied sync runs did to this project's resources: per person, or one expandable line for a big run. */
	private fun syncs(projectId: UUID, before: OffsetDateTime?, limit: Int): List<ActivityItem> {
		val rows = dsl.select(SYNC_RUN.ID, SYNC_RUN.STARTED_AT, SYNC_CHANGE.KIND, SYNC_CHANGE.OUTCOME, RESOURCE.TOOL, RESOURCE.NAME, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME)
			.from(SYNC_CHANGE).join(SYNC_RUN).on(SYNC_RUN.ID.eq(SYNC_CHANGE.RUN_ID))
			.join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(SYNC_CHANGE.RESOURCE_ID), PROJECT_RESOURCE.PROJECT_ID.eq(projectId))
			.join(RESOURCE).on(RESOURCE.ID.eq(SYNC_CHANGE.RESOURCE_ID)).leftJoin(PERSON).on(PERSON.ID.eq(SYNC_CHANGE.PERSON_ID))
			.where(
				SYNC_RUN.MODE.eq("apply"), SYNC_CHANGE.OUTCOME.`in`("applied", "invited"), SYNC_CHANGE.KIND.`in`("add", "change", "remove", "create_resource"),
				before?.let { SYNC_RUN.STARTED_AT.lt(it) } ?: org.jooq.impl.DSL.noCondition(),
			)
			.orderBy(SYNC_RUN.STARTED_AT.desc()).limit(limit * 10)
			.fetch()
		return rows.groupBy { it.value1()!! }.flatMap { (_, run) ->
			val at = run.first().value2()!!
			val created = run.filter { it.value3() == "create_resource" }.map { label(it.value5()!!, it.value6()!!) }
			val byPerson = run.filter { it.value7() != null }.groupBy { ProjectQueries.name(it.value9(), it.value7()!!, it.value8()!!) }
				.mapValues { (_, changes) ->
					// Always in the same order: added, invited, changed, removed; then the tools' order (registry).
					fun verb(kind: String?, outcome: String?) = when { kind == "remove" -> 3; kind == "change" -> 2; outcome == "invited" -> 1; else -> 0 }
					list(changes.sortedWith(compareBy({ verb(it.value3(), it.value4()) }, { tools.find(it.value5())?.order ?: 99 })).map {
						val what = label(it.value5()!!, it.value6()!!)
						when {
							it.value3() == "remove" -> "removed from $what"
							it.value4() == "invited" -> "invited to $what"
							it.value3() == "change" -> "access changed on $what"
							else -> "added to $what"
						}
					})
				}
			buildList {
				if (created.isNotEmpty()) add(ActivityItem(at, "sync", "Created ${list(created)}", null))
				if (byPerson.size > GROUP_ABOVE) {
					add(ActivityItem(at, "sync", "Updated access for ${byPerson.size} people", null, byPerson.map { (who, what) -> "$who $what" }.sorted()))
				} else byPerson.forEach { (who, what) -> add(ActivityItem(at, "sync", "$who $what", null)) }
			}
		}
	}

	/** Slack channels with a #, the rest by name. */
	private fun label(tool: String, name: String) = tools.displayName(tool, name)

	private fun agreements(projectId: UUID, before: OffsetDateTime?, limit: Int): List<ActivityItem> =
		dsl.select(AGREEMENT.STATUS, AGREEMENT.SIGNED_AT, AGREEMENT.UPDATED_AT, AGREEMENT.DECLINE_REASON, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME)
			.from(AGREEMENT).join(PERSON).on(PERSON.ID.eq(AGREEMENT.PERSON_ID))
			.where(AGREEMENT.PROJECT_ID.eq(projectId), AGREEMENT.STATUS.`in`("signed", "declined"))
			.orderBy(AGREEMENT.UPDATED_AT.desc()).limit(limit)
			.fetch()
			.mapNotNull { r ->
				val at = (if (r.value1() == "signed") r.value2() else null) ?: r.value3()!!
				if (before != null && !at.isBefore(before)) return@mapNotNull null
				val who = ProjectQueries.name(r.value7(), r.value5()!!, r.value6()!!)
				if (r.value1() == "signed") ActivityItem(at, "agreement", "$who signed the project agreement", null)
				else ActivityItem(at, "agreement", "$who declined the project agreement", null, listOfNotNull(r.value4()))
			}
}
