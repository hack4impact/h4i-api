package org.hack4impact.portal.resolver

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerTests {
	private val ada = UUID(0, 1)
	private val channel = UUID(0, 2)
	private val repo = UUID(0, 3)
	private val reason = listOf(Reason.ChapterMember(UUID(0, 9)))

	private fun desire(vararg grants: Grant) = Resolution(grants.toList(), emptyList())

	@Test
	fun `missing access is added`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), emptyList(), emptyMap())
		assertEquals(1, plan.adds.size)
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `different access is changed`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.ADMIN, reason)), listOf(Membership(ada, channel, Access.WRITE)), emptyMap())
		assertEquals(listOf(AccessChange(Grant(ada, channel, Access.ADMIN, reason), Access.WRITE)), plan.changes)
		assertTrue(plan.lowerings.isEmpty()) // raising access isn't held with removals
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `access the portal granted earlier is removed, with the reasons it had`() {
		val plan = Planner.plan(desire(), listOf(Membership(ada, repo, Access.WRITE)), mapOf((ada to repo) to reason))
		assertEquals(listOf(Removal(ada, repo, Access.WRITE, reason)), plan.removals)
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `access the portal never granted is drift, never removed`() {
		val plan = Planner.plan(desire(), listOf(Membership(ada, repo, Access.WRITE)), mapOf((ada to repo) to emptyList()))
		assertEquals(listOf(Membership(ada, repo, Access.WRITE)), plan.drift)
		assertTrue(plan.isEmpty)
	}

	@Test
	fun `matching access needs nothing`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), listOf(Membership(ada, channel, Access.WRITE)), emptyMap())
		assertTrue(plan.isEmpty)
	}

	@Test
	fun `access the portal didn't give is never lowered (review H3)`() {
		// A group owner the portal wants as a member: reported, not demoted.
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), listOf(Membership(ada, channel, Access.ADMIN)), emptyMap())
		assertTrue(plan.changes.isEmpty())
		assertEquals(listOf(Membership(ada, channel, Access.ADMIN)), plan.drift)
		// Raised by hand above what the portal applied: still not lowered.
		val raised = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), listOf(Membership(ada, channel, Access.ADMIN)), mapOf((ada to channel) to reason),
			mapOf((ada to channel) to Access.WRITE))
		assertTrue(raised.changes.isEmpty())
	}

	@Test
	fun `access the portal raised can be lowered again, and counts with removals`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), listOf(Membership(ada, channel, Access.ADMIN)), mapOf((ada to channel) to reason),
			mapOf((ada to channel) to Access.ADMIN))
		assertEquals(listOf(AccessChange(Grant(ada, channel, Access.WRITE, reason), Access.ADMIN)), plan.changes)
		assertEquals(1, plan.lowerings.size)
	}
}
