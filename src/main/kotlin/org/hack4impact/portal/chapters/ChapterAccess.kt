package org.hack4impact.portal.chapters

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.auth.requireChapter
import org.hack4impact.portal.chapters.ChapterQueries.Chapter
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Chapter scoping for controllers: 404 for an unknown chapter, 403 without a role in it (or, to change things, without lead, co-lead or national). */
@Component
class ChapterAccess(private val viewers: Viewers, private val chapters: ChapterQueries) {
	fun read(user: OidcUser, code: String): Pair<Viewer, Chapter> {
		val viewer = viewers.of(user)
		val chapter = chapters.byCode(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")
		viewer.requireChapter(chapter.id)
		return viewer to chapter
	}

	fun manage(user: OidcUser, code: String): Pair<Viewer, Chapter> =
		read(user, code).also { (viewer, chapter) -> if (!viewer.canManage(chapter.id)) throw AccessDeniedException("Leads only") }
}
