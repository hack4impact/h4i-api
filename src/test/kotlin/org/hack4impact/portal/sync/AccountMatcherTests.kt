package org.hack4impact.portal.sync

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountMatcherTests {
	private val ada = UUID.randomUUID()
	private val alan = UUID.randomUUID()
	private val twin1 = UUID.randomUUID()
	private val twin2 = UUID.randomUUID()
	private val matcher = AccountMatcher(
		byId = mapOf("1001" to ada),
		byLogin = mapOf("alan-gh" to setOf(alan), "shared" to setOf(twin1, twin2)),
		byEmail = mapOf("ada@hack4impact.org" to setOf(ada), "alan@personal.test" to setOf(alan), "both@personal.test" to setOf(twin1, twin2)),
	)

	@Test
	fun `the account ID on file wins, then login, then email`() {
		assertEquals(PersonMatch(ada, MatchedBy.ID), matcher.match("1001", "alan-gh", "alan@personal.test"))
		assertEquals(PersonMatch(alan, MatchedBy.LOGIN), matcher.match("2002", "Alan-GH", null))
		assertEquals(PersonMatch(alan, MatchedBy.EMAIL), matcher.match("2002", "someone", "ALAN@personal.test"))
	}

	@Test
	fun `a login that is an address is tried as an email (Google group members)`() {
		assertEquals(PersonMatch(ada, MatchedBy.EMAIL), matcher.match("g-1", "ada@hack4impact.org", null))
	}

	@Test
	fun `a login or email shared by two people matches nobody`() {
		assertNull(matcher.match("3003", "shared", null))
		assertNull(matcher.match("3003", null, "both@personal.test"))
		assertNull(matcher.match("3003", "stranger", "stranger@personal.test"))
	}
}
