package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The fake's writes keep [WriteAdapter]'s promises, since the sync's tests (stage 2 packages 4–9) run on it. */
class InMemoryWriteAdapterTests {
	private fun tool() = InMemoryReadAdapter(Tool.SLACK, resources = mapOf(ToolResource("C1", "umd-rise-dc") to emptyList()))

	@Test
	fun `granting and revoking are idempotent, and a grant changes the level`() {
		val slack = tool()
		repeat(2) { assertEquals(GrantResult.DONE, slack.grant("C1", AccountRef("U1"), Access.WRITE)) }
		assertEquals(listOf(ResourceMember("U1", Access.WRITE)), slack.members("C1"))
		slack.grant("C1", AccountRef("U1"), Access.ADMIN)
		assertEquals(listOf(ResourceMember("U1", Access.ADMIN)), slack.members("C1"))
		repeat(2) { assertEquals(GrantResult.DONE, slack.revoke("C1", "U1")) }
		assertTrue(slack.members("C1").isEmpty())
	}

	@Test
	fun `someone without an account ID is invited, not added`() {
		val slack = tool()
		assertEquals(GrantResult.INVITED, slack.grant("C1", AccountRef(null, email = "nia@hack4impact.org"), Access.WRITE))
		assertTrue(slack.members("C1").isEmpty())
		assertEquals(listOf("invite C1 nia@hack4impact.org"), slack.writes)
	}

	@Test
	fun `creating twice makes one resource, and archiving removes it`() {
		val slack = tool()
		val id = slack.create("umd-new")
		assertEquals(id, slack.create("umd-new"))
		assertEquals(1, slack.resources().count { it.externalId == id })
		slack.archive(id)
		assertFailsWith<NotFound> { slack.members(id) }
	}

	@Test
	fun `a write failure stops writes but not reads, and an unknown resource is NotFound`() {
		val slack = tool().apply { failWrites = Unavailable(Tool.SLACK, "down") }
		assertFailsWith<Unavailable> { slack.grant("C1", AccountRef("U1"), Access.WRITE) }
		assertEquals(1, slack.resources().size)
		slack.failWrites = null
		assertFailsWith<NotFound> { slack.grant("C9", AccountRef("U1"), Access.WRITE) }
		assertTrue(slack.writes.isEmpty())
	}
}
