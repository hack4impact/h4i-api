package org.hack4impact.portal.chapters

import java.util.UUID

// Response shapes of the chapter API. The frontend's types are generated
// from the OpenAPI spec of these (openapi.json).

data class Me(
	val email: String,
	val name: String?,
	/** Null when the Google account isn't linked to anyone in the portal yet. */
	val personId: UUID?,
	val nationalAdmin: Boolean,
	/** Chapters this person may see. Empty means signed in but no access. */
	val chapters: List<ChapterRef>,
)

/** [role] is the viewer's role here: lead, co_lead, viewer, or national for national admins. */
data class ChapterRef(val id: UUID, val code: String, val name: String, val role: String)

data class ChapterOverview(
	val id: UUID,
	val code: String,
	val name: String,
	val status: String,
	val role: String,
	val stats: ChapterStats,
	val registrationLink: String,
)

/** [waitingApplications]: sent and waiting for a lead on the Approvals tab. */
data class ChapterStats(val activeMembers: Int, val alumni: Int, val waitingApplications: Int, val leads: Int)

data class MemberRow(
	val personId: UUID,
	val name: String,
	/** The @hack4impact.org address, else the school address. Personal emails aren't shown (PRD: Security). */
	val email: String?,
	/** applicant, active, alumni, removal_requested or removed. */
	val status: String,
	val kind: String,
	/** lead, co_lead or viewer in this chapter, if any. */
	val chapterRole: String?,
	/** Official title, e.g. "Director of product" (decision 35). */
	val title: String?,
	val accounts: List<AccountSummary>,
	/** Whether the person has confirmed their details (claimed or registered). */
	val claimed: Boolean,
	/** Imported, not claimed, not removed, with an address on file: national can send them a claim link. */
	val claimable: Boolean = false,
	/** When their current claim link (unused, not expired) was made, if they have one. */
	val claimLinkSentAt: java.time.OffsetDateTime? = null,
)

/** One tool account: [state] is unverified, invited, accepted, confirmed, suspended or removed. */
data class AccountSummary(val tool: String, val state: String)
