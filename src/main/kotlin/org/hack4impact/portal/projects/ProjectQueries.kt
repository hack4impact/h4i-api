package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.PlannedChange
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.resolver.ProjectStatus
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.WithheldReason
import org.hack4impact.portal.sync.WorldLoader
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/** Reads for the projects screens (stage 2, from the full portal's; no Notion pages, repositories or national projects). */
@Component
class ProjectQueries(
	private val dsl: DSLContext, private val worlds: WorldLoader, private val activity: ProjectActivity,
	private val links: org.hack4impact.portal.adapters.ToolLinks,
) {
	/** Who could join the team: the chapter's active members and alumni not on it yet (PRD: alumni can join). */
	fun candidates(chapterId: UUID, projectId: UUID): List<TeamCandidate> {
		val notOnTeam = DSL.notExists(DSL.selectOne().from(PROJECT_MEMBER).where(PROJECT_MEMBER.PROJECT_ID.eq(projectId), PROJECT_MEMBER.PERSON_ID.eq(PERSON.ID), PROJECT_MEMBER.REMOVED_AT.isNull))
		val eligible = PERSON.DELETED_AT.isNull.and(PERSON.STATUS.`in`("active", "alumni", "removal_requested")).and(notOnTeam)
		return dsl.selectDistinct(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.STATUS).from(PERSON)
			.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
			.where(CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, eligible)
			.fetch { TeamCandidate(it.value1()!!, name(it.value4(), it.value2()!!, it.value3()!!), it.value5()!!) }
			.sortedBy { it.name }
	}

	fun byId(id: UUID): ProjectRecord? = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(id)).fetchOne()

	fun bySlug(chapterId: UUID, slug: String): ProjectRecord? =
		dsl.selectFrom(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.SLUG.eq(slug.lowercase())).fetchOne()

	fun list(chapterId: UUID): List<ProjectSummary> {
		val members = DSL.selectCount().from(PROJECT_MEMBER).where(PROJECT_MEMBER.PROJECT_ID.eq(PROJECT.ID), PROJECT_MEMBER.REMOVED_AT.isNull).asField<Int>()
		val resources = DSL.selectCount().from(PROJECT_RESOURCE).where(PROJECT_RESOURCE.PROJECT_ID.eq(PROJECT.ID)).asField<Int>()
		val order = DSL.case_(PROJECT.STATUS).`when`("active", 0).`when`("paused", 1).`when`("draft", 2).else_(3)
		return dsl.select(PROJECT.ID, PROJECT.NAME, PROJECT.SLUG, PROJECT.STATUS, PROJECT.TYPE, PROJECT.TAGS, TERM.SEASON, TERM.YEAR, PARTNER.NAME, members, resources, PROJECT.UPDATED_AT)
			.from(PROJECT).leftJoin(TERM).on(TERM.ID.eq(PROJECT.TERM_ID)).leftJoin(PARTNER).on(PARTNER.ID.eq(PROJECT.PARTNER_ID))
			.where(PROJECT.CHAPTER_ID.eq(chapterId))
			.orderBy(order, PROJECT.NAME)
			.fetch {
				ProjectSummary(
					it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5(), it.value6()!!.filterNotNull(),
					termLabel(it.value7(), it.value8()?.toInt()), it.value9(), it.value10() ?: 0, it.value11() ?: 0, it.value12()!!,
				)
			}
	}

	/** [canEditTeam]: may change who's on the team (chapter leads, and the project's own leads). */
	fun detail(chapterId: UUID, project: ProjectRecord, canEdit: Boolean, canEditTeam: Boolean = canEdit): ProjectDetail {
		val id = project.id!!
		val term = project.termId?.let { t -> dsl.select(TERM.SEASON, TERM.YEAR).from(TERM).where(TERM.ID.eq(t)).fetchOne()?.let { termLabel(it.value1(), it.value2()?.toInt()) } }
		val partner = project.partnerId?.let { p -> dsl.select(PARTNER.NAME).from(PARTNER).where(PARTNER.ID.eq(p)).fetchOne()?.value1() }

		val members = dsl.select(
			PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL, PERSON.STATUS,
			PROJECT_ROLE.ID, PROJECT_ROLE.NAME, PROJECT_ROLE.IS_LEAD, PROJECT_MEMBER.AGREEMENT_STATUS,
		).from(PROJECT_MEMBER).join(PERSON).on(PERSON.ID.eq(PROJECT_MEMBER.PERSON_ID))
			.join(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID))
			.where(PROJECT_MEMBER.PROJECT_ID.eq(id), PROJECT_MEMBER.REMOVED_AT.isNull)
			.fetch {
				ProjectMemberRow(
					it.value1()!!, name(it.value4(), it.value2()!!, it.value3()!!), it.value5() ?: it.value6(), it.value7()!!,
					it.value8()!!, it.value9()!!, it.value10() == true, it.value11()!!,
				)
			}
			.sortedWith(compareBy({ !it.isLead }, { it.name }))

		// Who would get each resource. A draft project is previewed as if it were active.
		val loaded = worlds.load().world
		val asIfActive = project.status == "draft"
		val world = if (!asIfActive) loaded else loaded.copy(projects = loaded.projects.map { if (it.id == id) it.copy(status = ProjectStatus.ACTIVE) else it })
		val resolution = Resolver.resolve(world)

		val resourceRows = dsl.select(
			RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME, RESOURCE.EXTERNAL_ID, RESOURCE.ARCHIVED_AT, RESOURCE.TAGS,
			PROJECT_RESOURCE.AUDIENCE, PROJECT_ROLE.ID, PROJECT_ROLE.NAME, PROJECT_RESOURCE.ACCESS_LEVEL, PROJECT_RESOURCE.REQUIRES_AGREEMENT,
			RESOURCE.MANAGED, RESOURCE.EXISTS_IN_TOOL_AT,
		).from(PROJECT_RESOURCE).join(RESOURCE).on(RESOURCE.ID.eq(PROJECT_RESOURCE.RESOURCE_ID))
			.leftJoin(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_RESOURCE.AUDIENCE_ROLE_ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(id))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME)
			.fetch()
		val resourceIds = resourceRows.map { it.value1()!! }
		val shared = dsl.select(PROJECT_RESOURCE.RESOURCE_ID, PROJECT.NAME).from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
			.where(PROJECT_RESOURCE.RESOURCE_ID.`in`(resourceIds), PROJECT_RESOURCE.PROJECT_ID.ne(id))
			.fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		val syncCounts = if (resourceIds.isEmpty()) emptyMap() else dsl.select(SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.STATUS, DSL.count()).from(SYNC_RECORD)
			.where(SYNC_RECORD.RESOURCE_ID.`in`(resourceIds), SYNC_RECORD.DESIRED.eq("present"))
			.groupBy(SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.STATUS).fetch()
			.groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! })
			.mapValues { (_, counts) ->
				val c = counts.toMap()
				ResourceSync(c["in_sync"] ?: 0, c["waiting_on_member"] ?: 0, c["manual_task"] ?: 0, c["pending"] ?: 0, (c["failed"] ?: 0) + (c["dead_letter"] ?: 0))
			}
		val resources = resourceRows.map { r ->
			val rid = r.value1()!!
			ProjectResourceRow(
				rid, r.value2()!!, r.value3()!!,
				state = when {
					r.value5() != null -> "archived"
					r.value4() == null -> "to_create"
					else -> "exists"
				},
				managed = r.value12()!!,
				audience = r.value7()!!, roleId = r.value8(), role = r.value9(), access = r.value10()!!, requiresAgreement = r.value11()!!,
				tags = r.value6()!!.filterNotNull(), sharedWith = shared[rid].orEmpty().sorted(),
				existsInTool = r.value4() == null && r.value13() != null && r.value12() != "unmanaged",
				getting = resolution.grants.count { it.resourceId == rid },
				awaitingAgreement = resolution.withheld.count { it.resourceId == rid && it.why is WithheldReason.AwaitingAgreement },
				url = links.url(r.value2()!!, r.value3()!!, r.value4()),
				sync = syncCounts[rid] ?: ResourceSync(),
			)
		}
		val grants = resolution.grants.filter { it.resourceId in resourceIds }
		val preview = AccessPreview(
			people = grants.map { it.personId }.toSet().size, grants = grants.size,
			awaitingAgreement = resolution.withheld.count { it.resourceId in resourceIds && it.why is WithheldReason.AwaitingAgreement },
			asIfActive = asIfActive,
		)

		// The latest run of each tool (a dry run's plan, or what an applying run did), limited to this project's resources.
		val latest = dsl.selectFrom(SYNC_RUN).orderBy(SYNC_RUN.STARTED_AT.desc()).limit(500).fetch().filter { it.tool != null }.distinctBy { it.tool }
		val modes = latest.associate { it.id!! to it.mode!! }
		val planned = if (resourceIds.isEmpty() || latest.isEmpty()) emptyList() else dsl.select(
			SYNC_CHANGE.KIND, RESOURCE.TOOL, RESOURCE.NAME, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME,
			SYNC_CHANGE.ACCOUNT_ID, SYNC_CHANGE.FROM_ACCESS, SYNC_CHANGE.TO_ACCESS, SYNC_CHANGE.RUN_ID, SYNC_CHANGE.OUTCOME,
		).from(SYNC_CHANGE).join(RESOURCE).on(RESOURCE.ID.eq(SYNC_CHANGE.RESOURCE_ID)).leftJoin(PERSON).on(PERSON.ID.eq(SYNC_CHANGE.PERSON_ID))
			.where(SYNC_CHANGE.RUN_ID.`in`(latest.map { it.id }), SYNC_CHANGE.RESOURCE_ID.`in`(resourceIds))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME, SYNC_CHANGE.KIND)
			.limit(500)
			.fetch {
				PlannedChange(
					it.value1()!!, it.value2()!!, it.value3()!!, it.value4()?.let { f -> name(it.value6(), f, it.value5()!!) }, it.value7(), it.value8(), it.value9(),
					modes.getValue(it.value10()!!), it.value11(),
				)
			}
		val lastRun = latest.mapNotNull { it.startedAt }.maxOrNull()

		val recent = activity.page(id, null, 5)

		return ProjectDetail(
			id, project.name!!, project.slug!!, project.status!!, project.type, project.tags!!.filterNotNull(), project.termId, term, project.partnerId, partner,
			project.memberDbProjectId?.takeIf { project.memberDbLinked == true }, project.startsOn, project.endsOn, members, resources,
			agreementsSigned = members.count { it.agreementStatus != "pending" },
			preview = preview,
			planned = planned,
			lastDryRunAt = lastRun,
			plannedOutOfDate = lastRun == null || project.updatedAt!!.isAfter(lastRun),
			activity = recent.items,
			activityMore = recent.more,
			canEdit = canEdit,
			canEditTeam = canEditTeam,
		)
	}

	fun roles(): List<ProjectRoleOption> =
		dsl.selectFrom(PROJECT_ROLE).orderBy(PROJECT_ROLE.IS_LEAD.desc(), PROJECT_ROLE.NAME).fetch { ProjectRoleOption(it.id!!, it.name!!, it.isLead!!) }

	fun terms(chapterId: UUID): List<TermOption> =
		dsl.select(TERM.ID, TERM.SEASON, TERM.YEAR).from(TERM).join(CHAPTER).on(CHAPTER.INSTITUTION_ID.eq(TERM.INSTITUTION_ID))
			.where(CHAPTER.ID.eq(chapterId)).orderBy(TERM.STARTS_ON.desc())
			.fetch { TermOption(it.value1()!!, termLabel(it.value2(), it.value3()?.toInt())!!) }

	/** The chapter's resources, for attaching one to another project (PRD: resources can be shared across projects). */
	fun chapterResources(chapterId: UUID): List<ChapterResourceOption> {
		val projects = dsl.select(PROJECT_RESOURCE.RESOURCE_ID, PROJECT.NAME).from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
			.where(PROJECT.CHAPTER_ID.eq(chapterId)).fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		return dsl.selectFrom(RESOURCE).where(RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.ne("unmanaged"))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME)
			.fetch { ChapterResourceOption(it.id!!, it.tool!!, it.name!!, if (it.externalId == null) "to_create" else "exists", projects[it.id].orEmpty().sorted()) }
	}

	companion object {
		fun name(preferred: String?, first: String, last: String) = "${preferred?.takeIf { it.isNotBlank() } ?: first} $last"

		fun termLabel(season: String?, year: Int?): String? = if (season == null || year == null) null else "${season.replaceFirstChar { it.uppercase() }} $year"
	}
}
