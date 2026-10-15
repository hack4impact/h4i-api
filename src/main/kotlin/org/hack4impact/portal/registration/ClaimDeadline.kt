package org.hack4impact.portal.registration

import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CLAIM_LINK
import org.hack4impact.portal.db.tables.references.PERSON
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * PRD: records not claimed by the deadline are marked alumni; decision 39: one month after their first claim link.
 * Runs daily at 04:00 New York time when `portal.claims.deadline-enabled` is true (off until claims are sent for real).
 */
@Component
class ClaimDeadline(
	private val dsl: DSLContext,
	@Value("\${portal.claims.deadline-enabled:false}") private val enabled: Boolean,
	@Value("\${portal.claims.deadline-days:30}") private val days: Long,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(cron = "0 0 4 * * *", zone = "America/New_York")
	fun daily() {
		if (enabled) run()
	}

	/** Returns how many records became alumni. */
	fun run(): Int {
		val firstLink = DSL.select(DSL.min(CLAIM_LINK.CREATED_AT)).from(CLAIM_LINK).where(CLAIM_LINK.PERSON_ID.eq(PERSON.ID)).asField<OffsetDateTime>()
		val ids = dsl.select(PERSON.ID).from(PERSON)
			.where(PERSON.SOURCE_ID.isNotNull, PERSON.CLAIMED_AT.isNull, PERSON.STATUS.eq("active"), firstLink.lt(OffsetDateTime.now().minusDays(days)))
			.fetch().map { it.value1()!! }
		if (ids.isEmpty()) return 0
		dsl.update(PERSON).set(PERSON.STATUS, "alumni").set(PERSON.UPDATED_AT, OffsetDateTime.now()).where(PERSON.ID.`in`(ids)).execute()
		dsl.insertInto(AUDIT_EVENT).set(AUDIT_EVENT.ACTOR_TYPE, "system").set(AUDIT_EVENT.ACTION, "claims.deadline").set(AUDIT_EVENT.TARGET_TYPE, "person")
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"alumni":${ids.size},"days":$days}""")).execute()
		log.info("Claim deadline: {} unclaimed records became alumni", ids.size)
		return ids.size
	}
}
