package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

@ConfigurationProperties("portal.sandbox-check")
data class SandboxCheckProperties(
	val enabled: Boolean = false,
	val fixture: String = "sandbox-fixture.yml",
	/** Must be true: the check empties the local portal database before loading the fixture. */
	val resetDatabase: Boolean = false,
	/** Print every account and resource membership the adapters see (sandbox data), to help write a fixture. */
	val discover: Boolean = false,
)

/**
 * Stage 2 package 9 (the full portal's step 6 finish line): loads a known portal state from a fixture into an emptied
 * LOCAL database, runs a dry run against the real sandboxes, and compares the planned diff with the fixture's `expect`
 * list, per tool. Run with scripts/sandbox-check.sh.
 */
@Component
@ConditionalOnBooleanProperty("portal.sandbox-check.enabled")
class SandboxCheck(
	private val properties: SandboxCheckProperties,
	private val dsl: DSLContext,
	private val dataSource: DataSource,
	private val adapters: List<ReadAdapter>,
	private val engine: SyncEngine,
	private val tools: org.hack4impact.portal.tools.ToolRegistry,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	private data class Expected(val kind: String, val person: String?, val resource: String)

	override fun run(args: ApplicationArguments) {
		val code = try {
			if (check()) 0 else 1
		} catch (e: IllegalStateException) {
			log.error("Sandbox check stopped: {}", e.message)
			1
		} catch (e: IllegalArgumentException) {
			log.error("Sandbox check stopped: {}", e.message)
			1
		}
		exitProcess(SpringApplication.exit(context, { code }))
	}

	@Suppress("UNCHECKED_CAST")
	fun check(): Boolean {
		if (properties.discover) {
			// Read-only: shows what the sandboxes contain, without touching the database or needing a fixture.
			log.info("{}", discover(adapters))
			return true
		}
		val file = File(properties.fixture)
		require(file.isFile) { "no fixture at ${file.absolutePath} (copy sandbox-fixture.example.yml)" }
		val fixture = Yaml().load<Map<String, Any?>>(file.readText())

		val tools = adapters.associateBy { it.tool }
		val report = StringBuilder()

		resetSandboxDatabase(dsl, dataSource, this.tools, properties.resetDatabase, "portal.sandbox-check.reset-database")
		val chapter = fixture["chapter"] as Map<String, Any?>
		val chapterId = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, chapter["code"] as String).set(CHAPTER.NAME, chapter["name"] as String)
			.returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val roles = mapOf(
			"member" to insertRole("Developer", false),
			"lead" to insertRole("Project Manager", true),
		)

		// Resources: matched in the tool by name or external ID.
		val resources = (fixture["resources"] as List<Map<String, Any?>>).associate { r ->
			val tool = Tool.valueOf((r["tool"] as String).uppercase())
			val adapter = tools[tool] ?: error("resource ${r["key"]}: the $tool adapter isn't enabled")
			val match = r["match"] as String
			val found = adapter.resources().firstOrNull { it.name == match || it.externalId == match }
				?: error("resource ${r["key"]}: no $tool resource named or with ID '$match'")
			val id = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, tool.name.lowercase()).set(RESOURCE.EXTERNAL_ID, found.externalId)
				.set(RESOURCE.NAME, found.name).set(RESOURCE.CHAPTER_ID, chapterId).returningResult(RESOURCE.ID).fetchSingle().value1()!!
			r["key"] as String to id
		}
		val resourceKeys = resources.entries.associate { (k, v) -> v to k }

		val people = loadFixturePeople(dsl, fixture, tools, chapterId)
		val personNames = people.entries.associate { (k, v) -> v to k }

		((fixture["projects"] as List<Map<String, Any?>>?) ?: emptyList()).forEach { proj ->
			val projectId = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, chapterId).set(PROJECT.NAME, proj["name"] as String)
				.set(PROJECT.SLUG, (proj["name"] as String).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
				.set(PROJECT.STATUS, (proj["status"] as String?) ?: "active").returningResult(PROJECT.ID).fetchSingle().value1()!!
			((proj["members"] as List<Map<String, Any?>>?) ?: emptyList()).forEach { m ->
				dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, projectId)
					.set(PROJECT_MEMBER.PERSON_ID, people[m["person"]] ?: error("project member ${m["person"]} isn't in people"))
					.set(PROJECT_MEMBER.PROJECT_ROLE_ID, roles.getValue((m["role"] as String?) ?: "member"))
					.set(PROJECT_MEMBER.AGREEMENT_STATUS, if (m["signed"] == false) "pending" else "signed").execute()
			}
			((proj["resources"] as List<Map<String, Any?>>?) ?: emptyList()).forEach { pr ->
				dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, projectId)
					.set(PROJECT_RESOURCE.RESOURCE_ID, resources[pr["resource"]] ?: error("project resource ${pr["resource"]} isn't in resources"))
					.set(PROJECT_RESOURCE.AUDIENCE, (pr["audience"] as String?) ?: "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, (pr["access"] as String?) ?: "write")
					.set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, pr["requiresAgreement"] == true).execute()
			}
		}
		((fixture["chapterResources"] as List<Map<String, Any?>>?) ?: emptyList()).forEach { cr ->
			dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, chapterId)
				.set(CHAPTER_RESOURCE.RESOURCE_ID, resources[cr["resource"]] ?: error("chapter resource ${cr["resource"]} isn't in resources"))
				.set(CHAPTER_RESOURCE.AUDIENCE, (cr["audience"] as String?) ?: "members").set(CHAPTER_RESOURCE.ACCESS_LEVEL, (cr["access"] as String?) ?: "write").execute()
		}

		val expected = ((fixture["expect"] as List<Map<String, Any?>>?) ?: emptyList())
			.map { Expected(it["kind"] as String, it["person"] as String?, it["resource"] as String) }.toSet()
		val usedTools = (fixture["resources"] as List<Map<String, Any?>>).map { Tool.valueOf((it["tool"] as String).uppercase()) }.toSet()
		var ok = true
		for (run in engine.dryRun("manual", usedTools)) {
			// Unmatched accounts are expected per resource; their account IDs aren't known in advance.
			val actual = dsl.selectFrom(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(run.runId)).fetch().map {
				Expected(it.kind!!, if (it.kind == "unmatched_account") null else it.personId?.let(personNames::get), resourceKeys[it.resourceId] ?: "?")
			}.toSet()
			val mine = expected.filter { resourceTool(it.resource, fixture) == run.tool }.toSet()
			val missing = mine - actual
			val unexpected = actual - mine
			val pass = run.error == null && missing.isEmpty() && unexpected.isEmpty()
			ok = ok && pass
			report.appendLine("== ${run.tool}: ${if (pass) "PASS" else "FAIL"} (${run.status}${run.error?.let { ": $it" } ?: ""})")
			report.appendLine("   planned: add ${run.adds} · change ${run.changes} · remove ${run.removals} · drift ${run.drift} · unmatched ${run.unmatchedAccounts} · missing ${run.missingResources}")
			missing.forEach { report.appendLine("   expected but not planned: $it") }
			unexpected.forEach { report.appendLine("   planned but not expected: $it") }
		}
		log.info("Sandbox check:\n{}", report)
		return ok
	}

	@Suppress("UNCHECKED_CAST")
	private fun resourceTool(key: String, fixture: Map<String, Any?>) =
		(fixture["resources"] as List<Map<String, Any?>>).firstOrNull { it["key"] == key }?.let { Tool.valueOf((it["tool"] as String).uppercase()) }

	private fun insertRole(name: String, lead: Boolean): UUID =
		dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, name).set(PROJECT_ROLE.IS_LEAD, lead).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!

	private fun discover(adapters: Collection<ReadAdapter>) = buildString {
		appendLine("Discovered (sandbox data):")
		for (adapter in adapters.sortedBy { it.tool }) {
			val accounts = adapter.accounts()
			val login = accounts.filter { it.externalId != null }.associate { it.externalId!! to (it.login ?: it.email ?: it.externalId) }
			appendLine("== ${adapter.tool}")
			accounts.forEach { appendLine("   account ${it.login ?: it.email} [${it.state.name.lowercase()}] id=${it.externalId}") }
			adapter.resources().forEach { r ->
				val members = adapter.members(r.externalId).joinToString { "${login[it.accountId] ?: it.accountId}:${it.access.name.lowercase()}" }
				appendLine("   resource '${r.name}' id=${r.externalId}${if (r.archived) " (archived)" else ""}: $members")
			}
		}
	}
}
