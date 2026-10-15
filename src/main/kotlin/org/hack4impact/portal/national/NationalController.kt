package org.hack4impact.portal.national

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class QueueTask(
	val id: UUID,
	val tool: String,
	/** create_account (a Google account to make by hand), check_account (a GitHub account linked to two people), … */
	val action: String,
	val description: String,
	val createdAt: OffsetDateTime,
	val status: String,
	val doneBy: String?,
	val doneAt: OffsetDateTime?,
)

/** [action]: done (made by hand) or cancel. */
data class TaskDecision(val action: String, val reason: String? = null)

/** National's admin queue. National admins only (claim links are in the registration API). */
@RestController
@RequestMapping("/api/national")
class NationalController(private val viewers: Viewers, private val queue: AdminQueue) {
	private fun national(user: OidcUser): Viewer =
		viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping("/queue")
	fun queue(@AuthenticationPrincipal user: OidcUser): List<QueueTask> {
		national(user)
		return queue.tasks()
	}

	@PostMapping("/queue/{id}")
	fun decide(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestBody decision: TaskDecision): List<QueueTask> {
		val viewer = national(user)
		when (decision.action) {
			"done" -> queue.done(id, viewer)
			"cancel" -> queue.cancel(id, viewer, decision.reason)
			else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be done or cancel")
		}
		return queue.tasks()
	}
}
