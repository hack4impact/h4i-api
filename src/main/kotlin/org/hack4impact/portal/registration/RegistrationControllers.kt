package org.hack4impact.portal.registration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.chapters.ChapterAccess
import org.hack4impact.portal.db.tables.references.APPLICANT_SESSION
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
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
import java.util.UUID

/**
 * The applicant API (PRD: what an applicant session can reach): verify a school email, open a claim or roster link,
 * link GitHub, save and submit their own registration, and check its status. No member, project or chapter data.
 */
@RestController
@RequestMapping("/api/apply")
class ApplyController(
	private val registrations: RegistrationService,
	private val sessions: ApplicantSessions,
	private val github: GitHubOAuth,
	private val dsl: DSLContext,
	@Value("\${portal.base-url}") private val baseUrl: String,
	@Value("\${portal.frontend-url}") private val frontendUrl: String,
) {
	private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
	private val callback get() = "${baseUrl.trimEnd('/')}/api/apply/github/callback"

	@GetMapping("/chapters/{code}")
	fun chapter(@PathVariable code: String): ApplyChapter = registrations.chapter(code)

	@PostMapping("/chapters/{code}/code")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun code(@PathVariable code: String, @RequestBody input: CodeRequest, request: HttpServletRequest) =
		registrations.sendCode(code, input.email, request.remoteAddr)

	@PostMapping("/chapters/{code}/verify")
	fun verify(@PathVariable code: String, @RequestBody input: VerifyRequest, request: HttpServletRequest, response: HttpServletResponse): ApplicantView {
		val registration = registrations.verifyCode(code, input.email, input.code, request.remoteAddr)
		sessions.start(registration, response)
		return registrations.view(registration, github.configured)
	}

	@PostMapping("/claim")
	fun claim(@RequestBody input: TokenRequest, request: HttpServletRequest, response: HttpServletResponse): ApplicantView {
		val registration = registrations.openClaim(input.token, request.remoteAddr)
		sessions.start(registration, response)
		return registrations.view(registration, github.configured)
	}

	@PostMapping("/roster")
	fun roster(@RequestBody input: TokenRequest, request: HttpServletRequest, response: HttpServletResponse): ApplicantView {
		val registration = registrations.openRoster(input.token, request.remoteAddr)
		sessions.start(registration, response)
		return registrations.view(registration, github.configured)
	}

	@GetMapping("/me")
	fun me(@AuthenticationPrincipal applicant: Applicant): ApplicantView = registrations.view(applicant.registrationId, github.configured)

	@PutMapping("/me")
	fun save(@AuthenticationPrincipal applicant: Applicant, @RequestBody input: DetailsInput): ApplicantView {
		registrations.save(applicant.registrationId, input)
		return registrations.view(applicant.registrationId, github.configured)
	}

	@PostMapping("/me/agreements/{kind}")
	fun startAgreement(@AuthenticationPrincipal applicant: Applicant, @PathVariable kind: String): AgreementStart =
		registrations.startAgreement(applicant.registrationId, kind)

	@PostMapping("/me/submit")
	fun submit(@AuthenticationPrincipal applicant: Applicant): ApplicantView {
		registrations.submit(applicant.registrationId)
		return registrations.view(applicant.registrationId, github.configured)
	}

	/** Leaves for GitHub; it comes back to [callback] with the state checked against this session. */
	@GetMapping("/github/start")
	fun githubStart(@AuthenticationPrincipal applicant: Applicant, response: HttpServletResponse) {
		if (!github.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub sign-in isn't set up yet")
		val state = Tokens.new()
		dsl.update(APPLICANT_SESSION).set(APPLICANT_SESSION.GITHUB_STATE, state).where(APPLICANT_SESSION.ID.eq(applicant.sessionId)).execute()
		response.sendRedirect(github.authorizeUrl(state, callback))
	}

	@GetMapping("/github/callback")
	fun githubCallback(
		@AuthenticationPrincipal applicant: Applicant,
		@RequestParam(required = false) code: String?,
		@RequestParam(required = false) state: String?,
		response: HttpServletResponse,
	) {
		val expected = dsl.select(APPLICANT_SESSION.GITHUB_STATE).from(APPLICANT_SESSION).where(APPLICANT_SESSION.ID.eq(applicant.sessionId)).fetchSingle().value1()
		dsl.update(APPLICANT_SESSION).setNull(APPLICANT_SESSION.GITHUB_STATE).where(APPLICANT_SESSION.ID.eq(applicant.sessionId)).execute()
		val ok = code != null && state != null && expected != null && Tokens.same(state, expected) && runCatching {
			registrations.linkGitHub(applicant.registrationId, github.user(code, callback))
		}.onFailure { log.warn("GitHub sign-in for registration {} failed: {}", applicant.registrationId, it.message) }.isSuccess
		response.sendRedirect("${frontendUrl.trimEnd('/')}/apply" + if (ok) "" else "?github=failed")
	}

	@PostMapping("/signout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun signOut(@AuthenticationPrincipal applicant: Applicant, response: HttpServletResponse) {
		sessions.revoke(applicant.registrationId)
		sessions.clear(response)
	}
}

/** Lead side: approvals, chapter questions, roster invites (PRD: chapter lead approves registrations). */
@RestController
@RequestMapping("/api/chapters/{code}")
class RegistrationController(
	private val access: ChapterAccess,
	private val registrations: RegistrationService,
	private val sessions: ApplicantSessions,
) {
	@GetMapping("/registrations")
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<RegistrationSummary> =
		registrations.registrations(access.read(user, code).second.id)

	@PostMapping("/registrations/{id}/approve")
	fun approve(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: ApproveInput): List<RegistrationSummary> {
		val (viewer, chapter) = access.manage(user, code)
		registrations.approve(chapter.id, id, input, viewer.personId)
		return registrations.registrations(chapter.id)
	}

	@PostMapping("/registrations/{id}/decline")
	fun decline(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: DeclineInput): List<RegistrationSummary> {
		val (viewer, chapter) = access.manage(user, code)
		registrations.decline(chapter.id, id, input.reason, viewer.personId, sessions)
		return registrations.registrations(chapter.id)
	}

	@GetMapping("/registration-settings")
	fun settings(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): RegistrationSettings {
		val (viewer, chapter) = access.read(user, code)
		return registrations.settings(chapter.id, viewer.canManage(chapter.id), viewer.nationalAdmin)
	}

	@PutMapping("/registration-settings")
	fun saveSettings(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: RegistrationSettingsInput): RegistrationSettings {
		val (viewer, chapter) = access.manage(user, code)
		registrations.saveSettings(chapter.id, input, viewer.nationalAdmin, viewer.personId)
		return registrations.settings(chapter.id, true, viewer.nationalAdmin)
	}

	@GetMapping("/roster")
	fun roster(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<RosterInviteView> =
		registrations.rosterInvites(access.read(user, code).second.id)

	@PostMapping("/roster")
	fun upload(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: RosterInput): RosterResult {
		val (viewer, chapter) = access.manage(user, code)
		return registrations.roster(chapter.id, input.csv, viewer.personId)
	}
}

/** National: claim links for imported members (PRD: existing members at launch). */
@RestController
@RequestMapping("/api/national/claims")
class ClaimController(private val viewers: Viewers, private val registrations: RegistrationService) {
	private fun national(user: OidcUser) = viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping("/preview")
	fun preview(@AuthenticationPrincipal user: OidcUser, @RequestParam(required = false) chapter: String?): ClaimPreview {
		national(user)
		return registrations.claimPreview(chapter)
	}

	@PostMapping("/send")
	fun send(@AuthenticationPrincipal user: OidcUser, @RequestBody input: ClaimSend): ClaimPreview {
		val viewer = national(user)
		registrations.sendClaims(input.chapter, input.limit, viewer.personId)
		return registrations.claimPreview(input.chapter)
	}

	/** One person's claim link, sent again or for the first time; any earlier link of theirs stops working. */
	@PostMapping("/people/{personId}")
	fun sendOne(@AuthenticationPrincipal user: OidcUser, @PathVariable personId: java.util.UUID): ClaimSent {
		val viewer = national(user)
		return registrations.sendClaim(personId, viewer.personId)
	}
}
