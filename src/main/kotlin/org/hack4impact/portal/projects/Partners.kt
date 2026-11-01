package org.hack4impact.portal.projects

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.memberdb.MemberDb
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The member database's projects with no engagement yet (decision 168), read live: a new project may link to one so
 * the member database keeps one row per project. Those a portal project already linked are left out.
 */
@Component
class PastProjects(private val memberDb: MemberDb, private val dsl: DSLContext) {
	fun list(): List<PastProject> {
		val linked = dsl.select(PROJECT.MEMBER_DB_PROJECT_ID).from(PROJECT).where(PROJECT.MEMBER_DB_LINKED.isTrue).fetch(PROJECT.MEMBER_DB_PROJECT_ID).filterNotNull().toSet()
		return read {
			memberDb.jdbc.sql(
				"""
				SELECT p.id, p.name FROM projects p
				WHERE p.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM project_engagements e WHERE e.project_id = p.id)
				ORDER BY p.name
				""".trimIndent(),
			).query { rs, _ -> PastProject(rs.getObject("id", UUID::class.java), rs.getString("name").trim()) }.list()
		}.filter { it.id !in linked }
	}

	/** Whether [id] may be linked to a project now: a past project without an engagement, not linked by another portal project. */
	fun available(id: UUID, except: UUID? = null): Boolean {
		val takenElsewhere = dsl.fetchExists(PROJECT, PROJECT.MEMBER_DB_LINKED.isTrue, PROJECT.MEMBER_DB_PROJECT_ID.eq(id), except?.let { PROJECT.ID.ne(it) } ?: DSL.noCondition())
		if (takenElsewhere) return false
		return read {
			memberDb.jdbc.sql(
				"SELECT count(*) FROM projects p WHERE p.id = ? AND p.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM project_engagements e WHERE e.project_id = p.id)",
			).param(id).query(Long::class.java).single() == 1L
		}
	}

	private fun <T> read(block: () -> T): T {
		if (!memberDb.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The member database isn't connected")
		return try {
			block()
		} catch (e: org.springframework.dao.DataAccessException) {
			throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The member database can't be reached right now")
		}
	}
}

/**
 * Partners a project is for: imported from the member database, or added by national. Leads pick from the list, so a
 * typo can't become a permanent member-database row (wiki: staged-rollout-stage-2, member database writes).
 */
@RestController
@RequestMapping("/api")
class PartnerController(private val viewers: Viewers, private val dsl: DSLContext) {
	private val json = JsonMapper.builder().build()

	/** Partners to pick from, by name. Chapter leads, co-leads and national. */
	@GetMapping("/partners")
	fun list(@AuthenticationPrincipal user: OidcUser): List<PartnerOption> {
		val viewer = viewers.of(user)
		if (!viewer.nationalAdmin && viewer.chapterRoles.values.none { it in setOf("lead", "co_lead") }) throw AccessDeniedException("Leads only")
		return options()
	}

	/** Adds a partner a lead asked for. National only. */
	@PostMapping("/national/partners")
	@Transactional
	fun add(@AuthenticationPrincipal user: OidcUser, @RequestBody input: PartnerInput): PartnerOption {
		val viewer = viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }
		val name = input.name.trim().ifEmpty { throw ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A partner needs a name") }
		if (dsl.fetchExists(PARTNER, DSL.lower(PARTNER.NAME).eq(name.lowercase()))) throw ResponseStatusException(HttpStatus.CONFLICT, "There's already a partner called $name")
		val id = dsl.insertInto(PARTNER).set(PARTNER.NAME, name).set(PARTNER.WEBSITE, input.website?.trim()?.ifEmpty { null })
			.returningResult(PARTNER.ID).fetchSingle().value1()!!
		audit(viewer, id, name)
		return options().first { it.id == id }
	}

	private fun options(): List<PartnerOption> =
		dsl.select(PARTNER.ID, PARTNER.NAME, PARTNER.WEBSITE).from(PARTNER).where(PARTNER.DELETED_AT.isNull).orderBy(PARTNER.NAME)
			.fetch { PartnerOption(it.value1()!!, it.value2()!!, it.value3()) }

	private fun audit(viewer: Viewer, id: UUID, name: String) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, "partner.add").set(AUDIT_EVENT.TARGET_TYPE, "partner").set(AUDIT_EVENT.TARGET_ID, id)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(mapOf("name" to name))))
			.execute()
	}
}

/** Member-database projects a lead may link a new project to (decision 168). Leads, co-leads and national. */
@RestController
@RequestMapping("/api/chapters/{code}/past-projects")
class PastProjectController(private val access: org.hack4impact.portal.chapters.ChapterAccess, private val past: PastProjects) {
	@GetMapping
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<PastProject> {
		access.manage(user, code)
		return past.list()
	}
}
