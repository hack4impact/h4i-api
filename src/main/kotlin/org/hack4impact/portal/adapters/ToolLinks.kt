package org.hack4impact.portal.adapters

import org.hack4impact.portal.tools.ToolRegistry
import org.springframework.stereotype.Component

/** Where to open a resource in its tool, once it exists there (member page, project page). */
@Component
class ToolLinks(private val adapters: AdapterProperties, private val tools: ToolRegistry) {
	/** Each tool's descriptor builds its link (tool registry). */
	fun url(tool: String, name: String, externalId: String?): String? {
		externalId ?: return null
		return tools.find(tool)?.link?.invoke(adapters, name, externalId)
	}
}
