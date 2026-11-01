package org.hack4impact.portal.resolver

import java.util.UUID
import kotlin.random.Random

/**
 * Builds random but well-formed worlds for property tests. Pools are small on purpose (few chapters, tags and
 * roles) so generated worlds collide often: shared resources, people in two chapters, rules overlapping
 * projects, members of another chapter's project.
 */
class Worlds(seed: Long) {
	private val random = Random(seed)
	private fun id() = UUID(random.nextLong(), random.nextLong())
	private fun <T> pick(xs: List<T>) = xs[random.nextInt(xs.size)]
	private fun <T> some(xs: Collection<T>, chance: Double = 0.4) = xs.filter { random.nextDouble() < chance }.toSet()
	private fun chance(p: Double) = random.nextDouble() < p

	private val tags = listOf("main", "eng", "client", "lts")
	private val titles = listOf("Director of product", "Director of engineering")

	fun build(people: Int, projects: Int, resources: Int, rules: Int): World {
		val chapters = List(1 + random.nextInt(3)) { id() }
		val roles = listOf(ProjectRole(id(), false), ProjectRole(id(), false), ProjectRole(id(), true))

		val persons = List(people) {
			val mine = (some(chapters) + pick(chapters)).let { if (chance(0.1)) emptySet() else it }
			Person(
				id(), pick(PersonStatus.entries), mine,
				if (chance(0.4) && mine.isNotEmpty()) setOf(ChapterRoleAssignment(pick(mine.toList()), pick(ChapterRole.entries), titles.random(random).takeIf { chance(0.3) })) else emptySet(),
			)
		}
		val projs = List(projects) { Project(id(), pick(chapters), pick(ProjectStatus.entries), some(tags, 0.3)) }
		val members = projs.flatMap { p ->
			some(persons, 0.5).map { ProjectMember(p.id, it.id, if (chance(0.15)) null else pick(roles).id, chance(0.6)) }
		}
		val res = List(resources) {
			Resource(id(), pick(Tool.entries), if (chance(0.2)) null else pick(chapters), some(tags, 0.35), chance(0.1))
		}
		val projectResources = projs.flatMap { p ->
			some(res, 0.35).map {
				val audience = when (random.nextInt(3)) {
					0 -> Audience.Team
					1 -> Audience.Role(pick(roles).id)
					else -> Audience.Leads
				}
				ProjectResource(p.id, it.id, audience, pick(Access.entries), chance(0.3))
			}
		}
		val chapterResources = chapters.flatMap { c -> some(res, 0.15).map { ChapterResource(c, it.id, pick(ChapterAudience.entries), pick(Access.entries)) } }
		val ruleList = List(rules) {
			val who = Who(
				statuses = (some(PersonStatus.entries, 0.3) + PersonStatus.ACTIVE).let { if (chance(0.2)) setOf(pick(PersonStatus.entries)) else it },
				everyone = chance(0.25),
				chapterRoles = some(ChapterRole.entries, 0.3),
				titles = some(titles, 0.3),
				projectRoles = some(roles.map { it.id }, 0.3),
				people = some(persons.map { it.id }, 0.2),
			)
			val projectScope = when (random.nextInt(4)) {
				0 -> ProjectScope.None
				1 -> ProjectScope.All
				2 -> ProjectScope.Ids(some(projs.map { it.id }, 0.5))
				else -> ProjectScope.Tags(some(tags, 0.5))
			}
			val resourceScope = if (chance(0.5)) ResourceScope.Ids(some(res.map { it.id }, 0.4)) else ResourceScope.Tags(some(tags, 0.5))
			Rule(id(), if (chance(0.3)) null else pick(chapters), who, projectScope, resourceScope, pick(Access.entries), some(Tool.entries, 0.3), chance(0.85))
		}
		return World(persons, projs, roles, members, res, projectResources, chapterResources, ruleList, sensitiveTools = setOf(Tool.GITHUB, Tool.VAULTWARDEN))
	}

	/** The same world with every list in a different order. */
	fun shuffled(world: World) = World(
		world.people.shuffled(random), world.projects.shuffled(random), world.projectRoles.shuffled(random),
		world.members.shuffled(random), world.resources.shuffled(random), world.projectResources.shuffled(random),
		world.chapterResources.shuffled(random), world.rules.shuffled(random), world.sensitiveTools,
	)

	/** Random actual tool state and sync records: some of the desired grants, some other pairs, some stale reasons. */
	fun actualState(world: World, desired: Resolution): Pair<List<Membership>, Map<Pair<UUID, UUID>, List<Reason>>> {
		val pairs = world.people.flatMap { p -> world.resources.map { p.id to it.id } }
		val present = (some(desired.grants.map { it.personId to it.resourceId }, 0.6) + some(pairs, 0.2))
		val actual = present.map { Membership(it.first, it.second, pick(Access.entries)) }
		val previous = some(pairs, 0.3).associateWith { if (chance(0.2)) emptyList() else listOf(Reason.ChapterMember(it.first)) }
		return actual to previous
	}
}
