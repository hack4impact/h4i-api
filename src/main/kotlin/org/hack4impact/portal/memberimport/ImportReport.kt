package org.hack4impact.portal.memberimport

/** Per-entity tallies for one import run. `kept` = row exists but was edited or claimed in the portal, so not overwritten. */
data class EntityCounts(
	var read: Int = 0,
	var inserted: Int = 0,
	var updated: Int = 0,
	var kept: Int = 0,
	var skipped: Int = 0,
)

/** Something a person should look at. Contains personal data (names, emails), so the report stays internal. */
data class ImportIssue(val entity: String, val sourceId: String?, val kind: String, val message: String)

class ImportReport {
	val counts = linkedMapOf<String, EntityCounts>()
	val issues = mutableListOf<ImportIssue>()

	fun counts(entity: String) = counts.getOrPut(entity) { EntityCounts() }

	fun issue(entity: String, sourceId: Any?, kind: String, message: String) {
		issues += ImportIssue(entity, sourceId?.toString(), kind, message)
	}

	fun toMarkdown(runId: Any?): String = buildString {
		appendLine("# Member DB import report")
		appendLine()
		appendLine("Run `$runId`. Contains personal data: keep it internal and don't commit it.")
		appendLine()
		appendLine("| Entity | Read | Inserted | Updated | Kept (edited in portal) | Skipped |")
		appendLine("| --- | --- | --- | --- | --- | --- |")
		counts.forEach { (entity, c) ->
			appendLine("| $entity | ${c.read} | ${c.inserted} | ${c.updated} | ${c.kept} | ${c.skipped} |")
		}
		appendLine()
		appendLine("## Issues (${issues.size})")
		issues.groupBy { it.kind }.forEach { (kind, list) ->
			appendLine()
			appendLine("### $kind (${list.size})")
			appendLine()
			list.forEach { appendLine("- **${it.entity}** `${it.sourceId ?: "-"}`: ${it.message}") }
		}
	}
}
