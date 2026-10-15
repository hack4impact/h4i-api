package org.hack4impact.portal.tools

/**
 * The tools the portal talks to. [accounts]: people have accounts in it (`tool_account.tool`): stage 1 creates Google
 * accounts and verifies GitHub accounts; Slack and Notion accounts come from the member database and are only shown.
 * Documenso holds the registration agreements.
 */
enum class Tool(val label: String, val order: Int, val accounts: Boolean = true) {
	SLACK("Slack", 1),
	GOOGLE("Google", 2),
	GITHUB("GitHub", 3),
	NOTION("Notion", 4),
	DOCUMENSO("Documenso", 5, accounts = false);

	val key: String get() = name.lowercase()

	companion object {
		/** The tool a stored name means, or null for one this app doesn't know (rows of such tools are left out). */
		fun fromKey(key: String?): Tool? = key?.let { k -> entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
	}
}
