package org.hack4impact.portal.resolver

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Readable scenarios, one per behavior. The invariants over random worlds are in [ResolverProperties]. */
class ResolverTests {

	private var next = 0L
	private fun id() = UUID(0, ++next)

	private val umd = id()
	private val upenn = id()
	private val dev = ProjectRole(id(), isLead = false)
	private val techLead = ProjectRole(id(), isLead = true)

	private fun person(status: PersonStatus = PersonStatus.ACTIVE, vararg chapters: UUID = arrayOf(umd), roles: Set<ChapterRoleAssignment> = emptySet()) =
		Person(id(), status, chapters.toSet(), roles)

	private fun resource(tool: Tool = Tool.SLACK, chapter: UUID? = umd, tags: Set<String> = emptySet(), archived: Boolean = false) =
		Resource(id(), tool, chapter, tags, archived)

	private fun project(chapter: UUID = umd, status: ProjectStatus = ProjectStatus.ACTIVE, tags: Set<String> = emptySet()) =
		Project(id(), chapter, status, tags)

	private fun world(
		people: List<Person>,
		projects: List<Project> = emptyList(),
		members: List<ProjectMember> = emptyList(),
		resources: List<Resource> = emptyList(),
		projectResources: List<ProjectResource> = emptyList(),
		chapterResources: List<ChapterResource> = emptyList(),
		rules: List<Rule> = emptyList(),
	) = World(people, projects, listOf(dev, techLead), members, resources, projectResources, chapterResources, rules, sensitiveTools = setOf(Tool.GITHUB, Tool.VAULTWARDEN))

	private fun Resolution.grant(person: Person, resource: Resource) = grants.singleOrNull { it.personId == person.id && it.resourceId == resource.id }
	private fun Resolution.withheld(person: Person, resource: Resource) = withheld.singleOrNull { it.personId == person.id && it.resourceId == resource.id }?.why

	// Chapter resources --------------------------------------------------------------------------

	@Test
	fun `active chapter members get the chapter's member resources`() {
		val ada = person()
		val general = resource()
		val r = Resolver.resolve(world(listOf(ada), resources = listOf(general), chapterResources = listOf(ChapterResource(umd, general.id, ChapterAudience.MEMBERS, Access.WRITE))))
		assertEquals(Grant(ada.id, general.id, Access.WRITE, listOf(Reason.ChapterMember(umd))), r.grant(ada, general))
	}

	@Test
	fun `alumni lose chapter resources`() {
		val alan = person(PersonStatus.ALUMNI)
		val general = resource()
		val r = Resolver.resolve(world(listOf(alan), resources = listOf(general), chapterResources = listOf(ChapterResource(umd, general.id, ChapterAudience.MEMBERS, Access.WRITE))))
		assertTrue(r.grants.isEmpty())
	}

	@Test
	fun `only leads and co-leads get the chapter's lead resources`() {
		val lead = person(roles = setOf(ChapterRoleAssignment(umd, ChapterRole.CO_LEAD)))
		val viewer = person(roles = setOf(ChapterRoleAssignment(umd, ChapterRole.VIEWER)))
		val otherChapterLead = person(chapters = arrayOf(umd, upenn), roles = setOf(ChapterRoleAssignment(upenn, ChapterRole.LEAD)))
		val leads = resource()
		val r = Resolver.resolve(
			world(listOf(lead, viewer, otherChapterLead), resources = listOf(leads), chapterResources = listOf(ChapterResource(umd, leads.id, ChapterAudience.LEADS, Access.ADMIN))),
		)
		assertEquals(listOf(Reason.ChapterLead(umd)), r.grant(lead, leads)?.reasons)
		assertEquals(null, r.grant(viewer, leads))
		assertEquals(null, r.grant(otherChapterLead, leads))
	}

	// Project resources --------------------------------------------------------------------------

	@Test
	fun `project audiences pick the whole team, one role, or the leads`() {
		val rise = project()
		val ada = person()
		val dana = person()
		val team = resource()
		val eng = resource()
		val admin = resource(Tool.GITHUB)
		val w = world(
			listOf(ada, dana), listOf(rise),
			members = listOf(ProjectMember(rise.id, ada.id, dev.id, true), ProjectMember(rise.id, dana.id, techLead.id, true)),
			resources = listOf(team, eng, admin),
			projectResources = listOf(
				ProjectResource(rise.id, team.id, Audience.Team, Access.WRITE),
				ProjectResource(rise.id, eng.id, Audience.Role(dev.id), Access.WRITE),
				ProjectResource(rise.id, admin.id, Audience.Leads, Access.ADMIN),
			),
		)
		val r = Resolver.resolve(w)
		assertEquals(setOf(team.id, eng.id), r.grants.filter { it.personId == ada.id }.map { it.resourceId }.toSet())
		assertEquals(setOf(team.id, admin.id), r.grants.filter { it.personId == dana.id }.map { it.resourceId }.toSet())
	}

	@Test
	fun `members without a role are only in the whole-team audience`() {
		val rise = project()
		val ada = person()
		val team = resource()
		val leads = resource()
		val r = Resolver.resolve(
			world(
				listOf(ada), listOf(rise), listOf(ProjectMember(rise.id, ada.id, null, true)), listOf(team, leads),
				listOf(ProjectResource(rise.id, team.id, Audience.Team, Access.WRITE), ProjectResource(rise.id, leads.id, Audience.Leads, Access.WRITE)),
			),
		)
		assertEquals(listOf(team.id), r.grants.map { it.resourceId })
	}

	@Test
	fun `draft and closed projects grant nothing, paused ones keep access`() {
		val ada = person()
		val projects = ProjectStatus.entries.map { project(status = it) }
		val channels = projects.map { resource() }
		val r = Resolver.resolve(
			world(
				listOf(ada), projects,
				members = projects.map { ProjectMember(it.id, ada.id, dev.id, true) },
				resources = channels,
				projectResources = projects.zip(channels) { p, c -> ProjectResource(p.id, c.id, Audience.Team, Access.WRITE) },
			),
		)
		val granted = r.grants.map { g -> projects[channels.indexOfFirst { it.id == g.resourceId }].status }.toSet()
		assertEquals(setOf(ProjectStatus.ACTIVE, ProjectStatus.PAUSED), granted)
	}

	@Test
	fun `gated resources wait for the project agreement`() {
		val rise = project()
		val ada = person()
		val signer = person()
		val repo = resource(Tool.GITHUB)
		val r = Resolver.resolve(
			world(
				listOf(ada, signer), listOf(rise),
				listOf(ProjectMember(rise.id, ada.id, dev.id, agreementSigned = false), ProjectMember(rise.id, signer.id, dev.id, agreementSigned = true)),
				listOf(repo), listOf(ProjectResource(rise.id, repo.id, Audience.Team, Access.WRITE, requiresAgreement = true)),
			),
		)
		assertEquals(WithheldReason.AwaitingAgreement(rise.id), r.withheld(ada, repo))
		assertEquals(Access.WRITE, r.grant(signer, repo)?.access)
	}

	@Test
	fun `a gated resource stays gated when a chapter resource or rule grants it`() {
		val rise = project()
		val ada = person()
		val repo = resource(Tool.GITHUB)
		val rule = Rule(id(), umd, Who(everyone = true), ProjectScope.None, ResourceScope.Ids(setOf(repo.id)), Access.WRITE)
		val r = Resolver.resolve(
			world(
				listOf(ada), listOf(rise), emptyList(), listOf(repo),
				listOf(ProjectResource(rise.id, repo.id, Audience.Team, Access.WRITE, requiresAgreement = true)),
				listOf(ChapterResource(umd, repo.id, ChapterAudience.MEMBERS, Access.WRITE)),
				listOf(rule),
			),
		)
		assertEquals(null, r.grant(ada, repo))
		assertEquals(WithheldReason.AwaitingAgreement(rise.id), r.withheld(ada, repo))
	}

	// Alumni and other statuses ------------------------------------------------------------------

	@Test
	fun `alumni on a live project get its repo, alumni elsewhere don't`() {
		val rise = project()
		val mentor = person(PersonStatus.ALUMNI)
		val repo = resource(Tool.GITHUB)
		val vault = resource(Tool.VAULTWARDEN)
		val everyRepo = Rule(id(), umd, Who(statuses = setOf(PersonStatus.ALUMNI), everyone = true), ProjectScope.None, ResourceScope.Ids(setOf(repo.id, vault.id)), Access.ADMIN)
		val r = Resolver.resolve(
			world(
				listOf(mentor), listOf(rise), listOf(ProjectMember(rise.id, mentor.id, dev.id, true)), listOf(repo, vault),
				listOf(ProjectResource(rise.id, repo.id, Audience.Team, Access.WRITE)), rules = listOf(everyRepo),
			),
		)
		// The rule can't raise an alumnus's repo access, and can't give them a vault outside the project.
		assertEquals(Grant(mentor.id, repo.id, Access.WRITE, listOf(Reason.ProjectMember(rise.id))), r.grant(mentor, repo))
		assertEquals(WithheldReason.SensitiveNeedsProject, r.withheld(mentor, vault))
	}

	@Test
	fun `applicants and removed people get no project access`() {
		val rise = project()
		val people = listOf(PersonStatus.APPLICANT, PersonStatus.REMOVED).map { person(it) }
		val channel = resource()
		val r = Resolver.resolve(
			world(people, listOf(rise), people.map { ProjectMember(rise.id, it.id, dev.id, true) }, listOf(channel), listOf(ProjectResource(rise.id, channel.id, Audience.Team, Access.WRITE))),
		)
		assertTrue(r.grants.isEmpty() && r.withheld.isEmpty())
	}

	@Test
	fun `people pending removal keep everything until national confirms`() {
		val rise = project()
		val flagged = person(PersonStatus.REMOVAL_REQUESTED)
		val general = resource()
		val repo = resource(Tool.GITHUB)
		val vault = resource(Tool.VAULTWARDEN)
		val rule = Rule(id(), umd, Who(everyone = true), ProjectScope.None, ResourceScope.Ids(setOf(vault.id)), Access.READ)
		val r = Resolver.resolve(
			world(
				listOf(flagged), listOf(rise), listOf(ProjectMember(rise.id, flagged.id, dev.id, true)), listOf(general, repo, vault),
				listOf(ProjectResource(rise.id, repo.id, Audience.Team, Access.WRITE)),
				listOf(ChapterResource(umd, general.id, ChapterAudience.MEMBERS, Access.WRITE)),
				listOf(rule),
			),
		)
		assertEquals(setOf(general.id, repo.id, vault.id), r.grants.map { it.resourceId }.toSet())
	}

	@Test
	fun `a national default keeps alumni in #alumni but never reaches removed people`() {
		val alan = person(PersonStatus.ALUMNI)
		val gone = person(PersonStatus.REMOVED)
		val alumniChannel = resource(chapter = null)
		val default = Rule(id(), null, Who(statuses = PersonStatus.entries.toSet(), everyone = true), ProjectScope.None, ResourceScope.Ids(setOf(alumniChannel.id)), Access.WRITE)
		val r = Resolver.resolve(world(listOf(alan, gone), resources = listOf(alumniChannel), rules = listOf(default)))
		assertEquals(listOf(Reason.NationalDefault(default.id)), r.grant(alan, alumniChannel)?.reasons)
		assertEquals(null, r.grant(gone, alumniChannel))
	}

	// Rules --------------------------------------------------------------------------------------

	@Test
	fun `rules match by title, chapter role, project role or name`() {
		val rise = project(tags = setOf("lts"))
		val director = person(roles = setOf(ChapterRoleAssignment(umd, ChapterRole.LEAD, "Director of product")))
		val lead = person(roles = setOf(ChapterRoleAssignment(umd, ChapterRole.LEAD)))
		val techLeadPerson = person()
		val named = person()
		val nobody = person()
		val channel = resource(tags = setOf("main"))
		val rules = listOf(
			Who(titles = setOf("Director of product")),
			Who(chapterRoles = setOf(ChapterRole.LEAD)),
			Who(projectRoles = setOf(techLead.id)),
			Who(people = setOf(named.id)),
		).map { Rule(id(), umd, it, ProjectScope.Tags(setOf("lts")), ResourceScope.Tags(setOf("main")), Access.WRITE) }
		val r = Resolver.resolve(
			world(
				listOf(director, lead, techLeadPerson, named, nobody), listOf(rise),
				listOf(ProjectMember(rise.id, techLeadPerson.id, techLead.id, true)), listOf(channel),
				listOf(ProjectResource(rise.id, channel.id, Audience.Leads, Access.READ)), rules = rules,
			),
		)
		assertEquals(setOf(director.id, lead.id, techLeadPerson.id, named.id), r.grants.map { it.personId }.toSet())
		// Two sources merge into one grant: the strongest access wins and both reasons are listed.
		assertEquals(Access.WRITE, r.grant(techLeadPerson, channel)?.access)
		assertEquals(listOf(Reason.ProjectMember(rise.id), Reason.FromRule(rules[2].id)), r.grant(techLeadPerson, channel)?.reasons)
	}

	@Test
	fun `rule project scopes pick all, listed or tagged live projects`() {
		val tagged = project(tags = setOf("lts"))
		val listed = project()
		val closed = project(status = ProjectStatus.CLOSED, tags = setOf("lts"))
		val ada = person()
		val channels = listOf(tagged, listed, closed).map { resource(tags = setOf("main")) }
		val base = world(
			listOf(ada), listOf(tagged, listed, closed), resources = channels,
			projectResources = listOf(tagged, listed, closed).zip(channels) { p, c -> ProjectResource(p.id, c.id, Audience.Leads, Access.READ) },
		)
		fun granted(scope: ProjectScope) = Resolver.resolve(
			base.copy(rules = listOf(Rule(id(), umd, Who(everyone = true), scope, ResourceScope.Tags(setOf("main")), Access.WRITE))),
		).grants.map { it.resourceId }.toSet()
		assertEquals(setOf(channels[0].id, channels[1].id), granted(ProjectScope.All))
		assertEquals(setOf(channels[1].id), granted(ProjectScope.Ids(setOf(listed.id))))
		assertEquals(setOf(channels[0].id), granted(ProjectScope.Tags(setOf("lts"))))
	}

	@Test
	fun `chapter rules stay in their chapter, disabled rules and tool filters apply`() {
		val ada = person(chapters = arrayOf(umd))
		val penn = person(chapters = arrayOf(upenn))
		val umdSlack = resource(Tool.SLACK, umd, setOf("main"))
		val umdNotion = resource(Tool.NOTION, umd, setOf("main"))
		val pennSlack = resource(Tool.SLACK, upenn, setOf("main"))
		val rule = Rule(id(), umd, Who(everyone = true), ProjectScope.None, ResourceScope.Tags(setOf("main")), Access.WRITE, tools = setOf(Tool.SLACK))
		val off = Rule(id(), umd, Who(everyone = true), ProjectScope.None, ResourceScope.Tags(setOf("main")), Access.ADMIN, enabled = false)
		val r = Resolver.resolve(world(listOf(ada, penn), resources = listOf(umdSlack, umdNotion, pennSlack), rules = listOf(rule, off)))
		assertEquals(listOf(Grant(ada.id, umdSlack.id, Access.WRITE, listOf(Reason.FromRule(rule.id)))), r.grants)
	}

	// Guards ------------------------------------------------------------------------------------

	@Test
	fun `another chapter's resources are withheld even when a national default points at them`() {
		val ada = person(chapters = arrayOf(umd))
		val pennChannel = resource(chapter = upenn)
		val default = Rule(id(), null, Who(everyone = true), ProjectScope.None, ResourceScope.Ids(setOf(pennChannel.id)), Access.WRITE)
		val r = Resolver.resolve(world(listOf(ada), resources = listOf(pennChannel), rules = listOf(default)))
		assertEquals(WithheldReason.OtherChapter(upenn), r.withheld(ada, pennChannel))
	}

	@Test
	fun `archived resources go to nobody, unknown references are ignored`() {
		val ada = person()
		val old = resource(archived = true)
		val rise = project()
		val r = Resolver.resolve(
			world(
				listOf(ada), listOf(rise), listOf(ProjectMember(rise.id, ada.id, dev.id, true), ProjectMember(rise.id, id(), dev.id, true)),
				listOf(old),
				listOf(ProjectResource(rise.id, old.id, Audience.Team, Access.WRITE), ProjectResource(rise.id, id(), Audience.Team, Access.WRITE)),
				listOf(ChapterResource(umd, id(), ChapterAudience.MEMBERS, Access.WRITE)),
			),
		)
		assertTrue(r.grants.isEmpty() && r.withheld.isEmpty())
	}
}
