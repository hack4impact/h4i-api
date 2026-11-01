package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.PlannedChange
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// Projects API (stage 2, from the full portal's build plan step 8, without drafts, Notion pages or repositories).
// Reading needs a role in the chapter; changing needs lead, co-lead or national; a project's own leads change its team.

data class ProjectSummary(
	val id: UUID,
	val name: String,
	val slug: String,
	/** draft, active, paused or closed. Only active and paused projects give access. */
	val status: String,
	val type: String?,
	val tags: List<String>,
	val term: String?,
	val partner: String?,
	val members: Int,
	val resources: Int,
	val updatedAt: OffsetDateTime,
)

data class ProjectDetail(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val type: String?,
	val tags: List<String>,
	val termId: UUID?,
	val term: String?,
	val partnerId: UUID?,
	val partner: String?,
	/** The member database's past project this one is linked to (decision 168); only settable while a draft. */
	val pastProjectId: UUID?,
	val startsOn: LocalDate?,
	val endsOn: LocalDate?,
	val members: List<ProjectMemberRow>,
	val resources: List<ProjectResourceRow>,
	/** Members who signed (or were waived from) the project agreement, out of all members. */
	val agreementsSigned: Int,
	/** Who would get access; for a draft project, as if it were active. */
	val preview: AccessPreview,
	/** Each tool's latest run for this project's resources: what a dry run would change, or what an applying run did. */
	val planned: List<PlannedChange>,
	val lastDryRunAt: OffsetDateTime?,
	/** The project changed after the latest run, so [planned] may be out of date. */
	val plannedOutOfDate: Boolean,
	/** The latest few; [activityMore] when there's older (GET …/activity). */
	val activity: List<ActivityItem>,
	val activityMore: Boolean,
	val canEdit: Boolean,
	/** May change who's on the team: chapter leads and co-leads, national, and the project's own leads. */
	val canEditTeam: Boolean,
)

data class ProjectMemberRow(
	val personId: UUID,
	val name: String,
	val email: String?,
	/** The person's status in H4I: active, alumni, … */
	val status: String,
	val roleId: UUID,
	val role: String,
	val isLead: Boolean,
	/** pending, signed or waived. */
	val agreementStatus: String,
	/** Their latest project agreement in Documenso, if one was made. */
	val agreement: MemberAgreement? = null,
)

data class MemberAgreement(
	val id: UUID,
	/** draft (not sent yet), sent, signed, declined, expired or voided. */
	val status: String,
	val sentAt: OffsetDateTime?,
	val signedAt: OffsetDateTime?,
	val reminders: Int,
	/** The signed PDF is kept in the portal (GET /api/agreements/{id}/pdf). */
	val hasPdf: Boolean,
	/** Why it couldn't be sent or read, in words. */
	val error: String?,
	val declineReason: String?,
)

data class ProjectResourceRow(
	val resourceId: UUID,
	val tool: String,
	val name: String,
	/** to_create (only in the portal so far), exists (linked to the tool) or archived. */
	val state: String,
	/** portal (the portal made it), adopted (national linked an existing one) or unmanaged (left alone). */
	val managed: String,
	/** team, role or leads (project leads). */
	val audience: String,
	val roleId: UUID?,
	val role: String?,
	/** read, write or admin. */
	val access: String,
	val requiresAgreement: Boolean,
	val tags: List<String>,
	/** Other projects using the same resource. */
	val sharedWith: List<String>,
	/** The tool already has its name: waiting for national to link it, rename the portal's, or leave it. */
	val existsInTool: Boolean,
	/** People who would have it (from the resolver). */
	val getting: Int,
	/** People waiting on the project agreement for it. */
	val awaitingAgreement: Int,
	/** Where to open it in the tool, once it exists there. */
	val url: String? = null,
	/** How the sync stands for it, person by person. */
	val sync: ResourceSync = ResourceSync(),
)

/** Sync records for one resource, counted by state (in sync, invited and not accepted yet, …). */
data class ResourceSync(val inSync: Int = 0, val waitingOnMember: Int = 0, val manualTask: Int = 0, val pending: Int = 0, val failing: Int = 0)

data class AccessPreview(val people: Int, val grants: Int, val awaitingAgreement: Int, val asIfActive: Boolean)

data class ProjectRoleOption(val id: UUID, val name: String, val isLead: Boolean)

data class TermOption(val id: UUID, val label: String)

data class ChapterResourceOption(val id: UUID, val tool: String, val name: String, val state: String, val projects: List<String>)

data class ProjectInput(
	val name: String,
	/** Only on create; derived from the name when blank. With the chapter code it names the standard resources. */
	val slug: String? = null,
	val type: String? = null,
	val tags: List<String> = emptyList(),
	val termId: UUID? = null,
	/** Picked from `GET /api/partners`; a project can't start without one (the member database needs it). */
	val partnerId: UUID? = null,
	/** A member-database project with no engagement yet (`GET …/past-projects`), only while the project is a draft. */
	val pastProjectId: UUID? = null,
	/** Defaults to the day the project is started. */
	val startsOn: LocalDate? = null,
	val endsOn: LocalDate? = null,
)

data class StatusChange(val status: String)

/** [roleId] is required: the member database records every team member's role. */
data class MemberInput(val personId: UUID, val roleId: UUID? = null)

/** Someone who could join a project's team. */
data class TeamCandidate(val personId: UUID, val name: String, val status: String)

data class MemberRoleInput(val roleId: UUID? = null)

/** Attach an existing resource ([resourceId]) or a new one ([tool] and [name]) to the project. */
data class ResourceInput(
	val resourceId: UUID? = null,
	val tool: String? = null,
	val name: String? = null,
	val audience: String = "team",
	val roleId: UUID? = null,
	val access: String = "write",
	val requiresAgreement: Boolean = false,
	/** The resource's tags (shared by every project using it); left out (null): unchanged. */
	val tags: List<String>? = null,
)

data class PartnerOption(val id: UUID, val name: String, val website: String?)

data class PartnerInput(val name: String, val website: String? = null)

/** A member-database project with no engagement yet, that a new project may link to. */
data class PastProject(val id: UUID, val name: String)
