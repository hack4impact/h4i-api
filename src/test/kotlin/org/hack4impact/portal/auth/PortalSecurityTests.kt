package org.hack4impact.portal.auth

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** Build plan step 5 finish line: a lead signs in and sees only their chapter. Real security filters, real Postgres. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PortalSecurityTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val viewers: Viewers,
) {
	private lateinit var umd: UUID
	private lateinit var gt: UUID

	private fun person(first: String, orgEmail: String?, status: String = "active", personal: String? = null, chapter: UUID? = null): UUID {
		val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.ORG_EMAIL, orgEmail)
			.set(PERSON.PERSONAL_EMAIL, personal).set(PERSON.STATUS, status).returningResult(PERSON.ID).fetchSingle().value1()!!
		chapter?.let { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, it).execute() }
		return id
	}

	private fun role(personId: UUID, chapterId: UUID, role: String) =
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, personId).set(CHAPTER_ROLE.CHAPTER_ID, chapterId).set(CHAPTER_ROLE.ROLE, role).execute()

	private fun signedIn(email: String, subject: String = "sub-$email") = oidcLogin().idToken {
		it.subject(subject).claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Hack4Impact Georgia Tech").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val lead = person("Lena", "lena@hack4impact.org", chapter = umd)
		role(lead, umd, "lead")
		val member = person("Ada", "ada@hack4impact.org", personal = "ada@personal.test", chapter = umd)
		person("Alan", null, status = "alumni", chapter = umd)
		person("Gus", "gus@hack4impact.org", chapter = gt)
		person("Nora", "nora@hack4impact.org").let { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		person("Rudy", "rudy@hack4impact.org", chapter = umd) // member without a role
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, member).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U1").execute()
	}

	@Test
	fun `a lead signs in and sees only their chapter`() {
		mvc.get("/api/me") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.chapters.length()") { value(1) }
			jsonPath("$.chapters[0].code") { value("umd") }
			jsonPath("$.chapters[0].role") { value("lead") }
			jsonPath("$.nationalAdmin") { value(false) }
		}
		mvc.get("/api/chapters/umd") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.stats.activeMembers") { value(3) } // Lena, Ada, Rudy
			jsonPath("$.stats.alumni") { value(1) }
			jsonPath("$.stats.waitingApplications") { value(0) }
			jsonPath("$.stats.leads") { value(1) }
			jsonPath("$.registrationLink") { value("https://join.hack4impact.org/umd") }
		}
		mvc.get("/api/chapters/gt") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }
		mvc.get("/api/chapters/gt/members") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }
	}

	@Test
	fun `the members table lists only that chapter, with roles and accounts, never personal emails`() {
		val body = mvc.get("/api/chapters/umd/members") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(4) }
			jsonPath("$[0].name") { value("Lena Test") } // leads first
			jsonPath("$[0].chapterRole") { value("lead") }
			jsonPath("$[?(@.name == 'Ada Test')].accounts[0].tool") { value("slack") }
			jsonPath("$[?(@.name == 'Ada Test')].email") { value("ada@hack4impact.org") }
		}.andReturn().response.contentAsString
		assert("personal.test" !in body) { "personal email leaked" }
		assert("Gus" !in body) { "another chapter's member leaked" }
	}

	@Test
	fun `a national admin sees every chapter`() {
		mvc.get("/api/me") { with(signedIn("nora@hack4impact.org")) }.andExpect {
			jsonPath("$.nationalAdmin") { value(true) }
			jsonPath("$.chapters[*].code") { value(org.hamcrest.Matchers.containsInAnyOrder("umd", "gt")) }
			jsonPath("$.chapters[0].role") { value("national") }
		}
		mvc.get("/api/chapters/gt/members") { with(signedIn("nora@hack4impact.org")) }.andExpect { status { isOk() } }
	}

	@Test
	fun `signed in without a role, or not in the portal at all, sees no chapter`() {
		for (email in listOf("rudy@hack4impact.org", "stranger@hack4impact.org")) {
			mvc.get("/api/me") { with(signedIn(email)) }.andExpect {
				status { isOk() }
				jsonPath("$.chapters.length()") { value(0) }
			}
			mvc.get("/api/chapters/umd") { with(signedIn(email)) }.andExpect { status { isForbidden() } }
		}
		mvc.get("/api/me") { with(signedIn("stranger@hack4impact.org")) }.andExpect { jsonPath("$.personId") { doesNotExist() } }
	}

	@Test
	fun `there is no web app here, only the API`() {
		// The frontend is a separate app (portal.frontend-url); the old app routes are gone.
		for (path in listOf("/", "/index.html", "/me", "/chapters/umd/members", "/national", "/join/umd", "/claim/t", "/apply")) {
			mvc.get(path).andExpect { status { isNotFound() } }
		}
	}

	@Test
	fun `the app gets a CSRF cookie, and sign-out needs it`() {
		val cookie = mvc.get("/api/me") { with(signedIn("lena@hack4impact.org")) }.andReturn().response.getCookie("XSRF-TOKEN")
		assert(cookie != null && !cookie.isHttpOnly) { "the app must be able to read the XSRF-TOKEN cookie" }
		mvc.post("/logout") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }
		mvc.post("/logout") { with(signedIn("lena@hack4impact.org")); with(csrf()) }.andExpect { redirectedUrl("http://localhost:8080/") } // back to the frontend
	}

	@Test
	fun `an unknown chapter is 404`() {
		mvc.get("/api/chapters/nope") { with(signedIn("nora@hack4impact.org")) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `signed-out API calls get 401, not a redirect, while health stays public`() {
		mvc.get("/api/me").andExpect { status { isUnauthorized() } }
		mvc.get("/api/chapters/umd/members").andExpect { status { isUnauthorized() } }
		mvc.get("/v3/api-docs").andExpect { status { isUnauthorized() } }
		mvc.get("/api/status").andExpect { status { isOk() } }
		mvc.get("/actuator/health").andExpect { status { isOk() } }
	}

	@Test
	fun `sign-in goes to Google asking for any Workspace account, so chapter subdomain accounts can be typed in`() {
		mvc.get("/oauth2/authorization/google").andExpect {
			status { is3xxRedirection() }
			header { string("Location", org.hamcrest.Matchers.startsWith("https://accounts.google.com/o/oauth2/v2/auth")) }
			header { string("Location", org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.containsString("hd=*"), org.hamcrest.Matchers.containsString("hd=%2A"))) }
		}
	}

	@Test
	fun `the first sign-in links the Google account, so later sign-ins match by Google ID`() {
		val user = DefaultOidcUser(emptyList(), OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60), mapOf("sub" to "g-123", "email" to "Lena@hack4impact.org")))
		viewers.linkGoogleAccount(user)
		assertEquals("g-123", dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.TOOL.eq("google")).fetchSingle().value1())
		// Even if the Google account's email later changes, the ID still finds Lena.
		val renamed = DefaultOidcUser(emptyList(), OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60), mapOf("sub" to "g-123", "email" to "lena.new@hack4impact.org")))
		assertEquals(mapOf(umd to "lead"), viewers.of(renamed).chapterRoles)
	}
}
