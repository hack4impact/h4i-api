package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import java.time.Duration

/**
 * Reads a tool's actual state (build plan step 4). Every adapter passes the same contract tests;
 * writes (add, remove) arrive in step 9. Adapters never decide anything: they report what the tool says.
 */
interface ReadAdapter {
	val tool: Tool

	/** Every account in the organization or workspace the portal manages, including pending invites. */
	fun accounts(): List<ToolAccount>

	/** Every resource of this tool: Slack channels, GitHub teams, Google groups, Vaultwarden collections. */
	fun resources(): List<ToolResource>

	/** Who is in one resource, by account ID. Throws [NotFound] for a resource the tool doesn't have. */
	fun members(resourceId: String): List<ResourceMember>
}

/**
 * An account as the tool reports it. [externalId] is the stable ID the portal matches on (GitHub numeric ID,
 * Slack user ID, Google user ID, Vaultwarden membership ID); it's null for an invite not yet tied to an account.
 */
data class ToolAccount(
	val externalId: String?,
	val login: String?,
	val email: String?,
	val name: String?,
	val state: AccountState,
)

/** INVITED: not accepted yet. ACCEPTED: accepted, waiting for an admin step (Vaultwarden confirm). */
enum class AccountState { ACTIVE, INVITED, ACCEPTED, SUSPENDED }

/** A resource as the tool reports it. [externalId] is what the portal stores (channel ID, team slug, group email, collection ID). */
data class ToolResource(val externalId: String, val name: String, val archived: Boolean = false)

/**
 * [login] is the login or email the tool shows for the member, when it shows one (GitHub, Google); it helps match
 * people. [implicit]: access that comes from the member's role in the tool, not from this resource (Vaultwarden
 * owners and admins reach every collection); the portal can neither give nor take it, so the sync leaves it alone.
 */
data class ResourceMember(val accountId: String, val access: Access, val login: String? = null, val implicit: Boolean = false)

/** Failures every adapter reports the same way, so the sync engine can retry, pause or alert without knowing the tool. */
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

/** The tool refused the request for another reason (a 4xx that isn't auth, rate limit or not found). */
/** [status] is the HTTP status when the tool answered with one, e.g. 409 for "already exists". */
class Rejected(tool: Tool, message: String, val status: Int? = null) : AdapterException(tool, message) {
	override val retryable = false
}
