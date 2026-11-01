package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Loads the sandbox fixture's people into [chapterId]: each with their status, chapter role and [email] (their H4I
 * address, which tools invite when they have no account yet), and each tool account matched by login, email or ID in
 * that tool (sandbox check and suite, stage 2 package 9, from the full portal). Returns name → person ID. With
 * [skipDisabledTools], accounts in tools whose adapter isn't enabled are left out; otherwise they're an error.
 */
@Suppress("UNCHECKED_CAST")
internal fun loadFixturePeople(dsl: DSLContext, fixture: Map<String, Any?>, tools: Map<Tool, ReadAdapter>, chapterId: UUID, skipDisabledTools: Boolean = false): Map<String, UUID> {
	val accountsByTool = mutableMapOf<Tool, List<ToolAccount>>()
	return (fixture["people"] as List<Map<String, Any?>>).associate { p ->
		val name = p["name"] as String
		val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, name.substringBefore(' ')).set(PERSON.LAST_NAME, name.substringAfter(' ', "Sandbox"))
			.set(PERSON.STATUS, (p["status"] as String?) ?: "active").set(PERSON.ORG_EMAIL, p["email"] as String?)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapterId).execute()
		(p["role"] as String?)?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, chapterId).set(CHAPTER_ROLE.ROLE, it).execute() }
		((p["accounts"] as Map<String, String>?) ?: emptyMap()).forEach { (toolName, login) ->
			val tool = Tool.valueOf(toolName.uppercase())
			if (skipDisabledTools && tool !in tools) return@forEach
			val adapter = tools[tool] ?: error("$name: the $tool adapter isn't enabled")
			val accounts = accountsByTool.getOrPut(tool) { adapter.accounts() }
			val account = accounts.firstOrNull { listOf(it.login, it.email, it.externalId).any { v -> v.equals(login, ignoreCase = true) } && it.externalId != null }
				?: error("$name: no $tool account with login, email or ID '$login'")
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, tool.name.lowercase())
				.set(TOOL_ACCOUNT.EXTERNAL_ID, account.externalId).set(TOOL_ACCOUNT.EXTERNAL_LOGIN, account.login).set(TOOL_ACCOUNT.STATE, "confirmed").execute()
		}
		name to id
	}
}

/**
 * Empties the portal tables of a LOCAL database for the sandbox check and suite. Puts back what the migrations seed
 * (the national resource template, which the truncate takes with `chapter`) and each syncing tool's settings row, in
 * dry run, as `ToolSettingsBootstrap` would (it's off in the one-shot profile).
 */
/** More people than a sandbox fixture would ever hold. */
internal const val MAX_SANDBOX_PEOPLE = 50

internal fun resetSandboxDatabase(dsl: DSLContext, dataSource: DataSource, tools: ToolRegistry, confirmed: Boolean, flag: String) {
	val url = dataSource.connection.use { it.metaData.url }
	check("//localhost" in url || "//127.0.0.1" in url) { "refusing to reset a non-local database ($url)" }
	check(confirmed) { "add --$flag=true: this empties the local portal database first" }
	// A deployment's database reached through a tunnel on localhost passes the URL check (review R3): refuse one that holds
	// what only a deployment would, people imported from the member database or more than a sandbox's handful.
	val imported = dsl.fetchCount(PERSON, PERSON.SOURCE_ID.isNotNull)
	val people = dsl.fetchCount(PERSON)
	check(imported == 0 && people <= MAX_SANDBOX_PEOPLE) {
		"refusing to reset a database that looks like a deployment's ($people people, $imported from the member database): the sandbox's has at most $MAX_SANDBOX_PEOPLE and none imported"
	}
	val national = dsl.selectFrom(RESOURCE_TEMPLATE).where(RESOURCE_TEMPLATE.CHAPTER_ID.isNull).fetch()
	val tables = dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal' AND tablename <> 'flyway_schema_history'").map { it.get(0, String::class.java) }
	dsl.execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
	national.forEach { it.changed(true); dsl.insertInto(RESOURCE_TEMPLATE).set(it).execute() } // a fetched record sets nothing until marked changed
	tools.syncTools.forEach { dsl.insertInto(TOOL_SETTING).set(TOOL_SETTING.TOOL, it.key).execute() }
}
