package org.hack4impact.portal

import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly clean-up of rows that are only useful for a while (code review L11): finished outbox events, and spent email
 * codes and applicant sessions. The audit log is kept.
 */
@Component
class Housekeeping(private val dsl: DSLContext) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(cron = "\${portal.housekeeping.cron:0 30 4 * * *}", zone = "America/New_York")
	fun nightly() {
		val counts = clean()
		if (counts.values.any { it > 0 }) log.info("Housekeeping: {}", counts)
	}

	fun clean(): Map<String, Int> = linkedMapOf(
		"finishedEvents" to dsl.execute("DELETE FROM portal.event_publication WHERE completion_date < now() - interval '7 days'"),
		"emailCodes" to dsl.execute("DELETE FROM portal.email_code WHERE expires_at < now() - interval '1 day'"),
		"applicantSessions" to dsl.execute("DELETE FROM portal.applicant_session WHERE expires_at < now() - interval '1 day' OR revoked_at < now() - interval '1 day'"),
	)