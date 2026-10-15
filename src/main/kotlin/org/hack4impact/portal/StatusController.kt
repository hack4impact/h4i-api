package org.hack4impact.portal

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class StatusResponse(val service: String, val version: String)

/** Hello-world endpoint used to confirm a deploy reached staging (build plan step 1). */
@RestController
class StatusController(
	@Value("\${spring.application.name}") private val service: String,
	@Value("\${portal.version}") private val version: String,
) {
	@GetMapping("/api/status")
	fun status() = StatusResponse(service, version)
}
