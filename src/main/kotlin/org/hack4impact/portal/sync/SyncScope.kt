package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What the sync acts on, as one rule for the engine, the member's page and the summary email (package 5 review R2): the
 * resolver grants every resource the world has, but the portal leaves some alone, and a tool with no adapter isn't
 * synced at all. Nobody should be promised access the sync won't give.
 */
@Component
class SyncScope(private val dsl: DSLContext, private val adapters: List<ReadAdapter>) {
	/**
	 * Resources the portal doesn't touch: left unmanaged, or waiting for national to decide about a name the tool already
	 * has (package 4). [tool]: only that tool's.
	 */
	fun untouched(tool: String? = null): Set<UUID> =
		dsl.select(RESOURCE.ID).from(RESOURCE)
			.where(
				tool?.let { RESOURCE.TOOL.eq(it) } ?: DSL.noCondition(),
				RESOURCE.MANAGED.eq("unmanaged").or(RESOURCE.EXISTS_IN_TOOL_AT.isNotNull.and(RESOURCE.EXTERNAL_ID.isNull)),
			)
			.fetch(RESOURCE.ID).filterNotNull().toSet()

	/** Whether this deployment syncs [tool] at all: it has the tool's adapter (its `PORTAL_ADAPTERS_<TOOL>_ENABLED`). */
	fun connected(tool: String): Boolean = adapters.any { it.tool.name.equals(tool, ignoreCase = true) }
}
