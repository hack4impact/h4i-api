package org.hack4impact.portal.chapters

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.CLAIM_LINK
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/** Read-only chapter queries. Every caller checks the viewer's role first; nothing here filters by viewer. */
@Component
class ChapterQueries(private val dsl: DSLContext) {

	data class Chapter(val id: UUID, val code: String, val name: String, val status: String)

	fun byCode(code: String): Chapter? =
		dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME, CHAPTER.STATUS).from(CHAPTER)
			.where(CHAPTER.CODE.eq(code.lowercase()), CHAPTER.DELETED_AT.isNull)
			.fetchOne { Chapter(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }

	fun visibleTo(viewer: Viewer): List<ChapterRef> {
		val all = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME).from(CHAPTER).where(CHAPTER.DELETED_AT.isNull).orderBy(CHAPTER.NAME).fetch()
		return all.mapNotNull { c ->
			val role = viewer.chapterRoles[c.value1()] ?: if (viewer.nationalAdmin) "national" else null
			role?.let { ChapterRef(c.value1()!!, c.value2()!!, c.value3()!!, it) }
		}
	}

	fun stats(chapterId: UUID): ChapterStats {
		val members = dsl.select(PERSON.STATUS).from(PERSON)
			.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
			.where(CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, PERSON.DELETED_AT.isNull)
			.fetch(PERSON.STATUS)
		return ChapterStats(
			activeMembers = members.count { it == "active" || it == "removal_requested" },
			alumni = members.count { it == "alumni" },
			waitingApplications = dsl.fetchCount(REGISTRATION, REGISTRATION.CHAPTER_ID.eq(chapterId).and(REGISTRATION.STATUS.eq("submitted"))),
			leads = dsl.fetchCount(CHAPTER_ROLE, CHAPTER_ROLE.CHAPTER_ID.eq(chapterId).and(CHAPTER_ROLE.ROLE.`in`("lead", "co_lead")).and(CHAPTER_ROLE.ENDS_AT.isNull)),
		)
	}

	fun isMember(chapterId: UUID, personId: UUID): Boolean =
		dsl.fetchExists(CHAPTER_MEMBERSHIP, CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.PERSON_ID.eq(personId))

	fun members(chapterId: UUID): List<MemberRow> {
		val people = dsl.select(
			PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL,
			PERSON.STATUS, PERSON.KIND, PERSON.CLAIMED_AT, PERSON.SOURCE_ID, PERSON.PERSONAL_EMAIL,
		).from(PERSON)
			.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
			.where(CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, PERSON.DELETED_AT.isNull)
			.fetch()
		val ids = people.map { it.get(PERSON.ID)!! }
		val roles = dsl.select(CHAPTER_ROLE.PERSON_ID, CHAPTER_ROLE.ROLE, CHAPTER_ROLE.TITLE).from(CHAPTER_ROLE)
			.where(CHAPTER_ROLE.CHAPTER_ID.eq(chapterId), CHAPTER_ROLE.ENDS_AT.isNull, CHAPTER_ROLE.PERSON_ID.`in`(ids))
			.fetch().associateBy { it.value1()!! }
		val openLinks = dsl.select(CLAIM_LINK.PERSON_ID, org.jooq.impl.DSL.max(CLAIM_LINK.CREATED_AT)).from(CLAIM_LINK)
			.where(CLAIM_LINK.PERSON_ID.`in`(ids), CLAIM_LINK.USED_AT.isNull, CLAIM_LINK.EXPIRES_AT.gt(java.time.OffsetDateTime.now()))
			.groupBy(CLAIM_LINK.PERSON_ID).fetch().associate { it.value1()!! to it.value2()!! }
		val accounts = dsl.select(TOOL_ACCOUNT.PERSON_ID, TOOL_ACCOUNT.TOOL, TOOL_ACCOUNT.STATE).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.`in`(ids))
			.fetch().groupBy({ it.value1()!! }, { AccountSummary(it.value2()!!, it.value3()!!) })
		return people.map { p ->
			val id = p.get(PERSON.ID)!!
			val first = p.get(PERSON.PREFERRED_NAME)?.takeIf { it.isNotBlank() } ?: p.get(PERSON.FIRST_NAME)!!
			MemberRow(
				personId = id,
				name = "$first ${p.get(PERSON.LAST_NAME)}",
				email = p.get(PERSON.ORG_EMAIL) ?: p.get(PERSON.SCHOOL_EMAIL),
				status = p.get(PERSON.STATUS)!!,
				kind = p.get(PERSON.KIND)!!,
				chapterRole = roles[id]?.value2(),
				title = roles[id]?.value3(),
				accounts = accounts[id].orEmpty().sortedBy { it.tool },
				// Someone who joined through the portal has confirmed their details too; only imported records wait for a claim.
				claimed = p.get(PERSON.CLAIMED_AT) != null || p.get(PERSON.SOURCE_ID) == null,
				claimable = p.get(PERSON.SOURCE_ID) != null && p.get(PERSON.CLAIMED_AT) == null && p.get(PERSON.STATUS) != "removed" &&
					listOfNotNull(p.get(PERSON.ORG_EMAIL), p.get(PERSON.SCHOOL_EMAIL), p.get(PERSON.PERSONAL_EMAIL)).any { it.isNotBlank() },
				claimLinkSentAt = openLinks[id],
			)
		}.sortedWith(compareBy({ it.chapterRole == null }, { it.status != "active" }, { it.name.lowercase() }))
	}
}
