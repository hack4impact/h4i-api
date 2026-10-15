package org.hack4impact.portal

import org.jooq.DSLContext

/** Empties every portal table, for tests that need a clean database (the migration seeds nothing). */
fun DSLContext.emptyPortalTables() {
	val keep = setOf("flyway_schema_history")
	val tables = fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal'").map { it.get(0, String::class.java) } - keep
	// A background listener from the previous test (an invite's email) may still be writing: try again.
	var attempt = 0
	while (true) {
		try {
			execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
			break
		} catch (e: org.springframework.dao.PessimisticLockingFailureException) {
			if (++attempt >= 5) throw e
			Thread.sleep(200L * attempt)
		}
	}
}
