package org.hack4impact.portal

import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly clean-up of rows that are only useful for a while (code review L11): old dry-run plans and sync notes,
 * finished outbox events, and spent email codes and applicant sessions. Applied changes, sync records and the audit
 * log are kept.
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
		// Dry runs' plans and every run's notes (drift, unknown accounts, missing resources) after 30 days.
		"syncNotes" to dsl.execute(
			"""
			DELETE FROM portal.sync_change c USING portal.sync_run r
			WHERE c.run_id = r.id AND r.started_at < now() - interval '30 days'
			  AND (r.mode = 'dry_run' OR c.kind IN ('drift', 'unmatched_account', 'missing_resource'))
			""".trimIndent(),
		),
		"finishedEvents" to dsl.execute("DELETE FROM portal.event_publication WHERE completion_date < now() - interval '7 days'"),
		"emailCodes" to dsl.execute("DELETE FROM portal.email_code WHERE expires_at < now() - interval '1 day'"),
		"applicantSessions" to dsl.execute("DELETE FROM portal.applicant_session WHERE expires_at < now() - interval '1 day' OR revoked_at < now() - interval '1 day'"),
	)
}
