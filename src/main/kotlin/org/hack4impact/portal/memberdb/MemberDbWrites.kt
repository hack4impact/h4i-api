package org.hack4impact.portal.memberdb

import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_LEADERSHIP_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_PROJECT_WRITE
import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Asks for records to be written to the member database (wiki: staged-rollout; stage 2 adds projects and chapter leads).
 * Called inside the change's own transaction, so the request commits with it or not at all; [MemberDbWriter] and
 * [MemberDbProjectWriter] do the writing, with retries. Asking again for something already waiting or written just puts
 * it back in line, with a fresh count.
 */
@Component
class MemberDbWrites(private val dsl: DSLContext) {
	fun request(person: UUID) {
		val now = OffsetDateTime.now()
		dsl.insertInto(MEMBER_DB_WRITE).set(MEMBER_DB_WRITE.PERSON_ID, person).set(MEMBER_DB_WRITE.REQUESTED_AT, now)
			.onConflict(MEMBER_DB_WRITE.PERSON_ID).doUpdate()
			.set(MEMBER_DB_WRITE.STATE, "pending").set(MEMBER_DB_WRITE.REQUESTED_AT, now).set(MEMBER_DB_WRITE.ATTEMPTS, 0)
			.setNull(MEMBER_DB_WRITE.NEXT_TRY_AT).setNull(MEMBER_DB_WRITE.LAST_ERROR)
			.execute()
	}

	/** A project's projects row, engagement and team (stage 2): after any change to the project or its team. */
	fun requestProject(project: UUID) {
		val now = OffsetDateTime.now()
		dsl.insertInto(MEMBER_DB_PROJECT_WRITE).set(MEMBER_DB_PROJECT_WRITE.PROJECT_ID, project).set(MEMBER_DB_PROJECT_WRITE.REQUESTED_AT, now)
			.onConflict(MEMBER_DB_PROJECT_WRITE.PROJECT_ID).doUpdate()
			.set(MEMBER_DB_PROJECT_WRITE.STATE, "pending").set(MEMBER_DB_PROJECT_WRITE.REQUESTED_AT, now).set(MEMBER_DB_PROJECT_WRITE.ATTEMPTS, 0)
			.setNull(MEMBER_DB_PROJECT_WRITE.NEXT_TRY_AT).setNull(MEMBER_DB_PROJECT_WRITE.LAST_ERROR).setNull(MEMBER_DB_PROJECT_WRITE.WAITING_ON)
			.execute()
	}

	/** A chapter lead's or co-lead's leadership assignment (decision 171): when the role is given or ended. Viewers aren't written. */
	fun requestLeadership(chapterRole: UUID) {
		val now = OffsetDateTime.now()
		dsl.insertInto(MEMBER_DB_LEADERSHIP_WRITE).set(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID, chapterRole).set(MEMBER_DB_LEADERSHIP_WRITE.REQUESTED_AT, now)
			.onConflict(MEMBER_DB_LEADERSHIP_WRITE.CHAPTER_ROLE_ID).doUpdate()
			.set(MEMBER_DB_LEADERSHIP_WRITE.STATE, "pending").set(MEMBER_DB_LEADERSHIP_WRITE.REQUESTED_AT, now).set(MEMBER_DB_LEADERSHIP_WRITE.ATTEMPTS, 0)
			.setNull(MEMBER_DB_LEADERSHIP_WRITE.NEXT_TRY_AT).setNull(MEMBER_DB_LEADERSHIP_WRITE.LAST_ERROR).setNull(MEMBER_DB_LEADERSHIP_WRITE.WAITING_ON)
			.execute()
	}

	/** Every open lead and co-lead role, when national first turns project writes on (decision 171: the backfill). Returns how many. */
	fun requestAllLeadership(): Int {
		val roles = dsl.select(CHAPTER_ROLE.ID).from(CHAPTER_ROLE).where(CHAPTER_ROLE.ROLE.`in`("lead", "co_lead"), CHAPTER_ROLE.ENDS_AT.isNull)
			.fetch(CHAPTER_ROLE.ID).filterNotNull()
		roles.forEach(::requestLeadership)
		return roles.size
	}
}
