package org.hack4impact.portal.projects

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.chapters.ChapterAccess
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.sync.SyncRequests
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Projects (stage 2, from the full portal's build plan step 8, without drafts). Every change answers with the updated
 * project. A change requests a sync in the same transaction (decision 142), so the tools follow once it commits, and
 * everyone whose projects or access it changed is emailed a summary (decision 131).
 */
@RestController
@RequestMapping("/api")
class ProjectController(
	private val access: ChapterAccess,
	private val queries: ProjectQueries,
	private val service: ProjectService,
	private val syncs: SyncRequests,
	private val summaries: AccessSummaries,
	private val projectActivity: ProjectActivity,
	private val transactions: TransactionTemplate,
) {
	/** Role names, and which are leads. Any signed-in person. */
	@GetMapping("/project-roles")
	fun roles(): List<ProjectRoleOption> = queries.roles()

	/** The chapter's school's semesters, for a project. Leads, co-leads and national. */
	@GetMapping("/chapters/{code}/terms")
	fun terms(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<TermOption> = queries.terms(access.manage(user, code).second.id)

	/** The chapter's resources, to attach one to another project. Leads, co-leads and national. */
	@GetMapping("/chapters/{code}/resources")
	fun resources(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<ChapterResourceOption> =
		queries.chapterResources(access.manage(user, code).second.id)

	@GetMapping("/chapters/{code}/projects")
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<ProjectSummary> = queries.list(access.read(user, code).second.id)

	@PostMapping("/chapters/{code}/projects")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: ProjectInput): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		val project = transactions.execute { service.create(chapter.id, input, viewer) }!!
		return queries.detail(chapter.id, project(chapter.id, project.slug!!), true)
	}

	/** The chapter's roles see every project; a project lead without one sees the projects they lead. */
	@GetMapping("/chapters/{code}/projects/{slug}")
	fun get(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String): ProjectDetail {
		val (viewer, chapterId, project) = readable(user, code, slug)
		return queries.detail(chapterId, project, viewer.canManage(chapterId), viewer.canManageTeam(chapterId, project.id!!))
	}

	/** Older activity, newest first: [before] is the oldest item already shown. */
	@GetMapping("/chapters/{code}/projects/{slug}/activity")
	fun activity(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String,
		@RequestParam(required = false) before: OffsetDateTime?, @RequestParam(defaultValue = "20") limit: Int,
	): ActivityPage = projectActivity.page(readable(user, code, slug).third.id!!, before, limit)

	/** Who could join the team: for the chapter's leads and the project's leads (who can't see the chapter's member list). */
	@GetMapping("/chapters/{code}/projects/{slug}/candidates")
	fun candidates(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String): List<TeamCandidate> {
		val (viewer, chapter) = access.lookup(user, code)
		val project = project(chapter.id, slug)
		if (!viewer.canManageTeam(chapter.id, project.id!!)) throw AccessDeniedException("Only the project's leads and the chapter's leads can change the team")
		return queries.candidates(chapter.id, project.id!!)
	}

	@PutMapping("/chapters/{code}/projects/{slug}")
	fun update(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ProjectInput) =
		change(user, code, slug) { chapter, project, viewer -> service.update(chapter, project, input, viewer) }

	/** Start (`active`, needs a partner), pause or close. */
	@PostMapping("/chapters/{code}/projects/{slug}/status")
	fun status(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: StatusChange) =
		change(user, code, slug) { chapter, project, viewer -> service.setStatus(chapter, project, input.status, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/members")
	fun addMember(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: MemberInput) =
		team(user, code, slug) { chapter, project, viewer -> service.addMember(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun setRole(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID, @RequestBody input: MemberRoleInput,
	) = team(user, code, slug) { chapter, project, viewer -> service.setMemberRole(chapter, project, personId, input, viewer) }

	@DeleteMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun removeMember(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID) =
		team(user, code, slug) { chapter, project, viewer -> service.removeMember(chapter, project, personId, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/resources")
	fun attach(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ResourceInput) =
		change(user, code, slug) { chapter, project, viewer -> service.attachResource(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun updateResource(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID, @RequestBody input: ResourceInput,
	) = change(user, code, slug) { chapter, project, viewer -> service.updateResource(chapter, project, resourceId, input, viewer) }

	@DeleteMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun detach(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID) =
		change(user, code, slug) { chapter, project, viewer -> service.detachResource(chapter, project, resourceId, viewer) }

	private fun project(chapterId: UUID, slug: String): ProjectRecord =
		queries.bySlug(chapterId, slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No project $slug")

	/** For reading a project: a role in its chapter, or leading it. */
	private fun readable(user: OidcUser, code: String, slug: String): Triple<Viewer, UUID, ProjectRecord> {
		val (viewer, chapter) = access.lookup(user, code)
		if (!viewer.canSee(chapter.id) && viewer.ledProjects.isEmpty()) throw AccessDeniedException("You have no role in this chapter")
		val project = project(chapter.id, slug)
		if (!viewer.canSee(chapter.id) && project.id !in viewer.ledProjects) throw AccessDeniedException("You don't lead this project")
		return Triple(viewer, chapter.id, project)
	}

	/** A change to the team: the chapter's leads and national, and the project's own leads (decision 133). */
	private fun team(user: OidcUser, code: String, slug: String, apply: (UUID, ProjectRecord, Viewer) -> Unit): ProjectDetail {
		val (viewer, chapter) = access.lookup(user, code)
		if (viewer.canManage(chapter.id)) return change(user, code, slug, apply)
		val project = if (viewer.ledProjects.isEmpty()) null else project(chapter.id, slug)
		if (project == null || project.id !in viewer.ledProjects) throw AccessDeniedException("Only the project's leads and the chapter's leads can change the team")
		applied(chapter.id, project, viewer, apply)
		return queries.detail(chapter.id, project(chapter.id, slug), false, true)
	}

	private fun change(user: OidcUser, code: String, slug: String, apply: (UUID, ProjectRecord, Viewer) -> Unit): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		applied(chapter.id, project(chapter.id, slug), viewer, apply)
		return queries.detail(chapter.id, project(chapter.id, slug), true)
	}

	/**
	 * The change, its summary emails (sent once it commits) and a sync request in one transaction (review H4, decision
	 * 142). A closed project can't change.
	 */
	private fun applied(chapterId: UUID, project: ProjectRecord, viewer: Viewer, apply: (UUID, ProjectRecord, Viewer) -> Unit) = transactions.executeWithoutResult {
		if (project.status == "closed") throw ResponseStatusException(HttpStatus.CONFLICT, "This project is closed")
		val before = summaries.snapshot()
		apply(chapterId, project, viewer)
		summaries.send(before, summaries.snapshot(), project.id!!)
		syncs.request("change")
	}
}
