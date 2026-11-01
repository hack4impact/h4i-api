package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Tool
import java.time.Duration

/** How a call to another service failed, the same way for every service, so callers can retry or report without knowing it. */
sealed class AdapterException(val tool: Tool, message: String, cause: Throwable? = null) : RuntimeException("$tool: $message", cause) {
	abstract val retryable: Boolean
}

class RateLimited(tool: Tool, val retryAfter: Duration) : AdapterException(tool, "rate limited, retry after ${retryAfter.seconds}s") {
	override val retryable = true
}

class Unavailable(tool: Tool, message: String, cause: Throwable? = null) : AdapterException(tool, message, cause) {
	override val retryable = true
}

/** Bad or revoked credentials, or missing permissions. Retrying won't help; a person has to fix the setup. */
class AuthFailed(tool: Tool, message: String) : AdapterException(tool, message) {
	override val retryable = false
}

class NotFound(tool: Tool, what: String) : AdapterException(tool, "not found: $what") {
	override val retryable = false
}

/** The service refused the request for another reason. [status] is the HTTP status when it answered with one, e.g. 409. */
class Rejected(tool: Tool, message: String, val status: Int? = null) : AdapterException(tool, message) {
	override val retryable = false
}
