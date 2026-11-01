package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.memberimport.MemberDbFixture
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Linking a new project to a past one in the member database (decision 168), against the fake member database: its
 * projects are RISE DC Portal and Food Bank App (with engagements), Orphan Project (none) and a deleted one.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
@AutoConfigureMockMvc
class PastProjectTests(@Autowired private val mvc: MockMvc, @Autowired private val dsl: DSLContext) {
	companion object {
		const val ORPHAN = "90000000-0000-0000-0000-000000000003"
		const val ENGAGED = "90000000-0000-0000-0000-000000000001"
		const val DELETED = "90000000-0000-0000-0000-000000000004"

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val db = MemberDbFixture.writable
			registry.add("portal.member-db.url") { db.url }
			registry.add("portal.member-db.username") { db.username }
			registry.add("portal.member-db.password") { db.password }
		}
	}

	private lateinit var partner: UUID

	private val lena = oidcLogin().idToken { it.subject("sub-lena").claim("email", "lena@hack4impact.org").claim("email_verified", true).claim("hd", "hack4impact.org") }

	private fun create(name: String, past: String?) = mvc.post("/api/chapters/umd/projects") {
		with(lena); with(csrf()); contentType = MediaType.APPLICATION_JSON
		content = """{"name":"$name","partnerId":"$partner"${past?.let { ""","pastProjectId":"$it"""" } ?: ""}}"""
	}

	@BeforeEach
	fun seed() {
		MemberDbFixture.resetWritable()
		dsl.emptyPortalTables()
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Lena").set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "lena@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		partner = dsl.insertInto(PARTNER).set(PARTNER.NAME, "RISE DC").returningResult(PARTNER.ID).fetchSingle().value1()!!
	}

	@Test
	fun `past projects are the member database's with no engagement, and only one portal project links each`() {
		mvc.get("/api/chapters/umd/past-projects") { with(lena) }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].name") { value("Orphan Project") }
		}
		create("Engaged", ENGAGED).andExpect { status { isUnprocessableContent() } }
		create("Deleted", DELETED).andExpect { status { isUnprocessableContent() } }
		create("Revived", ORPHAN).andExpect {
			status { isCreated() }
			jsonPath("$.pastProjectId") { value(ORPHAN) }
		}
		assertEquals(true, dsl.select(PROJECT.MEMBER_DB_LINKED).from(PROJECT).where(PROJECT.SLUG.eq("revived")).fetchSingle().value1())
		mvc.get("/api/chapters/umd/past-projects") { with(lena) }.andExpect { jsonPath("$.length()") { value(0) } } // linked now
		create("Again", ORPHAN).andExpect { status { isUnprocessableContent() } }
	}

	@Test
	fun `the past project is changed only while the project is a draft`() {
		create("Revived", null).andExpect { status { isCreated() } }
		val edit = { past: String? -> mvc.put("/api/chapters/umd/projects/revived") {
			with(lena); with(csrf()); contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Revived","partnerId":"$partner"${past?.let { ""","pastProjectId":"$it"""" } ?: ""}}"""
		} }
		edit(ORPHAN).andExpect { jsonPath("$.pastProjectId") { value(ORPHAN) } }
		edit(ORPHAN).andExpect { status { isOk() } } // unchanged is fine
		mvc.post("/api/chapters/umd/projects/revived/status") { with(lena); with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"status":"active"}""" }
			.andExpect { status { isOk() } }
		edit(null).andExpect { status { isUnprocessableContent() } }
	}
}
