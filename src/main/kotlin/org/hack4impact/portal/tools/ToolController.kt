package org.hack4impact.portal.tools

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A tool's name and place in lists, for showing people's accounts. */
data class ToolInfo(val key: String, val name: String, val order: Int)

@RestController
class ToolController(private val tools: ToolRegistry) {
	/** Every tool a person can have an account in, in list order. */
	@GetMapping("/api/tools")
	fun tools(): List<ToolInfo> = tools.accountTools.map { ToolInfo(it.key, it.name, it.order) }
}
