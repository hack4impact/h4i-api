package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.national.AdminQueue
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A resource the portal was about to create whose name its tool already has (wiki: staged-rollout-stage-2, linking
 * existing resources by hand). Instead of retrying, the portal records it and asks national once, who links it,
 * renames the portal's or leaves it unmanaged. Until then the planner leaves the resource alone.
 */
@Component
class ExistingResources(private val dsl: DSLContext, private val queue: AdminQueue) {
	companion object {
		/** Names compared as the naming convention does: case, a leading #, spaces and underscores don't matter. */
		fun normalize(s: String) = s.trim().trimStart('#').lowercase().replace(Regex("[\\s_]+"), "-").trim('-')

		/** The resource in a tool's [listing] with the portal resource's [name], if any. A Google group is its address. */
		fun find(tool: Tool, name: String, listing: List<ToolResource>): ToolResource? {
			val wanted = normalize(name)
			return listing.firstOrNull { r ->
				when (tool) {
					Tool.GOOGLE -> normalize(r.externalId) == wanted
					Tool.GITHUB -> normalize(r.externalId) == wanted || normalize(r.name) == wanted
					else -> normalize(r.name) == wanted
				}
			}
		}

		/** Named as the portal names what it creates: a Google group by its address, a GitHub team by its slug, others by their name. */
		fun nameOf(tool: Tool, found: ToolResource) = if (tool == Tool.GOOGLE || tool == Tool.GITHUB) found.externalId else found.name

		/**
		 * A create the tool refused because the name is taken: Google's 409, GitHub's 422, Slack's name_taken (often a
		 * private channel the portal's app can't see).
		 */
		fun alreadyExists(e: AdapterException): Boolean =
			e is Rejected && (e.status == 409 || (e.status == 422 && e.tool == Tool.GITHUB) || "name_taken" in e.message.orEmpty() || "already exists" in e.message.orEmpty())
	}

	/**
	 * Records that [name] exists in [tool] ([foundExternalId] when the listing showed it) and opens national's task.
	 * Returns false when it was already recorded.
	 */
	fun record(tool: Tool, resourceId: UUID, name: String, foundExternalId: String?): Boolean {
		val updated = dsl.update(RESOURCE)
			.set(RESOURCE.EXISTS_IN_TOOL_AT, OffsetDateTime.now()).set(RESOURCE.FOUND_EXTERNAL_ID, foundExternalId)
			.where(RESOURCE.ID.eq(resourceId), RESOURCE.EXISTS_IN_TOOL_AT.isNull).execute()
		if (updated == 0) return false
		queue.resourceExists(tool, resourceId, name, foundExternalId != null)
		return true
	}
}
