package org.hack4impact.portal.preflight

import org.hack4impact.portal.resolver.Tool

/**
 * A syncing tool's own preflight checks, declared next to its adapters by each tool item (stage 2, packages 3-Slack,
 * 3-Google, 3-GitHub, 3-Vaultwarden): what the portal may do there, asked without trying a write. Slack: the bot's
 * scopes from `auth.test`; GitHub: the App installation's Members permission; Google: a token for the group write
 * scopes; Vaultwarden: the API key logs in and the master password unlocks the organization key.
 */
interface ToolPreflight {
	val tool: Tool

	fun checks(): List<Check>
}
