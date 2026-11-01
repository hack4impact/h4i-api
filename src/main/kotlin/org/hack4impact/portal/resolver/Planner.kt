package org.hack4impact.portal.resolver

import java.util.UUID

/** One person's actual access to one resource, as read from a tool by an adapter. */
data class Membership(val personId: UUID, val resourceId: UUID, val access: Access)

data class AccessChange(val grant: Grant, val from: Access)

/** Access the portal granted earlier and no longer wants, with the reasons it was granted for. */
data class Removal(val personId: UUID, val resourceId: UUID, val access: Access, val previousReasons: List<Reason>)

/**
 * What to change in the tools so they match the resolver. [drift] is access the portal never granted
 * (made by hand in a tool), including a level above what it gave (a group owner the portal wants as a member);
 * the plan reports it but never removes or lowers it. Adoption (step 7) decides about it.
 */
data class Plan(
	val adds: List<Grant>,
	val changes: List<AccessChange>,
	val removals: List<Removal>,
	val drift: List<Membership>,
) {
	val isEmpty get() = adds.isEmpty() && changes.isEmpty() && removals.isEmpty()

	/** Changes that lower someone's access: they count, and are held, with removals (code review H3). */
	val lowerings get() = changes.filter { it.grant.access < it.from }
}

object Planner {

	/**
	 * Diffs the desired grants against what the tools actually have. [previous] holds, per (person, resource),
	 * the reasons the portal last granted it for (the sync records). Only pairs with previous reasons can be
	 * removed, so the portal never takes away access it didn't give. [applied] is the level the portal last applied
	 * per pair: access is only lowered from a level the portal gave (code review H3).
	 */
	fun plan(
		desired: Resolution, actual: Collection<Membership>, previous: Map<Pair<UUID, UUID>, List<Reason>>, applied: Map<Pair<UUID, UUID>, Access> = emptyMap(),
	): Plan {
		val have = actual.associateBy { it.personId to it.resourceId }
		val want = desired.grants.associateBy { it.personId to it.resourceId }
		val adds = desired.grants.filter { (it.personId to it.resourceId) !in have }
		val changes = mutableListOf<AccessChange>()
		val above = mutableListOf<Membership>()
		for (grant in desired.grants) {
			val current = have[grant.personId to grant.resourceId] ?: continue
			when {
				current.access == grant.access -> {}
				grant.access > current.access -> changes += AccessChange(grant, current.access)
				applied[grant.personId to grant.resourceId]?.let { it >= current.access } == true -> changes += AccessChange(grant, current.access)
				else -> above += current // someone raised it in the tool (or had it before the portal): not the portal's to lower
			}
		}
		val (removable, drift) = have.filterKeys { it !in want }.values.partition { !previous[it.personId to it.resourceId].isNullOrEmpty() }
		val order = compareBy<Membership>({ it.personId }, { it.resourceId })
		return Plan(
			adds = adds,
			changes = changes,
			removals = removable.sortedWith(order).map { Removal(it.personId, it.resourceId, it.access, previous.getValue(it.personId to it.resourceId)) },
			drift = (drift + above).sortedWith(order),
		)
	}
}
