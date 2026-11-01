package org.hack4impact.portal.tools

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.resolver.Tool
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * What the portal needs to know about a tool besides its adapters (wiki: tool plugins): its names, where it sits in
 * lists, how its resources are named, and a few policy flags. The essentials are built in; a plugin declares a
 * [ToolDescriptor] bean next to its adapters. Behaviour that's one tool's own (Vaultwarden's confirm step, GitHub
 * invitations) stays in that tool's code. Ported from the full portal for stage 2 (wiki: staged-rollout-stage-2).
 */
data class ToolDescriptor(
	val tool: Tool,
	/** "Slack", "Google". */
	val name: String,
	/** What a resource is called after its name in previews and emails: "umd-rise (Google group)". */
	val resourceLabel: String,
	/** The project page's box for this tool: "Slack channels". */
	val resourcesTitle: String,
	/** Position in lists (member page, activity, filters, the national Tools panel). */
	val order: Int,
	/** Position of its box on the project page. */
	val projectOrder: Int,
	/** Shown before a resource's name: "#" for Slack channels. */
	val namePrefix: String = "",
	/** Names a new resource may have; null: the portal doesn't create this tool's resources. */
	val naming: NameRule? = null,
	/** An example name for a new resource, for the add form: {chapter} and {project} are filled in. */
	val nameExample: String = "{chapter}-{project}-eng",
	/** New resource names are lowercased (and lose a leading #). */
	val lowercaseNames: Boolean = false,
	/** Repositories and vaults: alumni only through a live project, and a project agreement releases them (decision 51). */
	val sensitive: Boolean = false,
	/** The tool has an admin level (GitHub maintainer, Google manager, Vaultwarden manage). */
	val hasAdminLevel: Boolean = false,
	/** Closing every project using a resource archives it (Slack channels); otherwise it's only emptied. */
	val archiveOnClose: Boolean = false,
	/** Chapters attach its resources to projects (false for Documenso, which holds agreements, and Notion until stage 4). */
	val projectResources: Boolean = true,
	/** People have accounts in it (`tool_account.tool`), shown on their page and listed by `/api/tools`. */
	val accounts: Boolean = true,
	/** The sync manages its resources: it has a `tool_setting` row (kill switch, dry run) and a switch for national. */
	val syncs: Boolean = false,
	/** A link into the tool for a resource that exists there. */
	val link: (settings: AdapterProperties, name: String, externalId: String) -> String? = { _, _, _ -> null },
) {
	val key: String get() = tool.name.lowercase()
}

/** A new resource's allowed names and the rule in words ("lowercase letters, digits and -"), always together. */
data class NameRule(val pattern: Regex, val hint: String)

@Component
class ToolRegistry(plugins: ObjectProvider<ToolDescriptor>) {
	companion object {
		/** The essentials, with the values the full portal uses (pinned by ToolRegistryGoldenTests). */
		val BUILT_IN: List<ToolDescriptor> = listOf(
			ToolDescriptor(
				Tool.SLACK, "Slack", "Slack", "Slack channels", order = 1, projectOrder = 1, namePrefix = "#",
				naming = NameRule(Regex("^[a-z0-9][a-z0-9_-]{0,79}$"), "lowercase letters, digits, - and _, at most 80"), lowercaseNames = true,
				archiveOnClose = true, syncs = true,
				link = { _, _, id -> "https://slack.com/app_redirect?channel=$id" },
			),
			ToolDescriptor(
				Tool.GOOGLE, "Google", "Google group", "Google groups", order = 2, projectOrder = 5,
				naming = NameRule(Regex("^[a-z0-9][a-z0-9._-]*@([a-z0-9-]+\\.)*hack4impact\\.org$"), "an address at hack4impact.org"), nameExample = "{chapter}-{project}-eng@hack4impact.org", lowercaseNames = true,
				hasAdminLevel = true, syncs = true,
				link = { _, name, _ -> name.substringBefore('@').let { local -> "https://groups.google.com/a/${name.substringAfter('@', "hack4impact.org")}/g/$local" } },
			),
			ToolDescriptor(
				Tool.GITHUB, "GitHub", "GitHub", "GitHub teams", order = 3, projectOrder = 2,
				naming = NameRule(Regex("^[a-z0-9][a-z0-9-]{0,99}$"), "lowercase letters, digits and -"), lowercaseNames = true,
				sensitive = true, hasAdminLevel = true, syncs = true,
				link = { s, name, _ -> s.github.org.trim().trim('/').substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { "https://github.com/orgs/$it/teams/$name" } },
			),
			// Accounts only: imported from the member database and shown. Pages and teamspaces come with stage 4.
			ToolDescriptor(
				Tool.NOTION, "Notion", "Notion", "Notion page", order = 4, projectOrder = 4, projectResources = false,
				link = { _, _, id -> "https://www.notion.so/${id.replace("-", "")}" },
			),
			ToolDescriptor(
				Tool.VAULTWARDEN, "Vaultwarden", "Vaultwarden", "Vaultwarden collections", order = 5, projectOrder = 3,
				naming = NameRule(Regex("^.{1,100}$"), "at most 100 characters"),
				sensitive = true, hasAdminLevel = true, syncs = true,
				link = { s, _, _ -> s.vaultwarden.baseUrl.trim().takeIf { it.isNotEmpty() }?.trimEnd('/') },
			),
			// Holds agreements; stopped only by PORTAL_ADAPTERS_DOCUMENSO_WRITE, never by a tool setting.
			ToolDescriptor(Tool.DOCUMENSO, "Documenso", "Documenso", "Documenso", order = 6, projectOrder = 6, projectResources = false, accounts = false),
		)
	}

	/** Every tool, in list order. */
	val all: List<ToolDescriptor> = (BUILT_IN + plugins.orderedStream().toList()).sortedBy { it.order }.also { tools ->
		// Checked at startup, not in the middle of a sync: every tool described, and only once.
		val twice = tools.groupBy { it.tool }.filterValues { it.size > 1 }.keys
		check(twice.isEmpty()) { "Tools described twice: $twice" }
		val missing = Tool.entries.filter { t -> tools.none { it.tool == t } }
		check(missing.isEmpty()) { "Tools without a ToolDescriptor (declare one next to their adapters): $missing" }
	}

	private val byKey = all.associateBy { it.key }

	operator fun get(tool: Tool): ToolDescriptor = byKey.getValue(tool.name.lowercase())

	fun find(key: String?): ToolDescriptor? = key?.let { byKey[it.lowercase()] }

	/** Tools whose resources chapters attach to projects, in list order. */
	val resourceTools: List<ToolDescriptor> get() = all.filter { it.projectResources }

	/** Tools people have accounts in, in list order. */
	val accountTools: List<ToolDescriptor> get() = all.filter { it.accounts }

	/** Tools the sync manages, in list order: each has a `tool_setting` row. */
	val syncTools: List<ToolDescriptor> get() = all.filter { it.syncs }

	val sensitive: Set<Tool> get() = all.filter { it.sensitive }.map { it.tool }.toSet()

	/** "umd-rise (Google group)": a resource named in a preview or an email. */
	fun label(key: String, name: String) = "$name (${find(key)?.resourceLabel ?: key})"

	/** A resource's name as people see it in the tool: "#umd-rise" for a Slack channel. */
	fun displayName(key: String, name: String) = "${find(key)?.namePrefix.orEmpty()}$name"
}
