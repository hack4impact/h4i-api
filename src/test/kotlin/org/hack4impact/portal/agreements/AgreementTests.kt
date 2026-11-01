package org.hack4impact.portal.agreements

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.registration.ApplicantSessions
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Q52: the document applicants sign through Documenso during registration, against a fake Documenso (envelope API v2). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AgreementTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val agreements: AgreementService,
	@Autowired private val sessions: ApplicantSessions,
) {
	companion object {
		const val SECRET = "whsec-test"
		val documenso = WireMockServer(options().dynamicPort()).apply { start() }

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portal.adapters.documenso.enabled") { "true" }
			registry.add("portal.adapters.documenso.base-url") { documenso.baseUrl() }
			registry.add("portal.adapters.documenso.token") { "api_test" }
			registry.add("portal.adapters.documenso.webhook-secret") { SECRET }
			registry.add("portal.adapters.documenso.write") { "true" }
		}
	}

	private val server = documenso
	private lateinit var umd: UUID
	private lateinit var registration: UUID
	private lateinit var cookie: Cookie

	private fun signedIn(email: String) = oidcLogin().idToken { it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org") }

	private fun json(path: String, body: String, as_: String? = null, cookie: Cookie? = null): ResultActionsDsl = mvc.post(path) {
		with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body
		as_?.let { with(signedIn(it)) }
		cookie?.let { cookie(it) }
	}

	private fun person(first: String, chapterRole: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				chapterRole?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, it).execute() }
			}

	private fun stubJson(path: String, body: String, method: String = "GET") =
		server.stubFor((if (method == "GET") get(urlPathEqualTo(path)) else post(urlPathEqualTo(path))).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))

	/** An envelope as Documenso returns it: one signer, one PDF. */
	private fun envelope(id: String, status: String, signing: String = "NOT_SIGNED", rejection: String? = null) = stubJson(
		"/api/v2/envelope/$id",
		"""{"id":"$id","status":"$status","externalId":null,"completedAt":${if (status == "COMPLETED") "\"2026-10-14T12:00:00.000Z\"" else "null"},
			"recipients":[{"id":70,"role":"SIGNER","email":"grace@umd.edu","token":"tok-$id","signingStatus":"$signing","rejectionReason":${rejection?.let { "\"$it\"" } ?: "null"}}],
			"envelopeItems":[{"id":"item-$id","order":1}]}""",
	)

	private fun status() = dsl.select(AGREEMENT.STATUS).from(AGREEMENT).fetchSingle().value1()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		server.resetAll()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lead")
		person("Vic", "viewer")
		person("Nora").also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, "volunteer").set(AGREEMENT_TEMPLATE.NAME, "Volunteer agreement")
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, "tmpl-volunteer").set(AGREEMENT_TEMPLATE.VERSION, 1).execute()
		stubJson("/api/v2/envelope/tmpl-volunteer", """{"id":"tmpl-volunteer","type":"TEMPLATE","status":"DRAFT","recipients":[{"id":8,"role":"SIGNER","email":"","token":"x","signingStatus":"NOT_SIGNED"}],"envelopeItems":[]}""")
		stubJson("/api/v2/envelope/use", """{"id":"env-1","recipients":[]}""", "POST")
		envelope("env-1", "PENDING")
		server.stubFor(get(urlPathEqualTo("/api/v2/envelope/item/item-env-1/download")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF-1.7 signed".toByteArray())))
		stubJson("/api/v2/envelope/cancel", """{"success":true}""", "POST")
		registration = dsl.insertInto(REGISTRATION).set(REGISTRATION.CHAPTER_ID, umd).set(REGISTRATION.KIND, "new").set(REGISTRATION.SCHOOL_EMAIL, "grace@umd.edu")
			.set(REGISTRATION.EMAIL_VERIFIED_AT, OffsetDateTime.now()).set(REGISTRATION.FIRST_NAME, "Grace").set(REGISTRATION.LAST_NAME, "Hopper")
			.returningResult(REGISTRATION.ID).fetchSingle().value1()!!
		cookie = MockHttpServletResponse().also { sessions.start(registration, it) }.getCookie(ApplicantSessions.COOKIE)!!
	}

	@Test
	fun `applicants sign the document before sending, on the page, and it becomes the member's`() {
		mvc.get("/api/apply/me") { cookie(cookie) }.andExpect {
			jsonPath("$.agreements[0].status") { value("not_started") }
			jsonPath("$.missing") { value(org.hamcrest.Matchers.hasItem("the Volunteer agreement")) }
		}
		json("/api/apply/me/agreements/volunteer", "{}", cookie = cookie).andExpect { jsonPath("$.url") { value("${server.baseUrl()}/sign/tok-env-1") } }
		val use = server.allServeEvents.first { it.request.url == "/api/v2/envelope/use" }.request.bodyAsString
		assertTrue("\"distributionMethod\":\"NONE\"" in use) // signed right away; no email
		assertTrue("/apply?signed=volunteer" in use)
		assertTrue("grace@umd.edu" in use)

		envelope("env-1", "COMPLETED", "SIGNED")
		mvc.get("/api/apply/me") { cookie(cookie) }.andExpect {
			jsonPath("$.agreements[0].status") { value("signed") }
			jsonPath("$.missing") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("the Volunteer agreement"))) }
		}
		val ada = person("Grace")
		agreements.attach(registration, ada) // what approval does
		assertEquals(1, dsl.fetchCount(AGREEMENT, AGREEMENT.PERSON_ID.eq(ada), AGREEMENT.STATUS.eq("signed")))
		json("/api/apply/me/agreements/volunteer", "{}", cookie = cookie).andExpect { status { isConflict() } } // already signed
	}

	@Test
	fun `the webhook needs the secret and only makes the portal re-read the envelope, which keeps the signed PDF for the chapter's leads`() {
		json("/api/apply/me/agreements/volunteer", "{}", cookie = cookie).andExpect { status { isOk() } }
		envelope("env-1", "COMPLETED", "SIGNED")
		val hook = """{"event":"DOCUMENT_COMPLETED","payload":{"envelopeId":"env-1","status":"COMPLETED"},"createdAt":"2026-10-14T12:00:00Z","webhookEndpoint":"x"}"""
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook }.andExpect { status { isUnauthorized() } }
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook; header("X-Documenso-Secret", "wrong") }.andExpect { status { isUnauthorized() } }
		assertEquals("sent", status())
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook; header("X-Documenso-Secret", SECRET) }.andExpect { status { isAccepted() } }
		val until = System.currentTimeMillis() + 10_000
		while (status() != "signed" && System.currentTimeMillis() < until) Thread.sleep(100) // handled after the request, in the background
		assertEquals("signed", status())

		val id = dsl.select(AGREEMENT.ID).from(AGREEMENT).fetchSingle().value1()
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isOk() }; content { contentType(MediaType.APPLICATION_PDF) } }
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("nora@hack4impact.org")) }.andExpect { status { isOk() } }
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("vic@hack4impact.org")) }.andExpect { status { isForbidden() } } // a viewer

		// Once approved, it's on the member's own page, and they can download it.
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("grace@hack4impact.org")) }.andExpect { status { isForbidden() } }
		agreements.attach(registration, person("Grace"))
		mvc.get("/api/me/home") { with(signedIn("grace@hack4impact.org")) }.andExpect {
			jsonPath("$.agreements[0].id") { value(id.toString()) }
			jsonPath("$.agreements[0].name") { value("Volunteer agreement") }
			jsonPath("$.agreements[0].status") { value("signed") }
			jsonPath("$.agreements[0].hasPdf") { value(true) }
		}
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("grace@hack4impact.org")) }.andExpect { status { isOk() } }
	}

	@Test
	fun `the regular pass cancels the document of a declined application`() {
		json("/api/apply/me/agreements/volunteer", "{}", cookie = cookie).andExpect { status { isOk() } }
		dsl.update(REGISTRATION).set(REGISTRATION.STATUS, "declined").where(REGISTRATION.ID.eq(registration)).execute()
		agreements.pass()
		assertEquals("voided", status())
		assertTrue(server.allServeEvents.any { it.request.url == "/api/v2/envelope/cancel" })
	}

	@Test
	fun `national sets the template after Documenso confirms it, and only the three kinds exist`() {
		server.stubFor(get(urlPathEqualTo("/api/v2/envelope/missing")).willReturn(aResponse().withStatus(404).withBody("{}")))
		stubJson("/api/v2/envelope/tmpl-volunteer-2", """{"id":"tmpl-volunteer-2","type":"TEMPLATE","status":"DRAFT","recipients":[{"id":9,"role":"SIGNER","email":"","token":"x","signingStatus":"NOT_SIGNED"}],"envelopeItems":[]}""")
		json("/api/national/agreement-templates", """{"kind":"volunteer","name":"Volunteer agreement","documensoTemplateId":"missing"}""", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		json("/api/national/agreement-templates", """{"kind":"volunteer","name":"Volunteer agreement","documensoTemplateId":"missing"}""", as_ = "nora@hack4impact.org").andExpect { status { isBadRequest() } }
		json("/api/national/agreement-templates", """{"kind":"nda","name":"NDA","documensoTemplateId":"tmpl-volunteer-2"}""", as_ = "nora@hack4impact.org").andExpect { status { isBadRequest() } } // project agreements came with stage 2
		json("/api/national/agreement-templates", """{"kind":"volunteer","name":"Volunteer agreement 2027","documensoTemplateId":"https://sign.example/t/tmpl-volunteer-2"}""", as_ = "nora@hack4impact.org")
			.andExpect {
				status { isOk() }
				jsonPath("$.templates[?(@.active == true)].documensoTemplateId") { value("tmpl-volunteer-2") } // pasted as a link
				jsonPath("$.templates[?(@.active == true)].version") { value(2) }
				jsonPath("$.sending") { value(true) }
			}
		assertEquals(1, dsl.fetchCount(AGREEMENT_TEMPLATE, AGREEMENT_TEMPLATE.KIND.eq("volunteer"), AGREEMENT_TEMPLATE.ACTIVE.isTrue))
	}
}
