package org.hack4impact.portal.resolver

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.util.UUID

/**
 * The PRD's invariants (wiki: testing, property invariants) checked over random worlds, plus the
 * resolver's own guarantees. Each property runs 1,000 generated worlds by default.
 */
class ResolverProperties {

	@Provide
	fun worlds(): Arbitrary<World> = Combinators.combine(
		Arbitraries.longs(),
		Arbitraries.integers().between(0, 10), // people
		Arbitraries.integers().between(0, 5), // projects
		Arbitraries.integers().between(0, 10), // resources
		Arbitraries.integers().between(0, 4), // rules
	).`as` { seed, people, projects, resources, rules -> Worlds(seed).build(people, projects, resources, rules) }

	// PRD invariants -----------------------------------------------------------------------------

	@Property
	fun `running a sync twice changes nothing`(@ForAll("worlds") world: World, @ForAll seed: Long): Boolean {
		val desired = Resolver.resolve(world)
		val (actual, previous) = Worlds(seed).actualState(world, desired)
		val first = Planner.plan(desired, actual, previous)
		val synced = desired.grants.associate { (it.personId to it.resourceId) to it.reasons }
		val second = Planner.plan(desired, first.applyTo(actual), synced)
		return second.isEmpty && second.drift == first.drift
	}

	@Property
	fun `input order doesn't change the result`(@ForAll("worlds") world: World, @ForAll seed: Long): Boolean =
		Resolver.resolve(world) == Resolver.resolve(Worlds(seed).shuffled(world))

	@Property
	fun `nobody gets another chapter's resources`(@ForAll("worlds") world: World): Boolean {
		val people = world.people.associateBy { it.id }
		val resources = world.resources.associateBy { it.id }
		return Resolver.resolve(world).grants.all { g ->
			val chapter = resources.getValue(g.resourceId).chapterId
			chapter == null || chapter in people.getValue(g.personId).chapters
		}
	}

	@Property
	fun `alumni never get repo or vault access outside a live project`(@ForAll("worlds") world: World): Boolean {
		val people = world.people.associateBy { it.id }
		val resources = world.resources.associateBy { it.id }
		return Resolver.resolve(world).grants
			.filter { resources.getValue(it.resourceId).tool in setOf(Tool.GITHUB, Tool.VAULTWARDEN) }
			.filter { people.getValue(it.personId).status !in ACTIVE_LIKE }
			.all { g -> g.reasons.any { it is Reason.ProjectMember && world.liveMember(it.projectId, g.personId) != null && world.attached(it.projectId, g.resourceId) != null } }
	}

	@Property
	fun `removals never happen without a reason`(@ForAll("worlds") world: World, @ForAll seed: Long): Boolean {
		val desired = Resolver.resolve(world)
		val (actual, previous) = Worlds(seed).actualState(world, desired)
		val plan = Planner.plan(desired, actual, previous)
		return desired.grants.all { it.reasons.isNotEmpty() } &&
			plan.removals.all { it.previousReasons.isNotEmpty() && previous[it.personId to it.resourceId] == it.previousReasons } &&
			// Drift: access the portal never granted, or a level above what it wants that it didn't give (review H3).
			plan.drift.all { d ->
				previous[d.personId to d.resourceId].isNullOrEmpty() || desired.grants.any { it.personId == d.personId && it.resourceId == d.resourceId && it.access < d.access }
			}
	}

	// The resolver's own guarantees ---------------------------------------------------------------

	@Property
	fun `every reason given is actually true`(@ForAll("worlds") world: World): Boolean {
		val people = world.people.associateBy { it.id }
		val rules = world.rules.associateBy { it.id }
		return Resolver.resolve(world).grants.all { g ->
			val person = people.getValue(g.personId)
			g.reasons.all { reason ->
				when (reason) {
					is Reason.ChapterMember -> person.status in ACTIVE_LIKE && reason.chapterId in person.chapters &&
						world.chapterResources.any { it.chapterId == reason.chapterId && it.resourceId == g.resourceId && it.audience == ChapterAudience.MEMBERS }
					is Reason.ChapterLead -> person.status in ACTIVE_LIKE &&
						person.chapterRoles.any { it.chapterId == reason.chapterId && it.role != ChapterRole.VIEWER } &&
						world.chapterResources.any { it.chapterId == reason.chapterId && it.resourceId == g.resourceId && it.audience == ChapterAudience.LEADS }
					is Reason.ProjectMember -> world.liveMember(reason.projectId, g.personId) != null && world.attached(reason.projectId, g.resourceId) != null
					is Reason.FromRule -> rules[reason.ruleId]?.let { it.enabled && it.chapterId != null && person.matches(it.who) } == true
					is Reason.NationalDefault -> rules[reason.ruleId]?.let { it.enabled && it.chapterId == null && person.matches(it.who) } == true
				}
			}
		}
	}

	@Property
	fun `agreement-gated access waits for the signature`(@ForAll("worlds") world: World): Boolean =
		Resolver.resolve(world).grants.all { g ->
			g.reasons.all { reason ->
				when (reason) {
					// From a project: that project's agreement, if its link to the resource requires one.
					is Reason.ProjectMember -> world.attached(reason.projectId, g.resourceId)?.requiresAgreement != true ||
						world.liveMember(reason.projectId, g.personId)?.agreementSigned == true
					// From anywhere else: every live project that gates the resource.
					else -> world.projectResources
						.filter { it.resourceId == g.resourceId && it.requiresAgreement && world.isLive(it.projectId) }
						.all { world.liveMember(it.projectId, g.personId)?.agreementSigned == true }
				}
			}
		}

	@Property
	fun `applicants, removed people and archived resources get nothing`(@ForAll("worlds") world: World): Boolean {
		val excluded = world.people.filter { it.status == PersonStatus.APPLICANT || it.status == PersonStatus.REMOVED }.map { it.id }.toSet()
		val archived = world.resources.filter { it.archived }.map { it.id }.toSet()
		val r = Resolver.resolve(world)
		return r.grants.none { it.personId in excluded || it.resourceId in archived } &&
			r.withheld.none { it.personId in excluded || it.resourceId in archived }
	}

	@Property
	fun `people pending removal keep at least what they'd have as active members`(@ForAll("worlds") world: World): Boolean {
		val flagged = world.people.filter { it.status == PersonStatus.REMOVAL_REQUESTED }.map { it.id }.toSet()
		val asActive = world.copy(people = world.people.map { if (it.id in flagged) it.copy(status = PersonStatus.ACTIVE) else it })
		// "At least": same resources, equal or stronger access. A rule can target pending removal specifically
		// with stronger access than active members get, so exact equality would be wrong.
		val pending = Resolver.resolve(world).grants.filter { it.personId in flagged }.associate { (it.personId to it.resourceId) to it.access }
		return Resolver.resolve(asActive).grants.filter { it.personId in flagged }.all { g ->
			pending[g.personId to g.resourceId]?.let { it >= g.access } == true
		}
	}

	@Property
	fun `disabled rules have no effect`(@ForAll("worlds") world: World): Boolean =
		Resolver.resolve(world) == Resolver.resolve(world.copy(rules = world.rules.filter { it.enabled }))

	@Property
	fun `one grant per person and resource, and nothing both granted and withheld`(@ForAll("worlds") world: World): Boolean {
		val r = Resolver.resolve(world)
		val granted = r.grants.map { it.personId to it.resourceId }
		return granted.size == granted.toSet().size && r.withheld.none { (it.personId to it.resourceId) in granted.toSet() }
	}
}

/** Statuses that get an active member's access: pending removal keeps it until national confirms. */
private val ACTIVE_LIKE = setOf(PersonStatus.ACTIVE, PersonStatus.REMOVAL_REQUESTED)

private fun Person.matches(who: Who) = status in who.statuses || (status == PersonStatus.REMOVAL_REQUESTED && PersonStatus.ACTIVE in who.statuses)

private fun World.isLive(projectId: UUID) = projects.any { it.id == projectId && (it.status == ProjectStatus.ACTIVE || it.status == ProjectStatus.PAUSED) }

private fun World.liveMember(projectId: UUID, personId: UUID) =
	members.firstOrNull { it.projectId == projectId && it.personId == personId }?.takeIf { isLive(projectId) }

private fun World.attached(projectId: UUID, resourceId: UUID) =
	projectResources.firstOrNull { it.projectId == projectId && it.resourceId == resourceId }

/** What the tools look like after the sync engine carries out this plan. */
fun Plan.applyTo(actual: Collection<Membership>): List<Membership> {
	val state = actual.associateBy { it.personId to it.resourceId }.toMutableMap()
	removals.forEach { state.remove(it.personId to it.resourceId) }
	(adds + changes.map { it.grant }).forEach { state[it.personId to it.resourceId] = Membership(it.personId, it.resourceId, it.access) }
	return state.values.toList()
}
