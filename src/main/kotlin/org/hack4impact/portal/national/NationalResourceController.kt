package org.hack4impact.portal.national

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Linking existing channels, groups and teams to projects and chapters (wiki: staged-rollout-stage-2, decision 170).
 * National only: a lead could otherwise hand their team another chapter's or national's channel (Q43).
 */
@RestController
@RequestMapping("/api/national")
class NationalResourceController(private val viewers: Viewers, private val links: ResourceLinks) {
	private fun national(user: OidcUser): Viewer =
		viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	/** A chapter's resources: managed or not, what each is linked to, and whether the tool already has its name. */
	@GetMapping("/chapters/{code}/resources")
	fun resources(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<NationalResource> {
		national(user)
		return links.list(code)
	}

	/** Starts a link for a resource the portal doesn't know yet (a chapter's own channel): unmanaged until national decides. */
	@PostMapping("/chapters/{code}/resources")
	fun start(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: StartLink): NationalResource =
		links.start(code, input, national(user))

	/** Who linking the resource to [target] would add and keep, read live from the tool. */
	@GetMapping("/resources/{id}/link")
	fun preview(
		@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID,
		@RequestParam target: String, @RequestParam(required = false) projectSlug: String?, @RequestParam(required = false) access: String?,
	): LinkPreviewResult {
		national(user)
		return links.preview(id, target, projectSlug, access)
	}

	/** Links it (confirming the "will add" count from the preview), renames the portal's, or leaves it unmanaged. */
	@PostMapping("/resources/{id}/link")
	fun decide(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestBody decision: LinkDecision): NationalResource =
		links.decide(id, decision, national(user))

	/** detach: the portal takes back what it added; unmanaged: it stops touching the resource and removes no one. */
	@DeleteMapping("/resources/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestParam mode: String) =
		links.remove(id, mode, national(user))
}
