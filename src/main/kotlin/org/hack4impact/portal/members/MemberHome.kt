package org.hack4impact.portal.members

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.tools.Tool
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/** A member's own page (stage 1): who they are to H4I, their chapters, their accounts and the documents they signed. */
data class MemberHome(
	val name: String,
	/** active, alumni, removal_requested, … */
	val status: String,
	val chapters: List<MyChapter>,
	val accounts: List<MyAccount>,
	val agreements: List<MyAgreement> = emptyList(),
)

/** [role]: their chapter role (lead, co_lead, viewer), or null for a member. */
data class MyChapter(val code: String, val name: String, val role: String?)

/** One of their accounts: [login] is the address or username; [state] unverified, invited, accepted, confirmed, suspended or removed. */
data class MyAccount(val tool: String, val login: String?, val state: String)

/** A document they signed or were sent (Q52): [status] sent, signed, declined or expired; [hasPdf] the signed copy is kept, at /api/agreements/{id}/pdf. */
data class MyAgreement(val id: UUID, val name: String, val status: String, val signedAt: OffsetDateTime?, val hasPdf: Boolean)

@Component
class MemberHomeQueries(private val dsl: DSLContext) {
	fun home(person: UUID, viewer: Viewer): MemberHome {
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(person)).fetchSingle()
		val chapters = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME).from(CHAPTER_MEMBERSHIP).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(person), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, CHAPTER.DELETED_AT.isNull).orderBy(CHAPTER.NAME).fetch()
			.map { MyChapter(it.value2()!!, it.value3()!!, viewer.chapterRoles[it.value1()]) }
		// Accounts of tools this app knows, in list order.
		val accounts = dsl.select(TOOL_ACCOUNT.TOOL, TOOL_ACCOUNT.EXTERNAL_LOGIN, TOOL_ACCOUNT.STATE).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(person)).fetch()
			.mapNotNull { a -> Tool.fromKey(a.value1())?.let { it to MyAccount(it.key, a.value2(), a.value3()!!) } }
			.sortedBy { it.first.order }.map { it.second }
		val agreements = dsl.select(AGREEMENT.ID, AGREEMENT_TEMPLATE.NAME, AGREEMENT.STATUS, AGREEMENT.SIGNED_AT, AGREEMENT.PDF_LOCATION)
			.from(AGREEMENT).join(AGREEMENT_TEMPLATE).on(AGREEMENT_TEMPLATE.ID.eq(AGREEMENT.TEMPLATE_ID))
			.where(AGREEMENT.PERSON_ID.eq(person), AGREEMENT.STATUS.`in`("sent", "signed", "declined", "expired"))
			.orderBy(AGREEMENT.SIGNED_AT.desc().nullsLast(), AGREEMENT.CREATED_AT.desc()).fetch()
			.map { MyAgreement(it.value1()!!, it.value2()!!, it.value3()!!, it.value4(), it.value5() == "database") }
		return MemberHome("${p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName} ${p.lastName}", p.status!!, chapters, accounts, agreements)
	}
}

@RestController
@RequestMapping("/api/me")
class MemberHomeController(private val viewers: Viewers, private val queries: MemberHomeQueries) {
	@GetMapping("/home")
	fun home(@AuthenticationPrincipal user: OidcUser): MemberHome {
		val viewer = viewers.of(user)
		val person = viewer.personId ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Your Google account isn't linked to a member yet")
		return queries.home(person, viewer)
	}
}
