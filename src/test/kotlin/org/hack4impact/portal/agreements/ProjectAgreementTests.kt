package org.hack4impact.portal.agreements

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_PDF
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.mail.MailSender
import org.hack4impact.portal.registration.ApplicantSessions
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectAgreementMail : MailSender {
	data class Sent(val to: String, val subject: String, val text: String)

	val sent = mutableListOf<Sent>()
	override fun send(to: String, subject: String, text: String) { sent += Sent(to, subject, text) }
}

@TestConfiguration(proxyBeanMethods = false)
class ProjectAgreementFakes {
	companion object {
		/** Fake Documenso (envelope API v2). */
		val documenso = WireMockServer(options().dynamicPort()).apply { start() }
	}

	@Bean
	@Primary
	fun agreementMail() = ProjectAgreementMail()
}

/**
 * Stage 2 package 6: project agreements through a fake Documenso, from sending to the signature releasing the GitHub
 * team and vault collection (the full portal's step 11 tests). Registration agreements are stage 1's `AgreementTests`.
 */
@Import(TestcontainersConfiguration::class, ProjectAgreementFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectAgreementTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val agreements: AgreementService,
	@Autowired private val mail: ProjectAgreementMail,
	@Autowired private val worlds: org.hack4impact.portal.sync.WorldLoader,
	@Autowired private val sessions: ApplicantSessions,
) {
	companion object {
		const val SECRET = "whsec-test"

		@JvmStatic
		@DynamicPropertySource
		fun documenso(registry: DynamicPropertyRegistry) {
			registry.add("portal.adapters.documenso.enabled") { "true" }
			registry.add("portal.adapters.documenso.base-url") { ProjectAgreementFakes.documenso.baseUrl() }
			registry.add("portal.frontend-url") { "https://portal.example" }
			registry.add("portal.adapters.documenso.token") { "api_test" }
			registry.add("portal.adapters.documenso.webhook-secret") { SECRET }
			registry.add("portal.adapters.documenso.write") { "true" }
			registry.add("portal.agreements.on-request") { "false" }
			registry.add("portal.agreements.schedule-enabled") { "false" }
			registry.add("portal.sync.on-request") { "false" }
		}
	}

	private val server = ProjectAgreementFakes.documenso
	private lateinit var umd: UUID
	private lateinit var project: UUID
	private lateinit var ada: UUID
	private lateinit var developer: UUID
	private lateinit var team: UUID

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

	private fun template(kind: String, documensoId: String) =
		dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, kind).set(AGREEMENT_TEMPLATE.NAME, mapOf("project" to "Project agreement", "volunteer" to "Volunteer agreement", "code_of_conduct" to "Code of conduct").getValue(kind))
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, documensoId).set(AGREEMENT_TEMPLATE.VERSION, 1).execute()

	private fun stubJson(path: String, body: String, method: String = "GET") =
		server.stubFor((if (method == "GET") get(urlPathEqualTo(path)) else post(urlPathEqualTo(path))).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))

	/** An envelope as Documenso returns it: one signer, one PDF. */
	private fun envelope(id: String, status: String, signing: String = "NOT_SIGNED", rejection: String? = null) = stubJson(
		"/api/v2/envelope/$id",
		"""{"id":"$id","status":"$status","externalId":null,"completedAt":${if (status == "COMPLETED") "\"2026-10-11T12:00:00.000Z\"" else "null"},
			"recipients":[{"id":70,"role":"SIGNER","email":"ada@hack4impact.org","token":"tok-$id","signingStatus":"$signing","rejectionReason":${rejection?.let { "\"$it\"" } ?: "null"}}],
			"envelopeItems":[{"id":"item-$id","order":1}]}""",
	)

	private fun agreement() = dsl.selectFrom(AGREEMENT).orderBy(AGREEMENT.CREATED_AT.desc()).limit(1).fetchSingle()
	private fun memberStatus() = dsl.select(PROJECT_MEMBER.AGREEMENT_STATUS).from(PROJECT_MEMBER).where(PROJECT_MEMBER.PERSON_ID.eq(ada)).fetchSingle().value1()

	private fun addAda() = json("/api/chapters/umd/projects/rise-dc/members", """{"personId":"$ada","roleId":"$developer"}""", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }

	/** Whether the resolver gives Ada the project's GitHub team, or holds it for the agreement. */
	private fun adaHasTeam() = org.hack4impact.portal.resolver.Resolver.resolve(worlds.load().world).grants.any { it.personId == ada && it.resourceId == team }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		mail.sent.clear()
		server.resetAll()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lead")
		person("Vic", "viewer")
		ada = person("Ada")
		project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		// The project's GitHub team, held until the agreement is signed (as the template sets it up).
		team = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "github").set(RESOURCE.NAME, "umd-rise-dc").set(RESOURCE.CHAPTER_ID, umd).returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, team).set(PROJECT_RESOURCE.AUDIENCE, "team")
			.set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, true).execute()
		template("project", "tmpl-project")
		// The template's signer placeholder, a new envelope, its PDF, and Documenso's reminder and cancel.
		stubJson("/api/v2/envelope/tmpl-project", """{"id":"tmpl-project","type":"TEMPLATE","status":"DRAFT","recipients":[{"id":7,"role":"SIGNER","email":"","token":"x","signingStatus":"NOT_SIGNED"}],"envelopeItems":[]}""")
		stubJson("/api/v2/envelope/use", """{"id":"env-1","recipients":[]}""", "POST")
		envelope("env-1", "PENDING")
		server.stubFor(get(urlPathEqualTo("/api/v2/envelope/item/item-env-1/download")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF-1.7 signed".toByteArray())))
		stubJson("/api/v2/envelope/redistribute", """{"success":true,"id":"env-1","recipients":[]}""", "POST")
		stubJson("/api/v2/envelope/cancel", """{"success":true}""", "POST")
	}

	@Test
	fun `joining a project sends the agreement, and the signature releases gated access and archives the PDF`() {
		assertEquals(0, dsl.fetchCount(TOOL_SETTING, TOOL_SETTING.TOOL.eq("documenso"))) // stage 1's gate: no tool setting, and it still sends
		addAda()
		assertEquals(1, agreements.pass().sent)
		val sent = server.allServeEvents.single { it.request.url == "/api/v2/envelope/use" }.request
		assertEquals("api_test", sent.getHeader("Authorization"))
		val payload = HttpJson.MAPPER.readTree(Regex("""name="payload"\r\n\r\n(.*)\r\n--""", RegexOption.DOT_MATCHES_ALL).find(sent.bodyAsString)!!.groupValues[1])
		assertEquals("tmpl-project", payload.path("envelopeId").asString())
		assertEquals(7, payload.path("recipients")[0].path("id").asInt()) // the template's signer, filled with Ada
		assertEquals("ada@hack4impact.org", payload.path("recipients")[0].path("email").asString())
		assertEquals("Project agreement: RISE DC", payload.path("override").path("title").asString())
		assertEquals("EMAIL", payload.path("override").path("distributionMethod").asString())
		assertTrue("https://portal.example/agreements/${agreement().id}" in sent.bodyAsString) // back to the frontend's agreement page
		with(agreement()) {
			assertEquals("sent", status)
			assertEquals("env-1", documensoDocumentId)
		}
		agreements.pass()
		assertEquals("pending", memberStatus()) // still pending in Documenso
		assertEquals(false, adaHasTeam())
		mvc.get("/api/chapters/umd/projects/rise-dc") { with(signedIn("lena@hack4impact.org")) }.andExpect { jsonPath("$.members[?(@.name == 'Ada Test')].agreement.status") { value("sent") } }

		envelope("env-1", "COMPLETED", "SIGNED")
		val syncs = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%\"trigger\":\"agreement\"%'")) }
		agreements.pass()
		assertEquals("signed", memberStatus())
		assertTrue(adaHasTeam()) // the resolver now releases the GitHub team and vault collection
		assertEquals(1, syncs()) // and a sync of those tools is asked for
		with(agreement()) {
			assertEquals("signed", status)
			assertEquals("database", pdfLocation)
			assertNotNull(signedAt)
		}
		assertEquals("%PDF-1.7 signed", String(dsl.select(AGREEMENT_PDF.CONTENT).from(AGREEMENT_PDF).fetchSingle().value1()!!))
		val id = agreement().id
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			status { isOk() }
			content { contentType(MediaType.APPLICATION_PDF) }
		}
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("vic@hack4impact.org")) }.andExpect { status { isForbidden() } } // a viewer
		mvc.get("/api/agreements/$id/pdf") { with(signedIn("ada@hack4impact.org")) }.andExpect { status { isOk() } } // the signer
		mvc.get("/api/chapters/umd/projects/rise-dc/activity") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			jsonPath("$.items[?(@.kind == 'agreement')].text") { value("Ada Test signed the project agreement") } // dated when Documenso says it was signed
		}
	}

	@Test
	fun `a member opens their unsigned agreement from their page`() {
		addAda()
		agreements.pass()
		val id = agreement().id
		mvc.get("/api/me/home") { with(signedIn("ada@hack4impact.org")) }.andExpect {
			jsonPath("$.agreements[0].project") { value("RISE DC") }
			jsonPath("$.agreements[0].canSign") { value(true) }
			jsonPath("$.access[?(@.tool == 'github')].state") { value("after_agreement") } // the agreement can be sent here
			jsonPath("$.access[?(@.tool == 'github')].reasons[0]") { value("After you sign the RISE DC agreement") }
		}
		json("/api/me/agreements/$id/sign", "{}", as_ = "ada@hack4impact.org").andExpect { jsonPath("$.url") { value("${server.baseUrl()}/sign/tok-env-1") } }
		json("/api/me/agreements/$id/sign", "{}", as_ = "lena@hack4impact.org").andExpect { status { isNotFound() } } // not hers
		envelope("env-1", "COMPLETED", "SIGNED")
		agreements.pass()
		json("/api/me/agreements/$id/sign", "{}", as_ = "ada@hack4impact.org").andExpect { status { isConflict() } } // signed already
	}

	@Test
	fun `a draft project sends nothing until it starts`() {
		dsl.update(PROJECT).set(PROJECT.STATUS, "draft").set(PROJECT.PARTNER_ID, dsl.insertInto(org.hack4impact.portal.db.tables.references.PARTNER)
			.set(org.hack4impact.portal.db.tables.references.PARTNER.NAME, "RISE").returningResult(org.hack4impact.portal.db.tables.references.PARTNER.ID).fetchSingle().value1()).execute()
		addAda()
		assertEquals(0, agreements.pass().sent)
		json("/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }
		assertEquals(1, agreements.pass().sent)
	}

	@Test
	fun `the webhook needs the secret, and only makes the portal re-read the envelope`() {
		addAda()
		agreements.pass()
		envelope("env-1", "COMPLETED", "SIGNED")
		val hook = """{"event":"DOCUMENT_COMPLETED","payload":{"envelopeId":"env-1","status":"COMPLETED"},"createdAt":"2026-10-11T12:00:00Z","webhookEndpoint":"x"}"""
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook }.andExpect { status { isUnauthorized() } }
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook; header("X-Documenso-Secret", "wrong") }.andExpect { status { isUnauthorized() } }
		assertEquals("pending", memberStatus())
		mvc.post("/api/webhooks/documenso") { contentType = MediaType.APPLICATION_JSON; content = hook; header("X-Documenso-Secret", SECRET) }.andExpect { status { isAccepted() } }
		val until = System.currentTimeMillis() + 10_000
		while (memberStatus() != "signed" && System.currentTimeMillis() < until) Thread.sleep(100) // handled after the request, in the background
		assertEquals("signed", memberStatus())
	}

	@Test
	fun `unsigned agreements get reminders, then the leads hear, and leaving the project cancels it`() {
		addAda()
		agreements.pass()
		dsl.update(AGREEMENT).set(AGREEMENT.SENT_AT, OffsetDateTime.now().minusDays(4)).execute()
		assertEquals(1, agreements.pass().reminded)
		server.verify(1, postRequestedFor(urlEqualTo("/api/v2/envelope/redistribute")).withRequestBody(containing("\"recipients\":[70]")))
		assertEquals(0, agreements.pass().reminded) // not again until remind-every has passed
		dsl.update(AGREEMENT).set(AGREEMENT.SENT_AT, OffsetDateTime.now().minusDays(8)).execute()
		agreements.pass()
		assertTrue(mail.sent.single { it.to == "lena@hack4impact.org" }.subject.contains("hasn't signed the RISE DC agreement"))
		agreements.pass()
		assertEquals(1, mail.sent.count { it.to == "lena@hack4impact.org" }) // once

		mvc.delete("/api/chapters/umd/projects/rise-dc/members/$ada") { with(csrf()); with(signedIn("lena@hack4impact.org")) }.andExpect { status { isOk() } }
		assertEquals(1, agreements.pass().voided)
		server.verify(1, postRequestedFor(urlEqualTo("/api/v2/envelope/cancel")))
		assertEquals("voided", agreement().status)
	}

	@Test
	fun `a declined agreement tells the leads why, and someone who signed before counts as signed when they rejoin`() {
		addAda()
		agreements.pass()
		envelope("env-1", "REJECTED", "REJECTED", "I need to read it with my advisor")
		agreements.pass()
		assertEquals("declined", agreement().status)
		assertTrue(mail.sent.single { it.to == "lena@hack4impact.org" }.text.contains("I need to read it with my advisor"))
		// The next pass doesn't send a new envelope (second review M1).
		server.resetRequests()
		agreements.pass()
		server.verify(0, postRequestedFor(urlEqualTo("/api/v2/envelope/use")))

		// Signed for this project before, left, and came back: no new envelope.
		dsl.update(AGREEMENT).set(AGREEMENT.STATUS, "signed").execute()
		mvc.delete("/api/chapters/umd/projects/rise-dc/members/$ada") { with(csrf()); with(signedIn("lena@hack4impact.org")) }
		addAda()
		server.resetRequests()
		agreements.pass()
		assertEquals("signed", dsl.select(PROJECT_MEMBER.AGREEMENT_STATUS).from(PROJECT_MEMBER).where(PROJECT_MEMBER.PERSON_ID.eq(ada), PROJECT_MEMBER.REMOVED_AT.isNull).fetchSingle().value1())
		server.verify(0, postRequestedFor(urlEqualTo("/api/v2/envelope/use")))
	}

	@Test
	fun `national sets templates after Documenso confirms them, and a new one is the next version`() {
		dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, dsl.select(PERSON.ID).from(PERSON).where(PERSON.FIRST_NAME.eq("Lena")).fetchSingle().value1()).execute()
		server.stubFor(get(urlPathEqualTo("/api/v2/envelope/missing")).willReturn(aResponse().withStatus(404).withBody("{}")))
		stubJson("/api/v2/envelope/tmpl-project-2", """{"id":"tmpl-project-2","type":"TEMPLATE","status":"DRAFT","recipients":[{"id":9,"role":"SIGNER","email":"","token":"x","signingStatus":"NOT_SIGNED"}],"envelopeItems":[]}""")
		json("/api/national/agreement-templates", """{"kind":"project","name":"Project agreement","documensoTemplateId":"missing"}""", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		json("/api/national/agreement-templates", """{"kind":"project","name":"Project agreement","documensoTemplateId":"missing"}""", as_ = "lena@hack4impact.org").andExpect { status { isBadRequest() } }
		json("/api/national/agreement-templates", """{"kind":"project","name":"Project agreement 2027","documensoTemplateId":"https://sign.example/t/tmpl-project-2"}""", as_ = "lena@hack4impact.org")
			.andExpect {
				status { isOk() }
				jsonPath("$.templates[?(@.active == true)].documensoTemplateId") { value("tmpl-project-2") } // pasted as a link
				jsonPath("$.templates[?(@.active == true)].version") { value(2) }
				jsonPath("$.sending") { value(true) }
			}
		assertEquals(1, dsl.fetchCount(AGREEMENT_TEMPLATE, AGREEMENT_TEMPLATE.KIND.eq("project"), AGREEMENT_TEMPLATE.ACTIVE.isTrue))
	}
}
