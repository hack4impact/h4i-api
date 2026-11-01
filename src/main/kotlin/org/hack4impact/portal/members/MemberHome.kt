package org.hack4impact.portal.members

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.WithheldReason
import org.hack4impact.portal.sync.WorldLoader
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A member's own page: who they are to H4I, their chapters, their accounts and the documents they signed (stage 1), and
 * their projects and what access they have and why (stage 2).
 */
data class MemberHome(
	val name: String,
	/** active, alumni, removal_requested, … */
	val status: String,
	val chapters: List<MyChapter>,
	val accounts: List<MyAccount>,
	val agreements: List<MyAgreement> = emptyList(),
	val projects: List<MyProject> = emptyList(),
	val access: List<MyAccess> = emptyList(),
)

data class MyProject(
	val chapterCode: String,
	val chapter: String,
	val slug: String,
	val name: String,
	/** draft, active or paused (closed projects aren't listed). */
	val status: String,
	val role: String,
	/** They hold a lead role on it (they manage its team). */
	val leads: Boolean,
	/** pending, signed or waived. */
	val agreementStatus: String,
	/** They can open the project's page: they lead it, or have a role in its chapter. */
	val canOpen: Boolean,
)

data class MyAccess(
	val tool: String,
	val name: String,
	/** A link to it in the tool, once it exists there. */
	val url: String?,
	/**
	 * ready, invited (accept the invite), setting_up (not done yet), after_agreement (sign first), agreement_not_set_up
	 * (waits for the project agreement, which national hasn't set up yet), not_connected (this portal doesn't sync the tool yet).
	 */
	val state: String,
	val access: String,
	/** Why they have it, in words: "On RISE DC", "Member of UMD", "Lead of UMD" (PRD: "each member's page shows why"). */
	val reasons: List<String>,
)

/** [role]: their chapter role (lead, co_lead, viewer), or null for a member. */
data class MyChapter(val code: String, val name: String, val role: String?)

/** One of their accounts: [login] is the address or username; [state] unverified, invited, accepted, confirmed, suspended or removed. */
data class MyAccount(val tool: String, val login: String?, val state: String)

/** A document they signed or were sent (Q52): [status] sent, signed, declined or expired; [hasPdf] the signed copy is kept, at /api/agreements/{id}/pdf. */
data class MyAgreement(
	val id: UUID,
	val name: String,
	val status: String,
	val signedAt: OffsetDateTime?,
	val hasPdf: Boolean,
	/** Stage 2: the project, for a project agreement. */
	val project: String? = null,
	/** Stage 2: Documenso can give a signing link now (`POST /api/me/agreements/{id}/sign`). */
	val canSign: Boolean = false,
)

/** Where to sign an agreement in Documenso. */
data class SignLink(val url: String)

@Component
class MemberHomeQueries(
	private val dsl: DSLContext,
	private val tools: ToolRegistry,
	private val worlds: WorldLoader,
	private val links: org.hack4impact.portal.adapters.ToolLinks,
	private val signing: org.hack4impact.portal.agreements.AgreementService,
	private val scope: org.hack4impact.portal.sync.SyncScope,
) {
	fun home(person: UUID, viewer: Viewer): MemberHome {
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(person)).fetchSingle()
		val chapters = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME).from(CHAPTER_MEMBERSHIP).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(person), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, CHAPTER.DELETED_AT.isNull).orderBy(CHAPTER.NAME).fetch()
			.map { MyChapter(it.value2()!!, it.value3()!!, viewer.chapterRoles[it.value1()]) }
		// Accounts of tools this app knows people have accounts in, in list order.
		val accounts = dsl.select(TOOL_ACCOUNT.TOOL, TOOL_ACCOUNT.EXTERNAL_LOGIN, TOOL_ACCOUNT.STATE).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(person)).fetch()
			.mapNotNull { a -> tools.find(a.value1())?.takeIf { it.accounts }?.let { it to MyAccount(it.key, a.value2(), a.value3()!!) } }
			.sortedBy { it.first.order }.map { it.second }
		val canRead = signing.canRead()
		val agreements = dsl.select(AGREEMENT.ID, AGREEMENT_TEMPLATE.NAME, AGREEMENT.STATUS, AGREEMENT.SIGNED_AT, AGREEMENT.PDF_LOCATION, PROJECT.NAME, AGREEMENT.DOCUMENSO_DOCUMENT_ID)
			.from(AGREEMENT).join(AGREEMENT_TEMPLATE).on(AGREEMENT_TEMPLATE.ID.eq(AGREEMENT.TEMPLATE_ID)).leftJoin(PROJECT).on(PROJECT.ID.eq(AGREEMENT.PROJECT_ID))
			.where(AGREEMENT.PERSON_ID.eq(person), AGREEMENT.STATUS.`in`("sent", "signed", "declined", "expired"))
			.orderBy(AGREEMENT.SIGNED_AT.desc().nullsLast(), AGREEMENT.CREATED_AT.desc()).fetch()
			.map { MyAgreement(it.value1()!!, it.value2()!!, it.value3()!!, it.value4(), it.value5() == "database", it.value6(), canRead && it.value3() == "sent" && it.value7() != null) }
		val projects = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, CHAPTER.CODE, CHAPTER.NAME, PROJECT.SLUG, PROJECT.NAME, PROJECT.STATUS, PROJECT_ROLE.NAME, PROJECT_ROLE.IS_LEAD, PROJECT_MEMBER.AGREEMENT_STATUS)
			.from(PROJECT_MEMBER).join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID)).join(CHAPTER).on(CHAPTER.ID.eq(PROJECT.CHAPTER_ID))
			.join(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID))
			.where(PROJECT_MEMBER.PERSON_ID.eq(person), PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT.STATUS.ne("closed"))
			.orderBy(PROJECT.NAME).fetch()
			.map {
				MyProject(it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!, it.value7()!!, it.value8()!!, it.value9() == true, it.value10()!!,
					viewer.canSee(it.value2()!!) || it.value1() in viewer.ledProjects)
			}
		return MemberHome("${p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName} ${p.lastName}", p.status!!, chapters, accounts, agreements, projects, access(person))
	}

	/**
	 * What [person] has and why (from the full portal's). [chapterId]: only that chapter's resources and national ones (a
	 * lead's view of a member); [own]: worded for the person themselves ("after you sign").
	 */
	fun access(person: UUID, chapterId: UUID? = null, own: Boolean = true): List<MyAccess> {
		val resolution = Resolver.resolve(worlds.load().world)
		val grants = resolution.grants.filter { it.personId == person }
		val waiting = resolution.withheld.filter { it.personId == person && it.why is WithheldReason.AwaitingAgreement }
		val ids = (grants.map { it.resourceId } + waiting.map { it.resourceId }).toSet()
		if (ids.isEmpty()) return emptyList()
		// Not what the portal leaves alone (unmanaged, or waiting for national): the sync won't give it (package 5 review R2).
		val untouched = scope.untouched()
		val resources = dsl.selectFrom(RESOURCE).where(RESOURCE.ID.`in`(ids)).fetch().associateBy { it.id!! }
			.filterValues { (chapterId == null || it.chapterId == null || it.chapterId == chapterId) && it.id !in untouched }
		val records = dsl.select(SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.STATUS).from(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(person), SYNC_RECORD.RESOURCE_ID.`in`(ids))
			.fetch().associate { it.value1()!! to it.value2()!! }
		val chapterNames = dsl.select(CHAPTER.ID, CHAPTER.NAME).from(CHAPTER).fetch().associate { it.value1()!! to it.value2()!! }
		val projectNames = dsl.select(PROJECT.ID, PROJECT.NAME).from(PROJECT).fetch().associate { it.value1()!! to it.value2()!! }
		fun why(r: Reason) = when (r) {
			is Reason.ChapterMember -> "Member of ${chapterNames[r.chapterId] ?: "the chapter"}"
			is Reason.ChapterLead -> "Lead of ${chapterNames[r.chapterId] ?: "the chapter"}"
			is Reason.ProjectMember -> "On ${projectNames[r.projectId] ?: "a project"}"
			// No automation rules until stage 3.
			is Reason.FromRule, is Reason.NationalDefault -> "A rule"
		}
		val granted = grants.mapNotNull { g ->
			val r = resources[g.resourceId] ?: return@mapNotNull null
			val state = when {
				!scope.connected(r.tool!!) -> "not_connected"
				records[g.resourceId] == "in_sync" -> "ready"
				records[g.resourceId] == "waiting_on_member" -> "invited"
				else -> "setting_up"
			}
			MyAccess(r.tool!!, r.name!!, links.url(r.tool!!, r.name!!, r.externalId), state, g.access.name.lowercase(), g.reasons.map(::why))
		}
		val later = waiting.distinctBy { it.resourceId }.mapNotNull { w ->
			val r = resources[w.resourceId] ?: return@mapNotNull null
			val project = (w.why as WithheldReason.AwaitingAgreement).projectId
			// The level the project gives it (package 5 review R7): the resolver's withheld grants carry none.
			val level = dsl.select(PROJECT_RESOURCE.ACCESS_LEVEL).from(PROJECT_RESOURCE)
				.where(PROJECT_RESOURCE.PROJECT_ID.eq(project), PROJECT_RESOURCE.RESOURCE_ID.eq(w.resourceId)).fetchOne()?.value1() ?: "write"
			// Nothing to sign yet when theirs wasn't sent and can't be (package 6 review R1, per person: package 8 review R3).
			if (!signing.agreementExpected(person, project)) MyAccess(r.tool!!, r.name!!, null, "agreement_not_set_up", level, listOf("After the ${projectNames[project] ?: "project"} agreement, which isn't set up yet"))
			else MyAccess(r.tool!!, r.name!!, null, "after_agreement", level, listOf("After ${if (own) "you sign" else "they sign"} the ${projectNames[project] ?: "project"} agreement"))
		}
		return (granted + later).sortedWith(compareBy({ tools.find(it.tool)?.order ?: 99 }, { it.name }))
	}
}

@RestController
@RequestMapping("/api/me")
class MemberHomeController(
	private val viewers: Viewers,
	private val queries: MemberHomeQueries,
	private val agreements: org.hack4impact.portal.agreements.AgreementService,
	private val dsl: DSLContext,
) {
	@GetMapping("/home")
	fun home(@AuthenticationPrincipal user: OidcUser): MemberHome {
		val viewer = viewers.of(user)
		val person = viewer.personId ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Your Google account isn't linked to a member yet")
		return queries.home(person, viewer)
	}

	/** Stage 2: a fresh Documenso signing link for one of their own unsigned agreements (the project agreement's page). */
	@PostMapping("/agreements/{id}/sign")
	fun sign(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID): SignLink {
		val person = viewers.of(user).personId ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Your Google account isn't linked to a member yet")
		val agreement = dsl.selectFrom(AGREEMENT).where(AGREEMENT.ID.eq(id), AGREEMENT.PERSON_ID.eq(person)).fetchOne()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such agreement of yours")
		return SignLink(agreements.signingUrl(agreement) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "It can't be signed here right now; use the link in your email"))
	}
}
