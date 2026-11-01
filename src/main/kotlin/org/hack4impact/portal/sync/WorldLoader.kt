package org.hack4impact.portal.sync

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
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Audience
import org.hack4impact.portal.resolver.ChapterAudience
import org.hack4impact.portal.resolver.ChapterResource
import org.hack4impact.portal.resolver.ChapterRole
import org.hack4impact.portal.resolver.ChapterRoleAssignment
import org.hack4impact.portal.resolver.Person
import org.hack4impact.portal.resolver.PersonStatus
import org.hack4impact.portal.resolver.Project
import org.hack4impact.portal.resolver.ProjectMember
import org.hack4impact.portal.resolver.ProjectResource
import org.hack4impact.portal.resolver.ProjectRole
import org.hack4impact.portal.resolver.ProjectStatus
import org.hack4impact.portal.resolver.Resource
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.resolver.World
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/** The resolver's input built from the portal's tables, plus anything that couldn't be read. */
data class LoadedWorld(val world: World, val warnings: List<String>)

/**
 * Reads the portal's data into the resolver's [World]. Only current rows count: live chapter memberships, roles without
 * an end, members not removed. A malformed access level or a resource of an unknown tool is skipped with a warning
 * rather than failing the whole sync. Ported from the full portal for stage 2: no automation rules (stage 3; the
 * resolver gets an empty list) and no national projects, so a resource's chapter is used as it is.
 */
@Component
class WorldLoader(private val dsl: DSLContext, private val tools: ToolRegistry) {

	fun load(): LoadedWorld {
		val warnings = mutableListOf<String>()

		val memberships = dsl.select(CHAPTER_MEMBERSHIP.PERSON_ID, CHAPTER_MEMBERSHIP.CHAPTER_ID).from(CHAPTER_MEMBERSHIP)
			.join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.LEFT_ON.isNull, CHAPTER.DELETED_AT.isNull)
			.fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		val roles = dsl.select(CHAPTER_ROLE.PERSON_ID, CHAPTER_ROLE.CHAPTER_ID, CHAPTER_ROLE.ROLE, CHAPTER_ROLE.TITLE).from(CHAPTER_ROLE)
			.where(CHAPTER_ROLE.ENDS_AT.isNull)
			.fetch().groupBy({ it.value1()!! }, { ChapterRoleAssignment(it.value2()!!, ChapterRole.valueOf(it.value3()!!.uppercase()), it.value4()) })
		val people = dsl.select(PERSON.ID, PERSON.STATUS).from(PERSON).where(PERSON.DELETED_AT.isNull).fetch {
			val id = it.value1()!!
			Person(id, PersonStatus.valueOf(it.value2()!!.uppercase()), memberships[id].orEmpty().toSet(), roles[id].orEmpty().toSet())
		}

		val projects = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, PROJECT.STATUS, PROJECT.TAGS).from(PROJECT).fetch {
			Project(it.value1()!!, it.value2()!!, ProjectStatus.valueOf(it.value3()!!.uppercase()), it.value4().orEmpty().filterNotNull().toSet())
		}
		val projectRoles = dsl.select(PROJECT_ROLE.ID, PROJECT_ROLE.IS_LEAD).from(PROJECT_ROLE).fetch { ProjectRole(it.value1()!!, it.value2()!!) }
		val members = dsl.select(PROJECT_MEMBER.PROJECT_ID, PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.PROJECT_ROLE_ID, PROJECT_MEMBER.AGREEMENT_STATUS)
			.from(PROJECT_MEMBER).where(PROJECT_MEMBER.REMOVED_AT.isNull)
			.fetch { ProjectMember(it.value1()!!, it.value2()!!, it.value3(), it.value4() in setOf("signed", "waived")) }

		val resources = dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.CHAPTER_ID, RESOURCE.TAGS, RESOURCE.ARCHIVED_AT).from(RESOURCE).fetch().mapNotNull {
			// A tool no code knows (a removed plugin): its resources are left out with a warning, not the whole sync.
			val tool = Tool.fromKey(it.value2()) ?: run { warnings += "resource ${it.value1()} skipped: unknown tool '${it.value2()}'"; return@mapNotNull null }
			Resource(it.value1()!!, tool, it.value3(), it.value4().orEmpty().filterNotNull().toSet(), it.value5() != null)
		}
		val projectResources = dsl.selectFrom(PROJECT_RESOURCE).fetch().mapNotNull { pr ->
			val access = access(pr.accessLevel, "project resource ${pr.projectId}/${pr.resourceId}", warnings) ?: return@mapNotNull null
			val audience = when (pr.audience) {
				"team" -> Audience.Team
				"leads" -> Audience.Leads
				else -> Audience.Role(pr.audienceRoleId!!)
			}
			ProjectResource(pr.projectId!!, pr.resourceId!!, audience, access, pr.requiresAgreement == true)
		}
		val chapterResources = dsl.selectFrom(CHAPTER_RESOURCE).fetch().mapNotNull { cr ->
			val access = access(cr.accessLevel, "chapter resource ${cr.chapterId}/${cr.resourceId}", warnings) ?: return@mapNotNull null
			ChapterResource(cr.chapterId!!, cr.resourceId!!, ChapterAudience.valueOf(cr.audience!!.uppercase()), access)
		}
		return LoadedWorld(World(people, projects, projectRoles, members, resources, projectResources, chapterResources, rules = emptyList(), sensitiveTools = tools.sensitive), warnings)
	}

	private fun access(value: String?, what: String, warnings: MutableList<String>): Access? =
		Access.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: run {
			warnings += "$what skipped: unknown access level '$value' (use read, write or admin)"
			null
		}
}
