package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext

/**
 * Empties every portal table, for tests that need a clean database, then puts back what the migrations seed (V4's
 * national resource template; the truncate would take it with `chapter` through its foreign key anyway) and what
 * the app adds at startup (each syncing tool's settings row, in dry run, as `ToolSettingsBootstrap` does).
 */
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
	// Same rows as V4__projects.sql.
	execute(
		"""
		INSERT INTO portal.resource_template (position, tool, name_pattern, access_level, requires_agreement, tags) VALUES
			(1, 'slack', '{chapter}-{project}', 'write', false, '{main}'),
			(2, 'google', '{chapter}-{project}@hack4impact.org', 'write', false, '{main}'),
			(3, 'github', '{chapter}-{project}', 'write', true, '{eng}'),
			(4, 'vaultwarden', '{chapter}-{project}', 'write', true, '{eng}')
		""".trimIndent(),
	)
	ToolRegistry.BUILT_IN.filter { it.syncs }.forEach { insertInto(TOOL_SETTING).set(TOOL_SETTING.TOOL, it.key).execute() }
}
