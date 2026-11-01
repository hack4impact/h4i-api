package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool

/**
 * Who to give access to, as the tool knows them: the account ID when the portal has one, else what the tool can
 * invite by (GitHub login, an email address).
 */
data class AccountRef(
	val id: String?,
	val login: String? = null,
	val email: String? = null,
	/** The portal person behind it, for adapters that write tasks rather than call a tool (the admin queue). */
	val person: java.util.UUID? = null,
	val name: String? = null,
)

/**
 * What giving access did: done; the tool sent an invitation the person still has to accept; or nothing yet, because
 * the person has no account the tool can use and the tool can't invite (Slack: members join the workspace themselves).
 */
enum class GrantResult {
	DONE, INVITED, WAITING,

	/** Written to the admin queue for a person to do by hand (no API: Notion membership). */
	QUEUED,
}

/**
 * Changes a tool (build plan step 9). Every call is idempotent: granting access someone already has, or revoking
 * access they don't, succeeds and changes nothing, so a retried run is safe. Failures use the same
 * [AdapterException]s as reads. An account the tool doesn't know and can't invite is [Rejected].
 */
interface WriteAdapter {
	val tool: Tool

	/** Creates a resource named [name] (private where the tool has the choice) and returns its external ID. */
	fun create(name: String): String

	/** Gives [account] [access] to the resource, or changes the access it has. */
	fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult

	/** Takes [accountId] out of the resource: DONE, or QUEUED for a person to do by hand (the admin queue). */
	fun revoke(resourceId: String, accountId: String): GrantResult

	/**
	 * Ends a resource. Slack archives the channel; GitHub teams and Google groups can't be archived, so they're
	 * deleted (a team's repos stay; a group's mail archive goes). Gone already counts as done. The sync doesn't
	 * call this yet: what closing a project does per tool is still to decide. The sandbox suite cleans up with it.
	 */
	fun archive(resourceId: String)

	/**
	 * Finishes what the tool needs an admin for after a person accepts an invitation (Vaultwarden: confirming the
	 * member). Called after each apply run with the IDs of the resources the portal manages; returns how many.
	 */
	fun confirmPending(managedResourceIds: Set<String>): Int = 0
}
