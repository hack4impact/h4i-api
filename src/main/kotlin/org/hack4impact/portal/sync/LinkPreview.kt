package org.hack4impact.portal.sync

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Audience
import org.hack4impact.portal.resolver.ChapterAudience
import org.hack4impact.portal.resolver.ChapterResource
import org.hack4impact.portal.resolver.ProjectResource
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.World
import java.util.UUID

/** Who a linked resource is for: a project's team, or a chapter's active members or its leads. */
enum class LinkTarget(val key: String) {
	PROJECT_TEAM("project_team"), CHAPTER_MEMBERS("chapter_members"), CHAPTER_LEADS("chapter_leads");

	companion object {
		fun of(key: String?): LinkTarget? = entries.firstOrNull { it.key == key }
	}
}

/**
 * Who should have a resource once it's linked to a target (wiki: staged-rollout-stage-2, linking existing resources by
 * hand): the resolver's answer for today's data with the resource attached. Extracted from the full portal's
 * `AdoptionReports.desired()`, so stage 3's adoption report can call the same function.
 */
object LinkPreview {
	/**
	 * Attaches [resourceId] to [target] in [world] and resolves it. A resource already attached to that project (a
	 * template resource) keeps its audience, level and gate; one national starts gets the team, [access], and the
	 * agreement gate when its tool is sensitive. A chapter target uses the chapter audience and [access].
	 */
	fun wouldHave(world: World, resourceId: UUID, target: LinkTarget, projectId: UUID?, access: Access): Set<UUID> {
		val resource = world.resources.firstOrNull { it.id == resourceId } ?: return emptySet()
		val proposed = when (target) {
			LinkTarget.PROJECT_TEAM -> {
				val project = requireNotNull(projectId) { "a project team needs a project" }
				if (world.projectResources.any { it.projectId == project && it.resourceId == resourceId }) world
				else world.copy(projectResources = world.projectResources + ProjectResource(project, resourceId, Audience.Team, access, resource.tool in world.sensitiveTools))
			}
			LinkTarget.CHAPTER_MEMBERS, LinkTarget.CHAPTER_LEADS -> {
				val chapter = requireNotNull(resource.chapterId) { "a chapter target needs the resource's chapter" }
				val audience = if (target == LinkTarget.CHAPTER_LEADS) ChapterAudience.LEADS else ChapterAudience.MEMBERS
				world.copy(chapterResources = world.chapterResources.filterNot { it.chapterId == chapter && it.resourceId == resourceId } + ChapterResource(chapter, resourceId, audience, access))
			}
		}
		return Resolver.resolve(proposed).grants.filter { it.resourceId == resourceId }.map { it.personId }.toSet()
	}
}
