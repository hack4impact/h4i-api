package org.hack4impact.portal.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DomainCheckTests {
	private fun claims(email: String?, hd: String?, verified: Any? = true) =
		mapOf("email" to email, "hd" to hd, "email_verified" to verified)

	@Test
	fun `a verified hack4impact org Workspace account may sign in`() {
		assertNull(DomainCheck.rejection(claims("Ada@Hack4Impact.org", "hack4impact.org"), "hack4impact.org"))
	}

	@Test
	fun `chapter subdomains may sign in, whichever domain Google reports as hd`() {
		assertNull(DomainCheck.rejection(claims("chase@umd.hack4impact.org", "umd.hack4impact.org"), "hack4impact.org"))
		assertNull(DomainCheck.rejection(claims("chase@umd.hack4impact.org", "hack4impact.org"), "hack4impact.org"))
	}

	@Test
	fun `look-alike domains are refused`() {
		assertEquals("not a hack4impact.org account", DomainCheck.rejection(claims("ada@evilhack4impact.org", "evilhack4impact.org"), "hack4impact.org"))
		assertEquals("not a hack4impact.org account", DomainCheck.rejection(claims("ada@hack4impact.org.evil.com", "hack4impact.org.evil.com"), "hack4impact.org"))
		assertEquals("not a hack4impact.org address", DomainCheck.rejection(claims("ada@evilhack4impact.org", "hack4impact.org"), "hack4impact.org"))
	}

	@Test
	fun `everything else is refused`() {
		val domain = "hack4impact.org"
		assertEquals("not a hack4impact.org account", DomainCheck.rejection(claims("ada@gmail.com", null), domain)) // consumer Gmail: no hd
		assertEquals("not a hack4impact.org account", DomainCheck.rejection(claims("ada@umd.edu", "umd.edu"), domain)) // another Workspace
		assertEquals("email not verified", DomainCheck.rejection(claims("ada@hack4impact.org", domain, verified = false), domain))
		assertEquals("not a hack4impact.org address", DomainCheck.rejection(claims("ada@evil.example", domain), domain)) // hd and email disagree
		assertEquals("no email in the Google account", DomainCheck.rejection(claims(null, domain), domain))
	}
}
