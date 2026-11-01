package org.hack4impact.portal.adapters

import org.hack4impact.portal.adapters.google.GooglePreflight
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.preflight.Check
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Google's preflight (3-Google): which scopes delegation allows, FAIL when under-granted and OK when all are. */
class GooglePreflightTests {
	private fun preflight(write: Boolean, allowed: Set<String>) =
		GooglePreflight(AdapterProperties(google = AdapterProperties.Google(enabled = true, adminSubject = "provisioning@hack4impact.org", write = write))).apply {
			token = { scopes -> if (allowed.containsAll(scopes)) "token" else throw IllegalStateException("unauthorized_client: Client is unauthorized to retrieve access tokens") }
		}.checks().single()

	private val read = GoogleServiceAccountToken.READ_SCOPES.toSet()
	private val all = read + GoogleServiceAccountToken.WRITE_SCOPES

	@Test
	fun `every scope allowed is OK`() = assertEquals(Check.Level.OK, preflight(write = true, all).level)

	@Test
	fun `the read scopes refused fail, the write scope refused fails only with the write switch on`() {
		val noRead = preflight(write = false, emptySet())
		assertEquals(Check.Level.FAIL, noRead.level)
		assertTrue("unauthorized_client" in noRead.detail, noRead.detail)
		assertEquals(Check.Level.WARN, preflight(write = false, read).level)
		assertEquals(Check.Level.FAIL, preflight(write = true, read).level)
	}
}
