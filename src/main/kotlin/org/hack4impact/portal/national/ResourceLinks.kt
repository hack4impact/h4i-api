package org.hack4impact.portal.national

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.chapters.ChapterQueries
import org.hack4impact.portal.db.tables.records.ResourceRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.AccountMatcher
import org.hack4impact.portal.sync.ExistingResources
import org.hack4impact.portal.sync.LinkPreview
import org.hack4impact.portal.sync.LinkTarget
import org.hack4impact.portal.sync.SyncRequests
import org.hack4impact.portal.sync.WorldLoader
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** A chapter's resource as national sees it: whether the portal manages it, what it's linked to, whether the tool has the name. */
data class NationalResource(
	val id: UUID,
	val tool: String,
	val name: String,
	val externalId: String?,
	/** portal (the portal made it), adopted (national linked an existing one), unmanaged (left alone). */
	val managed: String,
	/** project_team, chapter_members or chapter_leads; null = linked to nothing. */
	val target: String?,
	val projectSlug: String?,
	val access: String?,
	/** The tool already has its name and national hasn't decided yet (link, rename or unmanaged). */
	val existsInTool: Boolean,
)

data class LinkedResource(val tool: String, val name: String, val externalId: String)

/** [verdict]: already_there (should have it and has it), will_add, kept (has it, not in the target), unknown_account (no portal person). */
data class LinkPerson(val personId: UUID?, val account: String, val name: String?, val verdict: String)

data class LinkPreviewResult(val resource: LinkedResource, val people: List<LinkPerson>, val willAdd: Int)

/** [action]: link, rename or unmanaged. [target] and [confirmedAdds] for link; [newName] for rename. */
data class LinkDecision(
	val action: String,
	val target: String? = null,
	val projectSlug: String? = null,
	val access: String? = null,
	val confirmedAdds: Int? = null,
	val newName: String? = null,
)

/** One of [name] or [externalId]: an existing channel, group or team the portal doesn't know yet. */
data class StartLink(val tool: String, val name: String? = null, val externalId: String? = null)

/**
 * National links existing channels, groups and teams to projects and chapters (wiki: staged-rollout-stage-2, decision
 * 170): previews who a link would add and keep, then links, renames the portal's, or leaves it unmanaged. A linked
 * resource only gains people: everyone already in it stays, since the portal removes only what it gave.
 */
@Component
class ResourceLinks(
	private val dsl: DSLContext,
	private val adapters: List<ReadAdapter>,
	private val worlds: WorldLoader,
	private val chapters: ChapterQueries,
	private val tools: ToolRegistry,
	private val queue: AdminQueue,
	private val syncs: SyncRequests,
) {
	private val json = JsonMapper.builder().build()

	private data class Target(val target: LinkTarget, val chapterId: UUID, val projectId: UUID?, val access: Access)

	fun list(code: String): List<NationalResource> {
		val chapter = chapter(code)
		val resources = dsl.selectFrom(RESOURCE).where(RESOURCE.CHAPTER_ID.eq(chapter.id), RESOURCE.ARCHIVED_AT.isNull).orderBy(RESOURCE.TOOL, RESOURCE.NAME).fetch()
		val ids = resources.map { it.id!! }
		val chapterLinks = dsl.selectFrom(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.`in`(ids)).fetch().associateBy { it.resourceId }
		val projectLinks = dsl.select(PROJECT_RESOURCE.RESOURCE_ID, PROJECT.SLUG, PROJECT_RESOURCE.ACCESS_LEVEL).from(PROJECT_RESOURCE)
			.join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID)).where(PROJECT_RESOURCE.RESOURCE_ID.`in`(ids)).orderBy(PROJECT.SLUG)
			.fetch().groupBy { it.value1() }
		return resources.map { r ->
			val cr = chapterLinks[r.id]
			val pr = projectLinks[r.id]?.firstOrNull()
			NationalResource(
				r.id!!, r.tool!!, r.name!!, r.externalId, r.managed!!,
				target = when {
					cr != null -> if (cr.audience == "leads") LinkTarget.CHAPTER_LEADS.key else LinkTarget.CHAPTER_MEMBERS.key
					pr != null -> LinkTarget.PROJECT_TEAM.key
					else -> null
				},
				projectSlug = pr?.value2(), access = cr?.accessLevel ?: pr?.value3(),
				existsInTool = r.existsInToolAt != null && r.externalId == null && r.managed != "unmanaged",
			)
		}
	}

	/** Records a resource national names in a tool, unmanaged until national decides, ready to preview. */
	@Transactional
	fun start(code: String, input: StartLink, viewer: Viewer): NationalResource {
		val chapter = chapter(code)
		if ((input.name.isNullOrBlank()) == (input.externalId.isNullOrBlank())) throw unprocessable("Send either name or externalId")
		val tool = Tool.fromKey(input.tool)?.takeIf { tools[it].syncs } ?: throw unprocessable("No syncing tool ${input.tool}")
		val listing = read(tool) { adapter(tool).resources() }.filterNot { it.archived }
		val found = (input.externalId?.takeIf { it.isNotBlank() }?.let { id -> listing.firstOrNull { it.externalId == id } }
			?: input.name?.takeIf { it.isNotBlank() }?.let { ExistingResources.find(tool, it, listing) })
			?: throw notListed(tool, input.name ?: input.externalId!!)
		if (dsl.fetchExists(RESOURCE, RESOURCE.TOOL.eq(tool.key()), RESOURCE.EXTERNAL_ID.eq(found.externalId))) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "${found.name} is already linked to a portal resource")
		}
		// Started already and not decided: the same row, not a second one.
		dsl.selectFrom(RESOURCE).where(RESOURCE.TOOL.eq(tool.key()), RESOURCE.FOUND_EXTERNAL_ID.eq(found.externalId), RESOURCE.EXTERNAL_ID.isNull, RESOURCE.CHAPTER_ID.eq(chapter.id))
			.fetchOne()?.let { existing -> return list(code).first { it.id == existing.id } }
		val id = dsl.insertInto(RESOURCE)
			.set(RESOURCE.TOOL, tool.key()).set(RESOURCE.NAME, ExistingResources.nameOf(tool, found)).set(RESOURCE.CHAPTER_ID, chapter.id).set(RESOURCE.MANAGED, "unmanaged")
			.set(RESOURCE.EXISTS_IN_TOOL_AT, OffsetDateTime.now()).set(RESOURCE.FOUND_EXTERNAL_ID, found.externalId)
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!
		audit(viewer, "resource.link_started", id, chapter.id, mapOf("tool" to tool.key(), "name" to found.name, "externalId" to found.externalId))
		return list(code).first { it.id == id }
	}

	fun preview(id: UUID, target: String?, projectSlug: String?, access: String?): LinkPreviewResult {
		val resource = resource(id)
		val tool = toolOf(resource)
		val to = target(resource, target, projectSlug, access)
		val externalId = externalIdOf(resource, tool)
		val adapter = adapter(tool)
		val members = read(tool) {
			try {
				adapter.members(externalId)
			} catch (e: NotFound) {
				throw ResponseStatusException(HttpStatus.NOT_FOUND, "${resource.name} is no longer in ${tools[tool].name}")
			}
		}
		val accounts = read(tool) { adapter.accounts() }.filter { it.externalId != null }.associateBy { it.externalId!! }
		val matcher = AccountMatcher.load(dsl, tool)
		val present = members.filterNot { it.implicit }.map { m ->
			val account = accounts[m.accountId]
			val login = m.login ?: account?.login ?: account?.email
			Triple(m.accountId, login, matcher.match(m.accountId, login, account?.email)?.personId)
		}
		val wouldHave = LinkPreview.wouldHave(worlds.load().world, id, to.target, to.projectId, to.access)
		val there = present.mapNotNull { it.third }.toSet()
		val names = names(there + wouldHave)
		val people = present.map { (accountId, login, person) ->
			val verdict = when {
				person == null -> "unknown_account"
				person in wouldHave -> "already_there"
				else -> "kept"
			}
			LinkPerson(person, login ?: accountId, person?.let { names[it] }, verdict)
		} + (wouldHave - there).map { LinkPerson(it, names[it] ?: it.toString(), names[it], "will_add") }
		val order = listOf("will_add", "already_there", "kept", "unknown_account")
		return LinkPreviewResult(
			LinkedResource(tool.key(), resource.name!!, externalId),
			people.sortedWith(compareBy({ order.indexOf(it.verdict) }, { it.name ?: it.account })),
			people.count { it.verdict == "will_add" },
		)
	}

	@Transactional
	fun decide(id: UUID, decision: LinkDecision, viewer: Viewer): NationalResource {
		val resource = resource(id, lock = true)
		val tool = toolOf(resource)
		when (decision.action) {
			"link" -> link(resource, tool, decision, viewer)
			"rename" -> rename(resource, tool, decision.newName, viewer)
			"unmanaged" -> {
				// A resource the portal created and manages isn't waiting for a decision: one request mustn't drop it (review L4).
				if (resource.managed == "portal" && resource.externalId != null) throw unprocessable("The portal created ${resource.name}: there's nothing to decide about it")
				dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(id)).execute()
				audit(viewer, "resource.unmanaged", id, resource.chapterId, mapOf("name" to resource.name))
			}
			else -> throw unprocessable("action must be link, rename or unmanaged")
		}
		queue.resourceDecided(id, viewer)
		return list(chapterCode(resource)).first { it.id == id }
	}

	private fun link(resource: ResourceRecord, tool: Tool, decision: LinkDecision, viewer: Viewer) {
		if (decision.target == null || decision.confirmedAdds == null) throw unprocessable("A link needs target and confirmedAdds")
		// Detached resources stay adopted until their removals are done, and may be linked again.
		val attached = dsl.fetchExists(CHAPTER_RESOURCE, CHAPTER_RESOURCE.RESOURCE_ID.eq(resource.id)) || dsl.fetchExists(PROJECT_RESOURCE, PROJECT_RESOURCE.RESOURCE_ID.eq(resource.id))
		if (resource.managed == "adopted" && attached) throw unprocessable("${resource.name} is already linked: detach it first")
		// Linking one the portal created would make it adopted, so it would no longer be archived on close (review L4).
		if (resource.managed == "portal" && resource.externalId != null) throw unprocessable("The portal created ${resource.name}: there's nothing to link")
		val to = target(resource, decision.target, decision.projectSlug, decision.access)
		val preview = preview(resource.id!!, decision.target, decision.projectSlug, decision.access)
		if (preview.willAdd != decision.confirmedAdds) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "The link would now add ${preview.willAdd} people, not ${decision.confirmedAdds}. Preview it again.")
		}
		val externalId = preview.resource.externalId
		if (dsl.fetchExists(RESOURCE, RESOURCE.TOOL.eq(tool.key()), RESOURCE.EXTERNAL_ID.eq(externalId), RESOURCE.ID.ne(resource.id))) {
			throw ResponseStatusException(HttpStatus.CONFLICT, "${resource.name} is already linked to another portal resource")
		}
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, externalId).set(RESOURCE.FOUND_EXTERNAL_ID, externalId).set(RESOURCE.MANAGED, "adopted")
			.where(RESOURCE.ID.eq(resource.id)).execute()
		when (to.target) {
			// A template resource keeps its audience, level and gate; one national started gets the team, and the gate when sensitive.
			LinkTarget.PROJECT_TEAM -> dsl.insertInto(PROJECT_RESOURCE)
				.set(PROJECT_RESOURCE.PROJECT_ID, to.projectId).set(PROJECT_RESOURCE.RESOURCE_ID, resource.id).set(PROJECT_RESOURCE.AUDIENCE, "team")
				.set(PROJECT_RESOURCE.ACCESS_LEVEL, to.access.name.lowercase()).set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, tools[tool].sensitive)
				.onConflictDoNothing().execute()
			LinkTarget.CHAPTER_MEMBERS, LinkTarget.CHAPTER_LEADS -> dsl.insertInto(CHAPTER_RESOURCE)
				.set(CHAPTER_RESOURCE.CHAPTER_ID, to.chapterId).set(CHAPTER_RESOURCE.RESOURCE_ID, resource.id)
				.set(CHAPTER_RESOURCE.AUDIENCE, if (to.target == LinkTarget.CHAPTER_LEADS) "leads" else "members")
				.set(CHAPTER_RESOURCE.ACCESS_LEVEL, to.access.name.lowercase())
				.onConflict(CHAPTER_RESOURCE.CHAPTER_ID, CHAPTER_RESOURCE.RESOURCE_ID).doUpdate()
				.set(CHAPTER_RESOURCE.AUDIENCE, if (to.target == LinkTarget.CHAPTER_LEADS) "leads" else "members")
				.set(CHAPTER_RESOURCE.ACCESS_LEVEL, to.access.name.lowercase())
				.execute()
		}
		audit(viewer, "resource.linked", resource.id, resource.chapterId, mapOf(
			"name" to resource.name, "externalId" to externalId, "target" to to.target.key, "projectId" to to.projectId?.toString(), "willAdd" to preview.willAdd,
		))
		// The run starts once this commits, so what's applied is what national confirmed.
		syncs.request("change", setOf(tool))
	}

	private fun rename(resource: ResourceRecord, tool: Tool, newName: String?, viewer: Viewer) {
		if (newName.isNullOrBlank()) throw unprocessable("A rename needs newName")
		if (resource.managed != "portal" || resource.externalId != null) throw unprocessable("Only a resource the portal was about to create can be renamed")
		val descriptor = tools[tool]
		val name = newName.trim().let { if (descriptor.lowercaseNames) it.trimStart('#').lowercase() else it }
		val rule = descriptor.naming ?: throw unprocessable("The portal doesn't create ${descriptor.name} resources")
		if (!rule.pattern.matches(name)) throw unprocessable("A ${descriptor.resourceLabel} name must be ${rule.hint}")
		dsl.update(RESOURCE).set(RESOURCE.NAME, name).setNull(RESOURCE.EXISTS_IN_TOOL_AT).setNull(RESOURCE.FOUND_EXTERNAL_ID)
			.set(RESOURCE.CREATE_ATTEMPTS, 0).setNull(RESOURCE.CREATE_RETRY_AT).where(RESOURCE.ID.eq(resource.id)).execute()
		audit(viewer, "resource.renamed", resource.id, resource.chapterId, mapOf("from" to resource.name, "to" to name))
		syncs.request("change", setOf(tool))
	}

	/**
	 * [mode] detach: the resource is no longer any project's or chapter's, so the next run takes back what the portal
	 * added and leaves everyone who was already there. unmanaged: the portal stops touching it and removes no one.
	 */
	@Transactional
	fun remove(id: UUID, mode: String, viewer: Viewer) {
		val resource = resource(id, lock = true)
		val chapterLinked = dsl.fetchExists(CHAPTER_RESOURCE, CHAPTER_RESOURCE.RESOURCE_ID.eq(id))
		if (resource.managed != "adopted" && !chapterLinked) throw unprocessable("${resource.name} isn't a linked or chapter resource")
		if (mode !in setOf("detach", "unmanaged")) throw unprocessable("mode must be detach or unmanaged")
		dsl.deleteFrom(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(id)).execute()
		dsl.deleteFrom(PROJECT_RESOURCE).where(PROJECT_RESOURCE.RESOURCE_ID.eq(id)).execute()
		if (mode == "unmanaged") dsl.update(RESOURCE).set(RESOURCE.MANAGED, "unmanaged").where(RESOURCE.ID.eq(id)).execute()
		audit(viewer, "resource.$mode", id, resource.chapterId, mapOf("name" to resource.name))
		if (mode == "detach") syncs.request("change", setOf(toolOf(resource)))
	}

	// Helpers --------------------------------------------------------------------------------

	private fun target(resource: ResourceRecord, target: String?, projectSlug: String?, access: String?): Target {
		val to = LinkTarget.of(target) ?: throw unprocessable("target must be project_team, chapter_members or chapter_leads")
		val level = (access ?: "write").let { a -> Access.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } } ?: throw unprocessable("access must be read, write or admin")
		val chapterId = resource.chapterId ?: throw unprocessable("${resource.name} belongs to no chapter")
		if (to != LinkTarget.PROJECT_TEAM) return Target(to, chapterId, null, level)
		if (projectSlug.isNullOrBlank()) throw unprocessable("A project team needs projectSlug")
		val project = dsl.select(PROJECT.ID).from(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.SLUG.eq(projectSlug)).fetchOne()?.value1()
			?: throw unprocessable("No project $projectSlug in this resource's chapter")
		return Target(to, chapterId, project, level)
	}

	/** The existing one's ID: what the listing found, or the linked one's, or looked up again by name (a private channel since the app was invited). */
	private fun externalIdOf(resource: ResourceRecord, tool: Tool): String {
		resource.externalId?.let { return it }
		resource.foundExternalId?.let { return it }
		val found = ExistingResources.find(tool, resource.name!!, read(tool) { adapter(tool).resources() }) ?: throw notListed(tool, resource.name!!)
		dsl.update(RESOURCE).set(RESOURCE.FOUND_EXTERNAL_ID, found.externalId).where(RESOURCE.ID.eq(resource.id)).execute()
		return found.externalId
	}

	private fun notListed(tool: Tool, what: String) =
		ResponseStatusException(HttpStatus.NOT_FOUND, "${tools[tool].name} doesn't list $what.${AdminQueue.unlistedHint(tool)}")

	private fun <T> read(tool: Tool, call: () -> T): T = try {
		call()
	} catch (e: ResponseStatusException) {
		throw e
	} catch (e: AdapterException) {
		throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Couldn't read ${tools[tool].name}: ${e.message}")
	}

	private fun adapter(tool: Tool): ReadAdapter =
		adapters.firstOrNull { it.tool == tool } ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The portal isn't connected to ${tools[tool].name}")

	private fun resource(id: UUID, lock: Boolean = false): ResourceRecord =
		dsl.selectFrom(RESOURCE).where(RESOURCE.ID.eq(id)).let { if (lock) it.forUpdate() else it }.fetchOne()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such resource")

	private fun toolOf(resource: ResourceRecord): Tool = Tool.fromKey(resource.tool) ?: throw unprocessable("Unknown tool ${resource.tool}")

	private fun chapter(code: String) = chapters.byCode(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")

	private fun chapterCode(resource: ResourceRecord): String =
		dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(resource.chapterId)).fetchSingle().value1()!!

	private fun names(ids: Set<UUID>): Map<UUID, String> =
		dsl.select(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.`in`(ids))
			.fetchMap({ it.value1()!! }, { "${it.value4()?.takeIf { p -> p.isNotBlank() } ?: it.value2()} ${it.value3()}" })

	private fun Tool.key() = name.lowercase()

	private fun unprocessable(message: String) = ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message)

	private fun audit(viewer: Viewer, action: String, id: UUID?, chapterId: UUID?, after: Map<String, Any?>) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "resource").set(AUDIT_EVENT.TARGET_ID, id).set(AUDIT_EVENT.CHAPTER_ID, chapterId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(after)))
			.execute()
	}
}
