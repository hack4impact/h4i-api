package org.hack4impact.portal.registration

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import jakarta.servlet.http.Cookie
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.ROSTER_INVITE
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same thing asked for twice at the same moment (a double click, two leads on one page, a link opened in two
 * tabs): it happens once, the other request is told so, and nothing is made twice. Each case runs [ROUNDS] times,
 * since two requests only sometimes overlap where it matters.
 */
@Import(TestcontainersConfiguration::class, RegistrationFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentRequestTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val mail: CapturedMail,
	@Autowired private val limiter: RateLimiter,
) {
	companion object {
		private const val ROUNDS = 8
		private const val LENA = "lena@hack4impact.org"
		private const val COLE = "cole@hack4impact.org"

		@JvmStatic
		@DynamicPropertySource
		fun settings(registry: DynamicPropertyRegistry) {
			registry.add("portal.github-oauth.client-id") { "client" }
			registry.add("portal.github-oauth.client-secret") { "secret" }
			registry.add("portal.github-oauth.web-url") { RegistrationFakes.server.baseUrl() }
			registry.add("portal.github-oauth.api-url") { RegistrationFakes.server.baseUrl() }
			registry.add("portal.sync.on-request") { "false" } // no background syncs to overlap the next test's clean-up
		}
	}

	private val server = RegistrationFakes.server
	private lateinit var umd: UUID

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun send(path: String, body: String = "{}", cookie: Cookie? = null, as_: String? = null, method: String = "POST") = run {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body
			cookie?.let { cookie(it) }
			as_?.let { with(signedIn(it)) }
		}
		(if (method == "PUT") mvc.put(path) { setup() } else mvc.post(path) { setup() }).andReturn().response
	}

	/** Runs the calls at the same moment and returns their statuses, lowest first. */
	private fun race(vararg calls: () -> Int): List<Int> {
		val start = CyclicBarrier(calls.size)
		val pool = Executors.newFixedThreadPool(calls.size)
		try {
			return calls.map { call -> pool.submit<Int> { start.await(10, TimeUnit.SECONDS); call() } }.map { it.get(60, TimeUnit.SECONDS) }.sorted()
		} finally {
			pool.shutdownNow()
		}
	}

	private fun person(first: String, role: String? = null, school: String? = null, imported: Boolean = false): UUID {
		val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.SCHOOL_EMAIL, school)
			.set(PERSON.ORG_EMAIL, if (imported) null else "${first.lowercase()}@hack4impact.org").set(PERSON.SOURCE_ID, if (imported) UUID.randomUUID() else null)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, it).execute() }
		return id
	}

	/** GitHub sign-in through the fake, then the form filled in: everything but sending it. */
	private fun filledIn(cookie: Cookie, first: String, n: Int): Cookie {
		// The Google account this applicant will get: its own ID, as Google's are.
		server.stubFor(post(urlEqualTo("/admin/directory/v1/users")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"g-$first"}""")))
		server.stubFor(post(urlEqualTo("/login/oauth/access_token")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"access_token":"gho_$n"}""")))
		server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":${9000 + n},"login":"gh-$n"}""")))
		val redirect = mvc.get("/api/apply/github/start") { cookie(cookie) }.andReturn().response.redirectedUrl!!
		mvc.get("/api/apply/github/callback?code=abc&state=${Regex("state=([^&]+)").find(redirect)!!.groupValues[1]}") { cookie(cookie) }
		val saved = send("/api/apply/me", """{"details":{"major":"CS","degreeType":"Bachelor's","graduationSeason":"spring","firstName":"$first","lastName":"Racer","graduationYear":2028},"answers":{}}""", cookie, method = "PUT")
		assertEquals("[]", HttpJson.MAPPER.readTree(saved.contentAsString).path("missing").toString(), "the form should be complete")
		return cookie
	}

	/** A new applicant with a verified school address and a complete form. */
	private fun applicant(first: String, n: Int): Cookie {
		val email = "${first.lowercase()}$n@umd.edu"
		assertEquals(202, send("/api/apply/chapters/umd/code", """{"email":"$email"}""").status)
		val code = Regex("\\d{6}").find(mail.last(email).subject)!!.value
		return filledIn(send("/api/apply/chapters/umd/verify", """{"email":"$email","code":"$code"}""").getCookie("h4i_applicant")!!, first, n)
	}

	private fun people(first: String) = dsl.fetchCount(PERSON, PERSON.FIRST_NAME.eq(first))
	private fun registration(first: String) = dsl.selectFrom(REGISTRATION).where(REGISTRATION.FIRST_NAME.eq(first)).fetch()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		mail.sent.clear()
		mail.failing = false
		limiter.reset()
		server.resetAll()
		server.stubFor(get(urlPathMatching("/admin/directory/v1/users/.*")).willReturn(aResponse().withStatus(404).withBody("{}")))
		server.stubFor(post(urlEqualTo("/admin/directory/v1/users")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"g-new"}""")))
		val school = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "University of Maryland").set(INSTITUTION.EMAIL_DOMAINS, arrayOf("umd.edu"))
			.returningResult(INSTITUTION.ID).fetchSingle().value1()!!
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").set(CHAPTER.INSTITUTION_ID, school)
			.set(CHAPTER.GOOGLE_ORG_UNIT, "/UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lead")
		person("Cole", "co_lead")
		dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, person("Nora")).execute()
	}

	@Test
	fun `two leads approving one application make one member`() {
		repeat(ROUNDS) { n ->
			val first = "Approve$n"
			send("/api/apply/me/submit", cookie = applicant(first, n))
			val id = registration(first).single().id
			val statuses = race(
				{ send("/api/chapters/umd/registrations/$id/approve", as_ = LENA).status },
				{ send("/api/chapters/umd/registrations/$id/approve", as_ = COLE).status },
			)
			assertEquals(listOf(200, 409), statuses, "round $n")
			assertEquals(1, people(first), "round $n: one person")
			assertEquals("approved", registration(first).single().status)
		}
		assertEquals(ROUNDS, server.allServeEvents.count { it.request.method.name == "POST" && it.request.url == "/admin/directory/v1/users" }, "one Google account each")
	}

	@Test
	fun `an approval and a decline at once leave one decision`() {
		repeat(ROUNDS) { n ->
			val first = "Decide$n"
			send("/api/apply/me/submit", cookie = applicant(first, n))
			val id = registration(first).single().id
			val statuses = race(
				{ send("/api/chapters/umd/registrations/$id/approve", as_ = LENA).status },
				{ send("/api/chapters/umd/registrations/$id/decline", """{"reason":"Not this term"}""", as_ = COLE).status },
			)
			assertEquals(1, statuses.count { it in 200..299 }, "round $n: exactly one decision goes through, got $statuses")
			assertEquals(409, statuses.last(), "round $n: the other is told it was already decided")
			val status = registration(first).single().status
			// Whichever won, the person and the registration agree.
			assertEquals(if (status == "approved") 1 else 0, people(first), "round $n: the application is $status")
			assertTrue(status in setOf("approved", "declined"), "round $n: the application is $status")
		}
	}

	@Test
	fun `an application sent twice at once is sent once`() {
		repeat(ROUNDS) { n ->
			val first = "Submit$n"
			val cookie = applicant(first, n)
			val statuses = race({ send("/api/apply/me/submit", cookie = cookie).status }, { send("/api/apply/me/submit", cookie = cookie).status })
			assertTrue(statuses.all { it == 200 || it == 409 } && 200 in statuses, "round $n: got $statuses")
			assertEquals("submitted", registration(first).single().status)
		}
	}

	@Test
	fun `a roster invitee sending twice at once becomes one member`() {
		repeat(ROUNDS) { n ->
			val first = "Roster$n"
			val email = "roster$n@umd.edu"
			assertEquals(200, send("/api/chapters/umd/roster", """{"csv":"first_name,last_name,school_email\n$first,Racer,$email"}""", as_ = LENA).status)
			val token = Regex("invite=([A-Za-z0-9_-]+)").find(mail.last(email).text)!!.groupValues[1]
			val cookie = filledIn(send("/api/apply/roster", """{"token":"$token"}""").getCookie("h4i_applicant")!!, first, n)
			val statuses = race({ send("/api/apply/me/submit", cookie = cookie).status }, { send("/api/apply/me/submit", cookie = cookie).status })
			assertTrue(statuses.none { it >= 500 } && statuses.any { it == 200 }, "round $n: got $statuses")
			assertEquals(1, people(first), "round $n: one person")
			assertEquals("approved", registration(first).single().status, "round $n")
		}
	}

	@Test
	fun `a claim link opened twice at once works once`() {
		repeat(ROUNDS) { n ->
			val email = "claim$n@umd.edu"
			val person = person("Claim$n", school = email, imported = true)
			assertEquals(200, send("/api/national/claims/people/$person", as_ = "nora@hack4impact.org").status)
			val token = Regex("/claim/([A-Za-z0-9_-]+)").find(mail.last(email).text)!!.groupValues[1]
			val statuses = race({ send("/api/apply/claim", """{"token":"$token"}""").status }, { send("/api/apply/claim", """{"token":"$token"}""").status })
			assertEquals(listOf(200, 410), statuses, "round $n")
			assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.PERSON_ID.eq(person)), "round $n: one claim started")
		}
	}

	@Test
	fun `a code used twice at once starts one application`() {
		repeat(ROUNDS) { n ->
			val email = "code$n@umd.edu"
			send("/api/apply/chapters/umd/code", """{"email":"$email"}""")
			val code = Regex("\\d{6}").find(mail.last(email).subject)!!.value
			val verify = { send("/api/apply/chapters/umd/verify", """{"email":"$email","code":"$code"}""").status }
			assertEquals(listOf(200, 400), race(verify, verify), "round $n")
			assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.SCHOOL_EMAIL.eq(email)), "round $n: one application")
		}
	}

	@Test
	fun `one roster uploaded twice at once invites each person once`() {
		repeat(ROUNDS) { n ->
			val email = "twice$n@umd.edu"
			val upload = { send("/api/chapters/umd/roster", """{"csv":"first_name,last_name,school_email\nTwice$n,Racer,$email"}""", as_ = LENA).status }
			val statuses = race(upload, upload)
			assertTrue(statuses.none { it >= 500 }, "round $n: got $statuses")
			assertEquals(1, dsl.fetchCount(ROSTER_INVITE, ROSTER_INVITE.SCHOOL_EMAIL.eq(email)), "round $n: one invite")
		}
	}

	@Test
	fun `a project made twice at once is made once, and someone added twice joins once`() {
		val developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		repeat(ROUNDS) { n ->
			val create = { leader: String -> send("/api/chapters/umd/projects", """{"name":"Race $n","tags":[]}""", as_ = leader).status }
			assertEquals(listOf(201, 409), race({ create(LENA) }, { create(COLE) }), "round $n: creating")
			val project = dsl.select(PROJECT.ID).from(PROJECT).where(PROJECT.SLUG.eq("race-$n")).fetchSingle().value1()!!
			val member = person("Member$n")
			val add = { leader: String -> send("/api/chapters/umd/projects/race-$n/members", """{"personId":"$member","roleId":"$developer"}""", as_ = leader).status }
			assertEquals(listOf(200, 409), race({ add(LENA) }, { add(COLE) }), "round $n: adding")
			assertEquals(1, dsl.fetchCount(PROJECT_MEMBER, PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.PERSON_ID.eq(member)), "round $n: on the team once")
		}
	}
}
