package org.hack4impact.portal.memberdb

import org.hack4impact.portal.db.tables.references.MEMBER_DB_WRITE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Asks for a person's record to be written to the member database (wiki: staged-rollout). Called inside the change's
 * own transaction, so the request commits with it or not at all; [MemberDbWriter] does the writing, with retries.
 * Asking again for someone already waiting or written just puts them back in line, with a fresh count.
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
}
