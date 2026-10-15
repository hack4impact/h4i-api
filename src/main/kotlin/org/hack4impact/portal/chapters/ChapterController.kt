package org.hack4impact.portal.chapters

import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.auth.requireChapter
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Read-only chapter API. Every chapter endpoint checks the viewer's role in that chapter (PRD: chapter scoping). */
@RestController
@RequestMapping("/api")
class ChapterController(
	private val viewers: Viewers,
	private val chapters: ChapterQueries,
	@Value("\${portal.join-base-url}") private val joinBaseUrl: String,
) {
	@GetMapping("/me")
	fun me(@AuthenticationPrincipal user: OidcUser): Me {
		val viewer = viewers.of(user)
		return Me(viewer.email, viewer.name, viewer.personId, viewer.nationalAdmin, chapters.visibleTo(viewer))
	}

	@GetMapping("/chapters/{code}")
	fun chapter(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): ChapterOverview {
		val (viewer, chapter) = authorize(user, code)
		return ChapterOverview(
			chapter.id, chapter.code, chapter.name, chapter.status,
			role = viewer.chapterRoles[chapter.id] ?: "national",
			stats = chapters.stats(chapter.id),
			registrationLink = "${joinBaseUrl.trimEnd('/')}/${chapter.code}",
		)
	}

	@GetMapping("/chapters/{code}/members")
	fun members(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<MemberRow> {
		val (_, chapter) = authorize(user, code)
		return chapters.members(chapter.id)
	}

	/** 404 for an unknown chapter, 403 for one the viewer has no role in. */
	private fun authorize(user: OidcUser, code: String) =
		viewers.of(user).let { viewer ->
			val chapter = chapters.byCode(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")
			viewer.requireChapter(chapter.id)
			viewer to chapter
		}
}
