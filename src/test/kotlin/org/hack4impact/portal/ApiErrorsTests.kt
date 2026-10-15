package org.hack4impact.portal

import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Second review L1: only the portal's own checks become 409 or 400; the same exceptions from elsewhere are a 500. */
class ApiErrorsTests {
	private val errors = ApiErrors()

	private fun <T : RuntimeException> fromLibrary(e: T): T = e.apply { stackTrace = arrayOf(StackTraceElement("tools.jackson.databind.Something", "read", "Something.java", 1)) }

	@Test
	fun `a failed check in portal code is a conflict, with its message`() {
		val e = runCatching { check(false) { "Already approved" } }.exceptionOrNull() as IllegalStateException
		val problem = errors.conflict(e)
		assertEquals(409, problem.status)
		assertEquals("Already approved", problem.detail)
	}

	@Test
	fun `a failed require in portal code is a bad request`() {
		val e = runCatching { require(false) { "No such tool" } }.exceptionOrNull() as IllegalArgumentException
		assertEquals(400, errors.badRequest(e).status)
	}

	@Test
	fun `the same exceptions from a library are a 500 that doesn't repeat their message`() {
		val conflict = errors.conflict(fromLibrary(IllegalStateException("internal detail")))
		assertEquals(500, conflict.status)
		assertEquals(500, errors.badRequest(fromLibrary(IllegalArgumentException("internal detail"))).status)
		assertEquals(false, conflict.detail!!.contains("internal detail"))
		assertEquals(500, errors.conflict(IllegalStateException("no trace").apply { stackTrace = emptyArray() }).status)
	}

	@Test
	fun `a unique-data race is a conflict asking to reload`() {
		assertEquals(409, errors.duplicate(DataIntegrityViolationException("duplicate key")).status)
	}
}
