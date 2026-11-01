package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.agreements.AgreementService
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.projects.MemberInput
import org.hack4impact.portal.projects.ProjectInput
import org.hack4impact.portal.projects.ProjectService
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

/** The prefix of everything a run creates, `sandbox-<user>-<yyyymmddhhmmss>-`: lowercase letters, digits and dashes, as every tool accepts. */
internal fun sandboxPrefix(user: String, now: LocalDateTime): String {
	val who = user.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).trim('-').ifEmpty { "tester" }
	return "sandbox-$who-${now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}-"
}

@ConfigurationProperties("portal.sandbox-e2e")
data class SandboxE2EProperties(
	val enabled: Boolean = false,
	val fixture: String = "sandbox-fixture.yml",
	/** Must be true: the suite empties the local portal database first. */
	val resetDatabase: Boolean = false,
	/** Leave the created channel, group, team and collection in the tools (to look at them). */
	val keep: Boolean = false,
	/** Who runs it: the created resources start with `sandbox-<user>-<yyyymmddhhmmss>-`, so two testers don't collide. */
	val user: String = System.getProperty("user.name") ?: "tester",
	/** The Google group's domain (the sandbox group scope must match the prefix). */
	val googleDomain: String = "hack4impact.org",
	/** The Documenso template (`envelope_…`) the project agreement is sent from; empty: the agreement isn't sent. */
	val agreementTemplate: String = "",
	/** How long to wait for someone to sign the agreement (and accept a vault invite) by hand; zero: don't wait. */
	val waitForSignature: Duration = Duration.ZERO,
	/** Sign the agreement through the LOCAL sandbox Documenso as its signing page does (refused for any other Documenso). */
	val signLocally: Boolean = false,
)

/**
 * Stage 2 package 9: really changes the sandboxes through the sync engine, and checks each tool after each step
 * (the full portal's step 9 suite, cut down to stage 2: no drafts, rules, Notion or registration).
 *
 *  1. Setup: the fixture's people in an emptied LOCAL database; a project made through [ProjectService] with the
 *     standard resources for each tool that has a write adapter, named `sandbox-<user>-<yyyymmddhhmmss>-…`; its team
 *     added and the project started. Everyone has signed the project agreement but one person (the signer).
 *  2. Agreement: with Documenso and a template, the signer's agreement is sent and waits for a signature there.
 *  3. Apply: the run creates the resources and adds everyone it can; the tools must show them, and the signer must
 *     be held back from the gated ones (GitHub, Vaultwarden).
 *  4. Vaultwarden: people invited by email accept (a local Vaultwarden without mail accepts for registered users;
 *     otherwise by hand in Mailpit), and the next run confirms them.
 *  5. Again: a second run must have nothing left to do.
 *  6. Sign: the signer signs (in the local Documenso by the suite, in a real one by hand with `--sign`; without
 *     Documenso the suite records it), the portal sees it and keeps the signed PDF, and the
 *     `agreement` run gives them the gated resources.
 *  7. Remove: one member leaves the project; the run takes their access away.
 *  8. Close: the project closes; the run takes everyone's access away and archives the Slack channel.
 *  9. Cleanup: the created resources are archived or deleted (unless `keep`), the test agreement deleted.
 *
 * Run with scripts/sandbox-e2e.sh.
 */
@Component
@ConditionalOnBooleanProperty("portal.sandbox-e2e.enabled")
class SandboxE2E(
	private val properties: SandboxE2EProperties,
	private val dsl: DSLContext,
	private val dataSource: DataSource,
	private val readers: List<ReadAdapter>,
	private val writers: List<WriteAdapter>,
	private val engine: SyncEngine,
	private val projects: ProjectService,
	private val agreements: AgreementService,
	private val documenso: ObjectProvider<org.hack4impact.portal.adapters.documenso.DocumensoClient>,
	private val registry: ToolRegistry,
	private val adapterProperties: org.hack4impact.portal.adapters.AdapterProperties,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)
	private val report = StringBuilder()
	private var failures = 0
	private val national = Viewer(null, "sandbox-e2e", "Sandbox suite", true, emptyMap())

	private fun check(label: String, step: String, ok: Boolean, detail: String = "") {
		if (!ok) failures++
		report.appendLine("${if (ok) "PASS" else "FAIL"}  ${label.padEnd(11)} $step${if (detail.isNotEmpty()) "  ($detail)" else ""}")
	}

	private fun check(tool: Tool, step: String, ok: Boolean, detail: String = "") = check(tool.name, step, ok, detail)

	private fun note(line: String) = report.appendLine("      $line")

	override fun run(args: ApplicationArguments) {
		val code = try {
			if (suite()) 0 else 1
		} catch (e: IllegalStateException) {
			log.error("Sandbox suite stopped: {}", e.message); 1
		} catch (e: IllegalArgumentException) {
			log.error("Sandbox suite stopped: {}", e.message); 1
		}
		exitProcess(SpringApplication.exit(context, { code }))
	}

	@Suppress("UNCHECKED_CAST")
	fun suite(): Boolean {
		val file = File(properties.fixture)
		require(file.isFile) { "no fixture at ${file.absolutePath} (copy sandbox-fixture.example.yml)" }
		val fixture = Yaml().load<Map<String, Any?>>(file.readText())
		val readerOf = readers.associateBy { it.tool }
		val syncing = registry.syncTools.map { it.tool }.toSet()
		val tools = writers.map { it.tool }.filter { it in readerOf && it in syncing }.sorted()
		check(tools.isNotEmpty()) { "no write adapters: set PORTAL_ADAPTERS_<TOOL>_WRITE=true for the sandboxes" }
		val gated = tools.filter { it in registry.sensitive }.toSet()

		// 1. Setup.
		resetSandboxDatabase(dsl, dataSource, registry, properties.resetDatabase, "portal.sandbox-e2e.reset-database")
		val chapter = fixture["chapter"] as Map<String, Any?>
		val code = chapter["code"] as String
		val chapterId = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, code).set(CHAPTER.NAME, chapter["name"] as String).returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val people = loadFixturePeople(dsl, fixture, readerOf, chapterId, skipDisabledTools = true)
		val names = people.entries.associate { (k, v) -> v to k }
		val statusOf = (fixture["people"] as List<Map<String, Any?>>).associate { it["name"] as String to ((it["status"] as String?) ?: "active") }
		val team = people.filterKeys { statusOf[it] == "active" }.values.toList()
		check(team.size >= 2) { "the fixture needs at least two active people (one is removed in the remove step)" }

		val prefix = sandboxPrefix(properties.user, LocalDateTime.now())
		tools.forEachIndexed { i, tool ->
			val pattern = "$prefix{chapter}-{project}" + if (tool == Tool.GOOGLE) "@${properties.googleDomain}" else ""
			dsl.insertInto(RESOURCE_TEMPLATE).set(RESOURCE_TEMPLATE.CHAPTER_ID, chapterId).set(RESOURCE_TEMPLATE.POSITION, i).set(RESOURCE_TEMPLATE.TOOL, tool.name.lowercase())
				.set(RESOURCE_TEMPLATE.NAME_PATTERN, pattern).set(RESOURCE_TEMPLATE.ACCESS_LEVEL, "write").set(RESOURCE_TEMPLATE.REQUIRES_AGREEMENT, tool in gated).execute()
		}
		val developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		val manager = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Project Manager").set(PROJECT_ROLE.IS_LEAD, true).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		val partner = dsl.insertInto(PARTNER).set(PARTNER.NAME, "Sandbox partner").returningResult(PARTNER.ID).fetchSingle().value1()!!
		val projectId = projects.create(chapterId, ProjectInput("Sandbox suite", slug = "e2e", partnerId = partner), national).id!!
		fun project() = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(projectId)).fetchSingle()
		team.forEachIndexed { i, person -> projects.addMember(chapterId, project(), MemberInput(person, if (i == 0) manager else developer), national) }
		projects.setStatus(chapterId, project(), "active", national)
		val resources = dsl.select(RESOURCE.TOOL, RESOURCE.ID).from(RESOURCE).join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(projectId)).fetch().associate { Tool.valueOf(it.value1()!!.uppercase()) to it.value2()!! }
		check("SETUP", "project ${project().slug} started with ${team.size} people and ${resources.size} resources named $prefix$code-e2e", resources.keys == tools.toSet())

		// The signer: someone with an account in a gated tool, or an address Vaultwarden can invite. Everyone else has signed.
		val signer = team.firstOrNull { p ->
			gated.any { tool -> dsl.fetchExists(TOOL_ACCOUNT, TOOL_ACCOUNT.PERSON_ID.eq(p), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase())) } ||
				(Tool.VAULTWARDEN in gated && dsl.fetchExists(PERSON, PERSON.ID.eq(p), PERSON.ORG_EMAIL.isNotNull))
		}
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed")
			.where(PROJECT_MEMBER.PROJECT_ID.eq(projectId), if (signer != null) PROJECT_MEMBER.PERSON_ID.ne(signer) else org.jooq.impl.DSL.noCondition()).execute()
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.`in`(tools.map { it.name.lowercase() })).execute()
		report.appendLine("Run $prefix: ${tools.joinToString { it.name.lowercase() }}, ${team.size} people${signer?.let { ", signer ${names[it]}" } ?: ""}")

		// Each person's accounts in a tool: the fixture's, plus any the tool lists under their address (an accepted invite).
		val listed = mutableMapOf<Tool, List<org.hack4impact.portal.adapters.ToolAccount>>()
		fun relist(tool: Tool) = readerOf.getValue(tool).accounts().also { listed[tool] = it }
		fun accountsOf(tool: Tool, person: UUID): Set<String> {
			val own = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase())).fetch().mapNotNull { it.value1() }
			val email = dsl.select(PERSON.ORG_EMAIL).from(PERSON).where(PERSON.ID.eq(person)).fetchSingle().value1()
			val byEmail = email?.let { e -> listed.getOrPut(tool) { relist(tool) }.filter { it.email.equals(e, ignoreCase = true) }.mapNotNull { it.externalId } }.orEmpty()
			return (own + byEmail).toSet()
		}
		fun externalId(tool: Tool) = dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(resources.getValue(tool))).fetchSingle().value1()
		fun outcomes(runId: UUID?) = dsl.select(SYNC_CHANGE.KIND, SYNC_CHANGE.OUTCOME, SYNC_CHANGE.PERSON_ID, SYNC_CHANGE.ERROR).from(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(runId)).fetch()
		/** Explicit members only: access through a role (a Vaultwarden owner) isn't the portal's. */
		fun membersIn(tool: Tool): Set<String>? = externalId(tool)?.let { ext ->
			try { readerOf.getValue(tool).members(ext).filterNot { it.implicit }.map { it.accountId }.toSet() } catch (e: AdapterException) { null }
		}
		fun has(tool: Tool, person: UUID, there: Set<String>?) = there != null && accountsOf(tool, person).any { it in there }

		var envelope: String? = null
		var confirmedEarlier = 0 // Vaultwarden: members the apply run already confirmed (a local Vaultwarden accepts at once)
		/** Who on the team the tool lists as an explicit member now. */
		fun holders(tool: Tool) = membersIn(tool).let { there -> team.filter { has(tool, it, there) }.toSet() }
		try {
			// 2. Agreement.
			envelope = agreementPhase(projectId, signer, gated)

			// 3. Apply.
			for (r in engine.run("manual", tools.toSet())) {
				val changes = outcomes(r.runId)
				check(r.tool, "apply: run ${r.status}", r.status == "completed", r.error ?: "")
				val created = changes.any { it.value1() == "create_resource" && it.value2() == "applied" }
				check(r.tool, "apply: created ${externalId(r.tool) ?: "nothing"}", created, changes.firstOrNull { it.value1() == "create_resource" }?.value4() ?: "")
				val added = changes.filter { it.value1() == "add" && it.value2() in setOf("applied", "invited") }.mapNotNull { it.value3() }.toSet()
				val expected = team.filter { r.tool !in gated || it != signer }
				relist(r.tool)
				// Everyone expected is added or invited, except someone with no account the tool could use, whose grant
				// waits for one (review R2). At least one real add, so the tool itself is exercised.
				val waiting = changes.filter { it.value1() == "add" && it.value2() == "waiting" }.mapNotNull { it.value3() }.filter { accountsOf(r.tool, it).isEmpty() }.toSet()
				val unexplained = expected.filter { it !in added && it !in waiting }
				val detail = (unexplained.map { p -> "${names[p]}: ${changes.firstOrNull { it.value1() == "add" && it.value3() == p }?.let { "${it.value2()}${it.value4()?.let { e -> " $e" } ?: ""}" } ?: "nothing planned"}" } +
					waiting.map { "${names[it]}: waiting, no account in the tool" }).joinToString().ifEmpty { "everyone" }
				check(r.tool, "apply: added or invited ${added.size} of ${expected.size}", added.isNotEmpty() && unexplained.isEmpty(), detail)
				val there = membersIn(r.tool)
				val applied = changes.filter { it.value1() == "add" && it.value2() == "applied" }.mapNotNull { it.value3() }
				val missing = applied.filterNot { has(r.tool, it, there) }
				check(r.tool, "apply: the tool shows the ${applied.size} added", there != null && missing.isEmpty(), if (there == null) "couldn't read members" else missing.joinToString { names[it] ?: "?" })
				val invited = changes.filter { it.value1() == "add" && it.value2() == "invited" }.mapNotNull { it.value3() }
				if (invited.isNotEmpty()) {
					val unlisted = invited.filter { accountsOf(r.tool, it).isEmpty() }
					check(r.tool, "apply: the tool lists the ${invited.size} invited", unlisted.isEmpty(), unlisted.joinToString { names[it] ?: "?" })
				}
				if (r.tool == Tool.VAULTWARDEN) confirmedEarlier = r.confirmed
				if (signer != null && r.tool in gated) {
					check(r.tool, "gate: ${names[signer]} held back until they sign", signer !in added && !has(r.tool, signer, there))
				}
			}

			// 4. Vaultwarden: invited people accept, and the next run confirms them.
			if (Tool.VAULTWARDEN in tools) vaultwardenPhase(team, names, confirmedEarlier, ::relist, ::accountsOf, ::membersIn, ::has)

			// 5. Again: nothing left to do.
			for (r in engine.run("manual", tools.toSet())) {
				val repeated = outcomes(r.runId).filter { (it.value1() == "add" || it.value1() == "create_resource") && it.value2() in setOf("applied", "invited", "queued") }
				check(r.tool, "again: nothing left to do", r.status == "completed" && repeated.isEmpty(), "${repeated.size} changes repeated${r.error?.let { ", $it" } ?: ""}")
			}

			// 6. Sign.
			if (signer != null && gated.isNotEmpty()) {
				val signed = signPhase(projectId, signer, envelope)
				if (signed) {
					for (r in engine.run("agreement", gated)) {
						relist(r.tool)
						val there = membersIn(r.tool)
						val ok = r.status == "completed" && (has(r.tool, signer, there) || outcomes(r.runId).any { it.value1() == "add" && it.value2() == "invited" && it.value3() == signer })
						check(r.tool, "sign: the signature gave ${names[signer]} access", ok, r.error ?: "")
					}
				}
			}

			// 7. Remove: the last team member leaves.
			val leaving = team.last()
			val hadBefore = tools.associateWith { leaving in holders(it) }
			projects.removeMember(chapterId, project(), leaving, national)
			for (r in engine.run("manual", tools.toSet())) {
				val removed = outcomes(r.runId).filter { it.value1() == "remove" && it.value2() == "applied" }.mapNotNull { it.value3() }
				val had = hadBefore.getValue(r.tool)
				relist(r.tool)
				val there = membersIn(r.tool)
				check(r.tool, "remove: ${names[leaving]} ${if (had) "lost their access" else "had none to lose"}", r.status == "completed" && (removed == listOfNotNull(leaving.takeIf { had })) && !has(r.tool, leaving, there),
					r.error ?: "removed ${removed.size}")
			}

			// 8. Close: everyone else loses access, and the Slack channel is archived.
			val beforeClose = tools.associateWith { holders(it) }
			projects.setStatus(chapterId, project(), "closed", national)
			for (r in engine.run("manual", tools.toSet())) {
				val changes = outcomes(r.runId)
				val removed = changes.count { it.value1() == "remove" && it.value2() == "applied" }
				val expected = beforeClose.getValue(r.tool)
				relist(r.tool)
				val there = membersIn(r.tool)
				val left = expected.filter { has(r.tool, it, there) }
				check(r.tool, "close: removed $removed of ${expected.size}, and the tool shows them gone", r.status == "completed" && removed == expected.size && left.isEmpty(),
					r.error ?: left.joinToString { names[it] ?: "?" })
				if (registry[r.tool].archiveOnClose) {
					val archived = changes.any { it.value1() == "archive_resource" && it.value2() == "applied" } &&
						dsl.fetchExists(RESOURCE, RESOURCE.ID.eq(resources.getValue(r.tool)), RESOURCE.ARCHIVED_AT.isNotNull)
					check(r.tool, "close: archived ${externalId(r.tool)}", archived)
				}
			}
		} catch (e: RuntimeException) {
			// Still clean up and report: a crash mustn't leave real channels and teams behind unnoticed.
			log.error("Sandbox suite stopped early", e)
			failures++
			report.appendLine("FAIL  stopped early: ${e.javaClass.simpleName}: ${e.message}")
		} finally {
			// 9. Cleanup.
			envelope?.let { id ->
				val ok = try {
					runCatching { documenso.ifAvailable!!.cancel(id, "Sandbox suite cleanup") } // a signed one can't be cancelled; deleting is enough
					documenso.ifAvailable!!.delete(id); true
				} catch (e: AdapterException) { note("cleanup agreement: ${e.message}"); false }
				check("DOCUMENSO", "cleanup: deleted the test agreement", ok)
			}
			if (!properties.keep) {
				for (tool in tools) {
					val row = dsl.select(RESOURCE.EXTERNAL_ID, RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(resources[tool])).fetchOne() ?: continue
					val ext = row.value1() ?: continue
					if (row.value2() != null) continue // archived when the project closed
					val ok = try { writers.first { it.tool == tool }.archive(ext); true } catch (e: AdapterException) { note("cleanup $tool: ${e.message}"); false }
					check(tool, "cleanup: ${if (tool == Tool.SLACK) "archived" else "deleted"} $ext", ok)
				}
				// Vaultwarden: the people this run invited leave the organization again, so the next run invites (and
				// confirms) them too. Removing someone from the organization isn't something the portal does.
				if (Tool.VAULTWARDEN in tools) {
					val invited = dsl.selectDistinct(SYNC_CHANGE.PERSON_ID).from(SYNC_CHANGE).join(SYNC_RUN).on(SYNC_RUN.ID.eq(SYNC_CHANGE.RUN_ID))
						.where(SYNC_RUN.TOOL.eq("vaultwarden"), SYNC_CHANGE.KIND.eq("add"), SYNC_CHANGE.OUTCOME.eq("invited")).fetch().mapNotNull { it.value1() }
					if (invited.isNotEmpty()) {
						val ok = try {
							relist(Tool.VAULTWARDEN)
							invited.flatMap { accountsOf(Tool.VAULTWARDEN, it) }.forEach(::leaveOrganization); true
						} catch (e: AdapterException) { note("cleanup Vaultwarden members: ${e.message}"); false }
						check(Tool.VAULTWARDEN, "cleanup: the ${invited.size} invited left the organization", ok)
					}
				}
			} else note("keep: left the created resources in the tools")
		}
		report.appendLine(if (failures == 0) "ALL PASSED" else "$failures FAILED")
		log.info("Sandbox suite:\n{}", report)
		return failures == 0
	}

	/**
	 * Signs [envelope]'s signature field and completes it as its recipient, with the two calls Documenso's signing page
	 * makes (its tRPC API, v2.20). Only for the local sandbox: the suite stands in for the person signing.
	 */
	private fun signLocally(base: String, envelope: String) {
		val http = org.hack4impact.portal.adapters.HttpJson(Tool.DOCUMENSO)
		val e = http.get("$base/api/v2/envelope/${org.hack4impact.portal.adapters.HttpJson.encode(envelope)}", mapOf("Authorization" to adapterProperties.documenso.token)).body
		val recipient = e.path("recipients").first()
		val token = recipient.path("token").asString()
		val field = e.path("fields").first { it.path("recipientId").asInt() == recipient.path("id").asInt() && it.path("type").asString() == "SIGNATURE" }.path("id").asInt()
		val document = e.path("secondaryId").asString().substringAfter("document_").toInt()
		val origin = mapOf("Origin" to base)
		http.postJson("$base/api/trpc/field.signFieldWithToken", mapOf("json" to mapOf("token" to token, "fieldId" to field, "value" to recipient.path("name").asString(), "isBase64" to false)), origin)
		http.postJson("$base/api/trpc/recipient.completeDocumentWithToken", mapOf("json" to mapOf("token" to token, "documentId" to document)), origin)
	}

	/** Removes a member from the Vaultwarden organization (sandbox cleanup only). */
	private fun leaveOrganization(memberId: String) {
		val v = adapterProperties.vaultwarden
		val url = v.baseUrl.trimEnd('/')
		val http = org.hack4impact.portal.adapters.vaultwardenHttp(v)
		val token = org.hack4impact.portal.adapters.vaultwarden.VaultwardenApiKeyToken(url, v.clientId, v.clientSecret, v.deviceId, http)
		http.delete("$url/api/organizations/${v.organizationId}/users/$memberId", mapOf("Authorization" to "Bearer ${token()}"))
	}

	/** Sends the signer's project agreement through Documenso, when it's set up. Returns the envelope's ID. */
	private fun agreementPhase(projectId: UUID, signer: UUID?, gated: Set<Tool>): String? {
		val client = documenso.ifAvailable
		if (signer == null || gated.isEmpty()) {
			note("Agreement: skipped (needs GitHub or Vaultwarden, and someone with an account there)")
			return null
		}
		if (client == null || !agreements.canSend() || properties.agreementTemplate.isBlank()) {
			note("Agreement: not sent (needs PORTAL_ADAPTERS_DOCUMENSO_* with WRITE=true and PORTAL_SANDBOXE2E_AGREEMENTTEMPLATE); the signature is recorded by the suite")
			return null
		}
		dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, "project").set(AGREEMENT_TEMPLATE.NAME, "Project agreement (sandbox)")
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, properties.agreementTemplate.trim()).set(AGREEMENT_TEMPLATE.VERSION, 1).execute()
		agreements.pass()
		val a = dsl.selectFrom(AGREEMENT).where(AGREEMENT.PERSON_ID.eq(signer), AGREEMENT.PROJECT_ID.eq(projectId)).fetchOne()
		check("DOCUMENSO", "agreement: sent", a?.status == "sent", a?.error ?: "")
		if (a?.status != "sent") return null
		val envelope = try { client.envelope(a.documensoDocumentId!!) } catch (e: AdapterException) { null }
		check("DOCUMENSO", "agreement: Documenso has it waiting for a signature", envelope?.status == "PENDING" && envelope.signers.isNotEmpty(), envelope?.status ?: "couldn't read it")
		return a.documensoDocumentId
	}

	/** The signer signs: by hand in Documenso (waiting up to [SandboxE2EProperties.waitForSignature]), or recorded by the suite. */
	private fun signPhase(projectId: UUID, signer: UUID, envelope: String?): Boolean {
		fun signed() = dsl.select(PROJECT_MEMBER.AGREEMENT_STATUS).from(PROJECT_MEMBER)
			.where(PROJECT_MEMBER.PROJECT_ID.eq(projectId), PROJECT_MEMBER.PERSON_ID.eq(signer), PROJECT_MEMBER.REMOVED_AT.isNull).fetchSingle().value1() == "signed"
		fun record(why: String): Boolean {
			dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").where(PROJECT_MEMBER.PROJECT_ID.eq(projectId), PROJECT_MEMBER.PERSON_ID.eq(signer)).execute()
			note("Sign: $why; the suite records the signature so the release is checked")
			return true
		}
		if (envelope == null) return record("no Documenso")
		var wait = properties.waitForSignature
		if (properties.signLocally) {
			val base = adapterProperties.documenso.baseUrl.trimEnd('/')
			if ("//localhost" !in base && "//127.0.0.1" !in base) return record("not signed: sign-locally only signs in the local sandbox Documenso, not $base")
			val ok = try { signLocally(base, envelope); true } catch (e: AdapterException) { note("sign locally: ${e.message}"); false }
			check("DOCUMENSO", "sign: signed in the local Documenso, as its signing page does", ok)
			if (!ok) return false
			wait = wait.takeUnless { it.isZero } ?: Duration.ofMinutes(2) // sealing takes a few seconds
		} else {
			if (wait.isZero) return record("not signed by hand (scripts/sandbox-e2e.sh --sign waits for it)")
			val link = try { documenso.ifAvailable!!.envelope(envelope).signers.firstOrNull()?.signingUrl } catch (e: AdapterException) { null }
			log.info("Sandbox suite: sign the test agreement now: {} (or the email in Mailpit, http://localhost:8025). Waiting up to {}.", link, wait)
		}
		val until = System.currentTimeMillis() + wait.toMillis()
		while (!signed() && System.currentTimeMillis() < until) {
			Thread.sleep(if (properties.signLocally) 3_000 else 10_000)
			agreements.pass()
		}
		check("DOCUMENSO", "sign: signed, and the portal saw it", signed())
		if (!signed()) return false
		val pdf = dsl.select(AGREEMENT.PDF_LOCATION).from(AGREEMENT).where(AGREEMENT.DOCUMENSO_DOCUMENT_ID.eq(envelope)).fetchSingle().value1()
		check("DOCUMENSO", "sign: the signed PDF is kept in the portal", pdf == "database")
		return true
	}

	/**
	 * Everyone the run invited to the collection by email accepts; the next run confirms them (shares the organization
	 * key) and they're members. A local Vaultwarden without mail accepts at once for registered users.
	 */
	private fun vaultwardenPhase(
		team: List<UUID>, names: Map<UUID, String>, confirmedEarlier: Int,
		relist: (Tool) -> List<org.hack4impact.portal.adapters.ToolAccount>, accountsOf: (Tool, UUID) -> Set<String>,
		membersIn: (Tool) -> Set<String>?, has: (Tool, UUID, Set<String>?) -> Boolean,
	) {
		val tool = Tool.VAULTWARDEN
		val invited = dsl.selectDistinct(SYNC_CHANGE.PERSON_ID).from(SYNC_CHANGE).where(SYNC_CHANGE.KIND.eq("add"), SYNC_CHANGE.OUTCOME.eq("invited"))
			.fetch().mapNotNull { it.value1() }.filter { it in team }
		if (invited.isEmpty()) {
			note("Vaultwarden: nobody invited by email (give a fixture person an email and no vaultwarden account to check accept and confirm)")
			return
		}
		fun states() = invited.associateWith { p -> relist(tool).filter { it.externalId in accountsOf(tool, p) }.map { it.state }.toSet() }
		val until = System.currentTimeMillis() + properties.waitForSignature.toMillis()
		while (states().values.any { AccountState.ACCEPTED !in it && AccountState.ACTIVE !in it } && System.currentTimeMillis() < until) Thread.sleep(10_000)
		val notAccepted = states().filterValues { AccountState.ACCEPTED !in it && AccountState.ACTIVE !in it }.keys
		check(tool, "invite: ${invited.size - notAccepted.size} of ${invited.size} accepted", notAccepted.isEmpty(),
			notAccepted.joinToString { "${names[it]} hasn't accepted (Mailpit, http://localhost:8025)" })
		val r = engine.run("manual", setOf(tool)).single()
		val confirmed = states().filterValues { AccountState.ACTIVE in it }.keys
		val there = membersIn(tool)
		check(tool, "confirm: ${confirmedEarlier + r.confirmed} confirmed (${confirmedEarlier} by the apply run, ${r.confirmed} by this one), and ${confirmed.size} of ${invited.size} are members of the collection",
			r.status == "completed" && confirmedEarlier + r.confirmed >= invited.size - notAccepted.size && confirmed.containsAll(invited - notAccepted) &&
				(invited - notAccepted).all { has(tool, it, there) },
			r.error ?: "")
	}
}
