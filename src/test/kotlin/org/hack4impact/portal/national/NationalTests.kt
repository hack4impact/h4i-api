package org.hack4impact.portal.national

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/** National's admin queue: things the portal couldn't do itself, marked done or cancelled by national only. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class NationalTests(@Autowired private val mvc: MockMvc, @Autowired private val dsl: DSLContext) {
	private lateinit var task: UUID
	private lateinit var other: UUID

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun decide(id: UUID, body: String, as_: String = "nora@hack4impact.org") =
		mvc.post("/api/national/queue/$id") { with(signedIn(as_)); with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }

	private fun person(first: String, email: String): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, email)
			.returningResult(PERSON.ID).fetchSingle().value1()!!

	private fun status(id: UUID) = dsl.select(ADMIN_TASK.STATUS).from(ADMIN_TASK).where(ADMIN_TASK.ID.eq(id)).fetchSingle().value1()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lena@hack4impact.org").also {
			dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
			dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute()
		}
		person("Nora", "nora@hack4impact.org").also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		val ada = person("Ada", "ada@umd.edu")
		fun task(action: String, description: String) = dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, "google").set(ADMIN_TASK.ACTION, action)
			.set(ADMIN_TASK.PERSON_ID, ada).set(ADMIN_TASK.DESCRIPTION, description).returningResult(ADMIN_TASK.ID).fetchSingle().value1()!!
		task = task("create_account", "Create a Google account for Ada Test at umd.hack4impact.org")
		other = task("check_account", "GitHub account ada-gh is linked to two people: check which is right")
	}

	@Test
	fun `national sees the queue and marks a task done or cancels it, once`() {
		mvc.get("/api/national/queue") { with(signedIn("nora@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(2) }
		}
		decide(task, """{"action":"done"}""").andExpect { status { isOk() }; jsonPath("$[?(@.status == 'done')].doneBy") { value("Nora Test") } }
		assertEquals("done", status(task))
		decide(task, """{"action":"cancel"}""").andExpect { status { isConflict() } } // already done
		decide(other, """{"action":"cancel","reason":"same person, two accounts"}""").andExpect { status { isOk() } }
		assertEquals("cancelled", status(other))
		decide(other, """{"action":"maybe"}""").andExpect { status { isBadRequest() } }
		assertEquals(2, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.`in`("queue.done", "queue.cancel")))
	}

	@Test
	fun `only national admins reach the queue`() {
		mvc.get("/api/national/queue") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }
		decide(task, """{"action":"done"}""", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		assertEquals("open", status(task))
	}
}
