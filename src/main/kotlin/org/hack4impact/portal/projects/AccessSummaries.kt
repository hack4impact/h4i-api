package org.hack4impact.portal.projects

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.WithheldReason
import org.hack4impact.portal.sync.WorldLoader
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * After a project change, everyone whose projects or access it changed gets one email (decision 131, without drafts):
 * the projects they joined or left, what they now get or no longer get, and what waits for the project agreement.
 * Access is resolved before and after the change in the change's own transaction, as the full portal's `AccessDiff`
 * does for drafts; the emails go once it commits.
 */
@Component
class AccessSummaries(
	private val dsl: DSLContext,
	private val worlds: WorldLoader,
	private val tools: ToolRegistry,
	private val mailer: Mailer,
	private val scope: org.hack4impact.portal.sync.SyncScope,
	private val agreements: org.hack4impact.portal.agreements.AgreementService,
	@Value("\${portal.frontend-url}") private val frontendUrl: String,
) {
	/** Who gets what, who waits on an agreement, and who's on which project (not closed). */
	data class Snapshot(val grants: Map<Pair<UUID, UUID>, Access>, val awaiting: Set<Pair<UUID, UUID>>, val members: Set<Pair<UUID, UUID>>)

	/** Only what the sync will act on (package 5 review R2): not resources it leaves alone, nor tools this deployment doesn't sync. */
	fun snapshot(): Snapshot {
		val world = worlds.load().world
		val untouched = scope.untouched()
		val synced = world.resources.filter { it.id !in untouched && scope.connected(it.tool.name) }.map { it.id }.toSet()
		val resolution = Resolver.resolve(world).let { r -> r.copy(grants = r.grants.filter { it.resourceId in synced }, withheld = r.withheld.filter { it.resourceId in synced }) }
		val members = dsl.select(PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.PROJECT_ID).from(PROJECT_MEMBER).join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID))
			.where(PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT.STATUS.ne("closed")).fetch().map { it.value1()!! to it.value2()!! }.toSet()
		return Snapshot(
			resolution.grants.associate { (it.personId to it.resourceId) to it.access },
			resolution.withheld.filter { it.why is WithheldReason.AwaitingAgreement }.map { it.personId to it.resourceId }.toSet(),
			members,
		)
	}

	/**
	 * Emails each of [projectId]'s team, before or after the change, whose projects or access [before] → [after] changed;
	 * sent once the transaction commits. Returns how many. Only the team: a change to one project changes only their
	 * access, and anything else that committed between the two snapshots stays out (package 5 review R5).
	 */
	fun send(before: Snapshot, after: Snapshot, projectId: UUID): Int {
		val team = (before.members + after.members).filter { it.second == projectId }.map { it.first }.toSet()
		fun ofTeam(keys: Collection<Pair<UUID, UUID>>) = keys.filter { it.first in team }.toSet()
		val added = ofTeam(after.grants.keys - before.grants.keys)
		val removed = ofTeam(before.grants.keys - after.grants.keys)
		val changed = ofTeam(after.grants.keys.intersect(before.grants.keys).filter { after.grants[it] != before.grants[it] })
		val waiting = ofTeam(after.awaiting - before.awaiting) - added
		val joins = ofTeam(after.members - before.members)
		val leaves = ofTeam(before.members - after.members)
		val people = (added + removed + changed + waiting + joins + leaves).map { it.first }.toSet()
		if (people.isEmpty()) return 0

		val resourceIds = (added + removed + changed + waiting).map { it.second }.toSet()
		val labels = if (resourceIds.isEmpty()) emptyMap() else dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME).from(RESOURCE).where(RESOURCE.ID.`in`(resourceIds))
			.fetch().associate { it.value1()!! to tools.label(it.value2()!!, tools.displayName(it.value2()!!, it.value3()!!)) }
		val projectNames = dsl.select(PROJECT.ID, PROJECT.NAME).from(PROJECT).where(PROJECT.ID.`in`((joins + leaves).map { it.second })).fetch()
			.associate { it.value1()!! to it.value2()!! }
		val (chapter, code, slug) = dsl.select(CHAPTER.NAME, CHAPTER.CODE, PROJECT.SLUG).from(PROJECT).join(CHAPTER).on(CHAPTER.ID.eq(PROJECT.CHAPTER_ID))
			.where(PROJECT.ID.eq(projectId)).fetchSingle().let { Triple(it.value1()!!, it.value2()!!, it.value3()!!) }
		val link = "${frontendUrl.trimEnd('/')}/projects/$code/$slug"

		fun of(keys: Collection<Pair<UUID, UUID>>, person: UUID, names: Map<UUID, String>) = keys.filter { it.first == person }.mapNotNull { names[it.second] }.sorted()
		var sent = 0
		dsl.selectFrom(PERSON).where(PERSON.ID.`in`(people)).fetch().forEach { p ->
			val to = p.orgEmail ?: p.schoolEmail ?: p.personalEmail ?: return@forEach
			val gains = of(added, p.id!!, labels) + changed.filter { it.first == p.id }.mapNotNull { k -> labels[k.second]?.let { "$it, now ${after.grants.getValue(k).name.lowercase()}" } }
			val first = p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName!!
			mailer.send(
				Emails.accessSummary(first, chapter, of(joins, p.id!!, projectNames), of(leaves, p.id!!, projectNames), gains.sorted(), of(removed, p.id!!, labels), of(waiting, p.id!!, labels), link,
					agreements.agreementExpected(p.id!!, projectId)),
				to, person = p.id,
			)
			sent++
		}
		return sent
	}
}
