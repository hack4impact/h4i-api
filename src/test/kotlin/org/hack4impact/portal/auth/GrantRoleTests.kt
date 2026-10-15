package org.hack4impact.portal.auth

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class GrantRoleTests(@Autowired private val grants: GrantRole, @Autowired private val dsl: DSLContext) {

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").execute()
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Lena").set(PERSON.LAST_NAME, "Lead").set(PERSON.ORG_EMAIL, "lena@hack4impact.org").set(PERSON.STATUS, "alumni").execute()
	}

	private fun activeRoles() = dsl.select(CHAPTER_ROLE.ROLE).from(CHAPTER_ROLE).where(CHAPTER_ROLE.ENDS_AT.isNull).fetch(CHAPTER_ROLE.ROLE)

	@Test
	fun `grants a role to an existing person, makes them a member, and audits it`() {
		assertEquals("lena@hack4impact.org is lead of umd", grants.grant(GrantProperties(email = "Lena@hack4impact.org", role = "lead", chapter = "UMD")))
		assertEquals(listOf("lead"), activeRoles())
		assertEquals(1, dsl.fetchCount(CHAPTER_MEMBERSHIP))
		assertEquals(1, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq("chapter_role.grant")))
	}

	@Test
	fun `a new role replaces the old one in that chapter, and granting twice changes nothing`() {
		grants.grant(GrantProperties(email = "lena@hack4impact.org", role = "viewer", chapter = "umd"))
		grants.grant(GrantProperties(email = "lena@hack4impact.org", role = "co_lead", chapter = "umd"))
		grants.grant(GrantProperties(email = "lena@hack4impact.org", role = "co_lead", chapter = "umd"))
		assertEquals(listOf("co_lead"), activeRoles())
	}

	@Test
	fun `creates the person when names are given, and makes national admins`() {
		assertContains(grants.grant(GrantProperties(email = "nora@hack4impact.org", role = "national_admin", firstName = "Nora", lastName = "National")), "new person created")
		assertEquals(1, dsl.fetchCount(NATIONAL_ADMIN))
		assertEquals("active", dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ORG_EMAIL.eq("nora@hack4impact.org")).fetchSingle().value1())
	}

	@Test
	fun `explains what's missing`() {
		assertContains(assertFailsWith<IllegalArgumentException> { grants.grant(GrantProperties(email = "new@hack4impact.org", role = "lead", chapter = "umd")) }.message!!, "first-name")
		assertContains(assertFailsWith<IllegalArgumentException> { grants.grant(GrantProperties(email = "lena@hack4impact.org", role = "owner", chapter = "umd")) }.message!!, "must be lead")
		assertContains(assertFailsWith<IllegalStateException> { grants.grant(GrantProperties(email = "lena@hack4impact.org", role = "lead", chapter = "nope")) }.message!!, "No chapter")
	}
}
