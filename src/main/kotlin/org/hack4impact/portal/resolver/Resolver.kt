package org.hack4impact.portal.resolver

import java.util.UUID

/**
 * Works out who should have what: a pure function from a [World] to grants with reasons (build plan step 3).
 *
 * Access comes from three places: chapter resources (active members, or leads), project resources
 * (members of live projects, by audience) and rules (including national defaults). Every candidate then
 * passes the same guards, which hold whatever the configuration says:
 * - applicants and removed people get nothing; archived resources go to nobody;
 * - people pending removal keep everything an active member has until national confirms;
 * - a chapter's resources only reach that chapter's members;
 * - repos and vault collections only reach non-active people through a live project they're on;
 * - agreement-gated resources wait for the signature: from a project, that project's agreement; from a
 *   chapter resource or rule, the agreement of every live project that gates the resource.
 * Candidates that fail a guard are reported as [Withheld], unless the person gets the resource another way.
 */
object Resolver {

	private val LIVE = setOf(ProjectStatus.ACTIVE, ProjectStatus.PAUSED)
	private val NO_ACCESS = setOf(PersonStatus.APPLICANT, PersonStatus.REMOVED)

	private data class Candidate(
		val person: Person,
		val resource: Resource,
		val access: Access,
		val reason: Reason,
		val awaitingAgreement: UUID? = null,
	)

	fun resolve(world: World): Resolution {
		val index = Index(world)
		val accepted = mutableListOf<Candidate>()
		val withheld = mutableSetOf<Withheld>()
		for (candidate in chapterCandidates(index) + projectCandidates(index) + ruleCandidates(index)) {
			when (val verdict = guard(candidate, world.sensitiveTools)) {
				Verdict.Accept -> accepted += candidate
				Verdict.Drop -> {}
				is Verdict.Withhold -> withheld += Withheld(candidate.person.id, candidate.resource.id, verdict.why)
			}
		}

		val grants = accepted
			.groupBy { it.person.id to it.resource.id }
			.map { (key, list) ->
				Grant(key.first, key.second, list.maxOf { it.access }, list.map { it.reason }.distinct().sortedBy { it.sortKey() })
			}
			.sortedWith(compareBy({ it.personId }, { it.resourceId }))
		val granted = grants.mapTo(HashSet()) { it.personId to it.resourceId }
		return Resolution(
			grants = grants,
			withheld = withheld
				.filter { (it.personId to it.resourceId) !in granted }
				.sortedWith(compareBy({ it.personId }, { it.resourceId }, { it.why.toString() })),
		)
	}

	private sealed interface Verdict {
		data object Accept : Verdict
		/** Not granted and not worth reporting: nobody needs telling that an applicant has no access. */
		data object Drop : Verdict
		data class Withhold(val why: WithheldReason) : Verdict
	}

	private fun guard(c: Candidate, sensitive: Set<Tool>): Verdict {
		val chapter = c.resource.chapterId
		return when {
			c.person.status in NO_ACCESS || c.resource.archived -> Verdict.Drop
			chapter != null && chapter !in c.person.chapters -> Verdict.Withhold(WithheldReason.OtherChapter(chapter))
			c.resource.tool in sensitive && c.person.accessStatus != PersonStatus.ACTIVE && c.reason !is Reason.ProjectMember ->
				Verdict.Withhold(WithheldReason.SensitiveNeedsProject)
			c.awaitingAgreement != null -> Verdict.Withhold(WithheldReason.AwaitingAgreement(c.awaitingAgreement))
			else -> Verdict.Accept
		}
	}

	// Sources of access ---------------------------------------------------------------------------

	private fun chapterCandidates(ix: Index): List<Candidate> = buildList {
		for (cr in ix.world.chapterResources) {
			val resource = ix.resources[cr.resourceId] ?: continue
			for (person in ix.world.people) {
				if (person.accessStatus != PersonStatus.ACTIVE || cr.chapterId !in person.chapters) continue
				when (cr.audience) {
					ChapterAudience.MEMBERS ->
						add(Candidate(person, resource, cr.access, Reason.ChapterMember(cr.chapterId), ix.unsignedGate(resource, person)))
					ChapterAudience.LEADS -> if (ix.leadsChapter(person, cr.chapterId)) {
						add(Candidate(person, resource, cr.access, Reason.ChapterLead(cr.chapterId), ix.unsignedGate(resource, person)))
					}
				}
			}
		}
	}

	private fun projectCandidates(ix: Index): List<Candidate> = buildList {
		for (pr in ix.world.projectResources) {
			if (pr.projectId !in ix.liveProjects) continue
			val resource = ix.resources[pr.resourceId] ?: continue
			for (member in ix.membersByProject[pr.projectId].orEmpty()) {
				val person = ix.people[member.personId] ?: continue
				if (person.accessStatus != PersonStatus.ACTIVE && person.accessStatus != PersonStatus.ALUMNI) continue
				val inAudience = when (val audience = pr.audience) {
					Audience.Team -> true
					is Audience.Role -> member.roleId == audience.roleId
					Audience.Leads -> ix.roles[member.roleId]?.isLead == true
				}
				if (!inAudience) continue
				val awaiting = pr.projectId.takeIf { pr.requiresAgreement && !member.agreementSigned }
				add(Candidate(person, resource, pr.access, Reason.ProjectMember(pr.projectId), awaiting))
			}
		}
	}

	private fun ruleCandidates(ix: Index): List<Candidate> = buildList {
		for (rule in ix.world.rules) {
			if (!rule.enabled) continue
			val reason = if (rule.chapterId == null) Reason.NationalDefault(rule.id) else Reason.FromRule(rule.id)
			val targets = ruleResources(ix, rule)
			for (person in ix.world.people) {
				if (!appliesTo(ix, rule, person)) continue
				for (resource in targets) add(Candidate(person, resource, rule.access, reason, ix.unsignedGate(resource, person)))
			}
		}
	}

	private fun appliesTo(ix: Index, rule: Rule, person: Person): Boolean {
		val who = rule.who
		val scope = rule.chapterId
		val statusMatches = person.status in who.statuses || person.accessStatus in who.statuses
		if (!statusMatches || (scope != null && scope !in person.chapters)) return false
		if (who.everyone || person.id in who.people) return true
		val roles = person.chapterRoles.filter { scope == null || it.chapterId == scope }
		if (roles.any { it.role in who.chapterRoles || it.title in who.titles }) return true
		return ix.membershipsByPerson[person.id].orEmpty().any {
			it.roleId in who.projectRoles && (scope == null || ix.liveProjects.getValue(it.projectId).chapterId == scope)
		}
	}

	private fun ruleResources(ix: Index, rule: Rule): List<Resource> {
		val scope = rule.chapterId
		val fromProjects: Collection<Resource> = when (val projects = rule.projects) {
			ProjectScope.None -> ix.world.resources
			ProjectScope.All -> ix.resourcesOfLiveProjects { true }
			is ProjectScope.Ids -> ix.resourcesOfLiveProjects { it.id in projects.ids }
			is ProjectScope.Tags -> ix.resourcesOfLiveProjects { project -> project.tags.any { it in projects.tags } }
		}
		return fromProjects.filter { resource ->
			(scope == null || resource.chapterId == scope) &&
				(rule.tools.isEmpty() || resource.tool in rule.tools) &&
				when (val wanted = rule.resources) {
					is ResourceScope.Ids -> resource.id in wanted.ids
					is ResourceScope.Tags -> resource.tags.any { it in wanted.tags }
				}
		}.distinct()
	}

	/**
	 * The status that decides access. Removal is only requested, not confirmed, so a person pending removal
	 * keeps what an active member has until national confirms (wiki decision 54).
	 */
	private val Person.accessStatus
		get() = if (status == PersonStatus.REMOVAL_REQUESTED) PersonStatus.ACTIVE else status

	private fun Reason.sortKey(): String = when (this) {
		is Reason.ChapterLead -> "1 $chapterId"
		is Reason.ChapterMember -> "2 $chapterId"
		is Reason.ProjectMember -> "3 $projectId"
		is Reason.FromRule -> "4 $ruleId"
		is Reason.NationalDefault -> "5 $ruleId"
	}

	/** Lookups built once per resolve. Members of non-live projects are left out everywhere. */
	private class Index(val world: World) {
		val people = world.people.associateBy { it.id }
		val resources = world.resources.associateBy { it.id }
		val roles = world.projectRoles.associateBy { it.id }
		val liveProjects = world.projects.filter { it.status in LIVE }.associateBy { it.id }
		private val liveMembers = world.members.filter { it.projectId in liveProjects }
		val membersByProject = liveMembers.groupBy { it.projectId }
		val membershipsByPerson = liveMembers.groupBy { it.personId }
		val signed = liveMembers.filter { it.agreementSigned }.mapTo(HashSet()) { it.projectId to it.personId }
		val gatedBy: Map<UUID, Set<UUID>> = world.projectResources
			.filter { it.requiresAgreement && it.projectId in liveProjects }
			.groupBy({ it.resourceId }, { it.projectId })
			.mapValues { it.value.toSet() }

		/** The first live project that gates [resource] behind an agreement [person] hasn't signed, if any. */
		fun unsignedGate(resource: Resource, person: Person): UUID? =
			gatedBy[resource.id].orEmpty().sorted().firstOrNull { (it to person.id) !in signed }

		fun leadsChapter(person: Person, chapterId: UUID) =
			person.chapterRoles.any { it.chapterId == chapterId && (it.role == ChapterRole.LEAD || it.role == ChapterRole.CO_LEAD) }

		fun resourcesOfLiveProjects(select: (Project) -> Boolean): List<Resource> =
			world.projectResources
				.filter { pr -> liveProjects[pr.projectId]?.let(select) == true }
				.mapNotNull { resources[it.resourceId] }
	}
}
