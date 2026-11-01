package org.hack4impact.portal.resolver

import java.util.UUID

// Inputs and outputs of the resolver (build plan step 3). Plain values with no database or framework
// types, so the resolver stays a pure function that property tests can drive directly.

enum class PersonStatus { APPLICANT, ACTIVE, ALUMNI, REMOVAL_REQUESTED, REMOVED }

enum class Tool {
	GOOGLE, GITHUB, SLACK, NOTION, VAULTWARDEN, DOCUMENSO;

	companion object {
		/**
		 * The tool a stored name means, or null for one the code doesn't know (a removed plugin's rows): the database
		 * only checks the name's format (V15), so callers skip such rows rather than fail.
		 */
		fun fromKey(key: String?): Tool? = key?.let { k -> entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
	}
}

/** Ordered: a stronger grant wins. Adapters map these onto each tool's own levels. */
enum class Access { READ, WRITE, ADMIN }

enum class ChapterRole { LEAD, CO_LEAD, VIEWER }

/** Only ACTIVE and PAUSED projects grant access ("live" projects). */
enum class ProjectStatus { DRAFT, ACTIVE, PAUSED, CLOSED }

data class Person(
	val id: UUID,
	val status: PersonStatus,
	val chapters: Set<UUID>,
	val chapterRoles: Set<ChapterRoleAssignment> = emptySet(),
)

/** A chapter role plus its official title, e.g. LEAD titled "Director of product" (wiki decision 35). */
data class ChapterRoleAssignment(val chapterId: UUID, val role: ChapterRole, val title: String? = null)

data class Project(val id: UUID, val chapterId: UUID, val status: ProjectStatus, val tags: Set<String> = emptySet())

data class ProjectRole(val id: UUID, val isLead: Boolean)

/** A current member of a project (removed members are simply absent). */
data class ProjectMember(val projectId: UUID, val personId: UUID, val roleId: UUID?, val agreementSigned: Boolean)

/** Anything access is granted to. `chapterId` null = a national resource, e.g. #alumni or the Google account. */
data class Resource(
	val id: UUID,
	val tool: Tool,
	val chapterId: UUID?,
	val tags: Set<String> = emptySet(),
	val archived: Boolean = false,
)

sealed interface Audience {
	data object Team : Audience
	data class Role(val roleId: UUID) : Audience
	data object Leads : Audience
}

data class ProjectResource(
	val projectId: UUID,
	val resourceId: UUID,
	val audience: Audience,
	val access: Access,
	val requiresAgreement: Boolean = false,
)

enum class ChapterAudience { MEMBERS, LEADS }

/** Chapter-level access granted on approval: the chapter channel, chapter GitHub team, Notion teamspace. */
data class ChapterResource(val chapterId: UUID, val resourceId: UUID, val audience: ChapterAudience, val access: Access)

/**
 * "When [who] -> add to [projects] . [resources]". Rules only add access. `chapterId` null = a national
 * default, which applies everywhere; a chapter rule only reaches that chapter's people and resources.
 */
data class Rule(
	val id: UUID,
	val chapterId: UUID?,
	val who: Who,
	val projects: ProjectScope,
	val resources: ResourceScope,
	val access: Access,
	val tools: Set<Tool> = emptySet(), // empty = every tool
	val enabled: Boolean = true,
)

/**
 * Who a rule applies to. A person matches when their status is in [statuses] and either [everyone] is set
 * or they match any of the selectors: a chapter role or title, a role on a live project, or being listed.
 */
data class Who(
	val statuses: Set<PersonStatus> = setOf(PersonStatus.ACTIVE),
	val everyone: Boolean = false,
	val chapterRoles: Set<ChapterRole> = emptySet(),
	val titles: Set<String> = emptySet(),
	val projectRoles: Set<UUID> = emptySet(),
	val people: Set<UUID> = emptySet(),
)

/** Which projects' resources a rule draws from. [None] = resources not tied to a project selection. */
sealed interface ProjectScope {
	data object None : ProjectScope
	data object All : ProjectScope
	data class Ids(val ids: Set<UUID>) : ProjectScope
	data class Tags(val tags: Set<String>) : ProjectScope
}

sealed interface ResourceScope {
	data class Ids(val ids: Set<UUID>) : ResourceScope
	data class Tags(val tags: Set<String>) : ResourceScope
}

/** Everything the resolver reads. Built from released data, or from released data plus a draft for previews. */
data class World(
	val people: List<Person>,
	val projects: List<Project> = emptyList(),
	val projectRoles: List<ProjectRole> = emptyList(),
	val members: List<ProjectMember> = emptyList(),
	val resources: List<Resource> = emptyList(),
	val projectResources: List<ProjectResource> = emptyList(),
	val chapterResources: List<ChapterResource> = emptyList(),
	val rules: List<Rule> = emptyList(),
	/** Repositories and vaults: non-active people get them only through a live project they're on (the tool registry). */
	val sensitiveTools: Set<Tool>,
)

// Output -----------------------------------------------------------------------------------------

/** Why someone has a resource. The UI shows these next to every grant ("always say why"). */
sealed interface Reason {
	data class ChapterMember(val chapterId: UUID) : Reason
	data class ChapterLead(val chapterId: UUID) : Reason
	data class ProjectMember(val projectId: UUID) : Reason
	data class FromRule(val ruleId: UUID) : Reason
	data class NationalDefault(val ruleId: UUID) : Reason
}

data class Grant(val personId: UUID, val resourceId: UUID, val access: Access, val reasons: List<Reason>)

/** Something a person would get but doesn't yet, and why. Shown as e.g. "Agreement unsigned". */
data class Withheld(val personId: UUID, val resourceId: UUID, val why: WithheldReason)

sealed interface WithheldReason {
	data class AwaitingAgreement(val projectId: UUID) : WithheldReason
	data class OtherChapter(val chapterId: UUID) : WithheldReason
	/** Repos and vault collections only reach non-active people through a live project they're on. */
	data object SensitiveNeedsProject : WithheldReason
}

/** Grants and withholdings, each sorted by person then resource, so equal worlds give equal results. */
data class Resolution(val grants: List<Grant>, val withheld: List<Withheld>)
