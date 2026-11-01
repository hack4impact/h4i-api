package org.hack4impact.portal.chapters

import org.hack4impact.portal.members.MemberHomeQueries
import org.hack4impact.portal.members.MyAccess
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** A member's access in this chapter and why (stage 2): what the member's own page shows, limited to the chapter's resources. */
@RestController
class MemberAccessController(private val access: ChapterAccess, private val chapters: ChapterQueries, private val homes: MemberHomeQueries) {
	/** Leads, co-leads and national. 404 for someone who isn't in the chapter (or left it). */
	@GetMapping("/api/chapters/{code}/members/{personId}/access")
	fun access(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable personId: UUID): List<MyAccess> {
		val (_, chapter) = access.manage(user, code)
		if (!chapters.isMember(chapter.id, personId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this chapter")
		return homes.access(personId, chapter.id, own = false)
	}
}
