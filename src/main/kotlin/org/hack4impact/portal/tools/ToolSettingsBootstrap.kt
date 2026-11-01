package org.hack4impact.portal.tools

import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * At startup: every tool the sync manages has its settings row (a new one starts enabled and in dry run; V4 seeds
 * none), and any tool name in the data that no tool claims is logged (the database only checks the format).
 * Runs once every bean exists (Flyway has migrated) and before the context starts scheduled tasks and event
 * listeners, so no sync can run without a new tool's row.
 */
@Component
class ToolSettingsBootstrap(private val dsl: DSLContext, private val tools: ToolRegistry) : SmartInitializingSingleton {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun afterSingletonsInstantiated() {
		val added = tools.syncTools.sumOf { dsl.insertInto(TOOL_SETTING).set(TOOL_SETTING.TOOL, it.key).onConflictDoNothing().execute() }
		if (added > 0) log.info("Tool settings: added {} for newly registered tools (in dry run)", added)
		val known = tools.all.map { it.key }.toSet()
		val unknown = (dsl.selectDistinct(TOOL_SETTING.TOOL).from(TOOL_SETTING).fetch(TOOL_SETTING.TOOL) +
			dsl.selectDistinct(RESOURCE.TOOL).from(RESOURCE).fetch(RESOURCE.TOOL)).filterNotNull().toSet() - known
		if (unknown.isNotEmpty()) log.warn("Tools in the data that no registered tool claims: {}", unknown.sorted())
	}
}
