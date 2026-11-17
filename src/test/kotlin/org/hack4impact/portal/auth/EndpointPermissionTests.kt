package org.hack4impact.portal.auth

import jakarta.servlet.http.Cookie
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who may call what, for every endpoint: each one is listed under a rule below and called as every kind of person.
 * Whoever the rule leaves out must be refused, and a signed-out caller gets 401. A new endpoint fails
 * [every endpoint has a rule here] until it's added to [RULES], so none ships without its access being decided.
 *
 * People the rule allows are only checked on GETs (the write flows have their own tests): a refused write changes
 * nothing, so one seeded database serves the whole table. Request bodies are made from `openapi.json`, so a refusal
 * is the access check's and not a body that couldn't be read.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class EndpointPermissionTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired @Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
	private enum class Who(val email: String) {
		/** Signed in with an H4I Google account the portal has no person for. */
		STRANGER("stranger@hack4impact.org"),
		/** A member of UMD without a role. */
		MEMBER("rudy@hack4impact.org"),
		/** The member whose agreement is seeded. */
		SIGNER("ada@hack4impact.org"),
		VIEWER("vic@hack4impact.org"),
		/** A lead, but of Georgia Tech: every chapter path below is UMD's. */
		OTHER_CHAPTER_LEAD("gus@hack4impact.org"),
		/** Leads the project in the paths, with no chapter role. */
		PROJECT_LEAD("pia@hack4impact.org"),
		/** Leads another UMD project. */
		OTHER_PROJECT_LEAD("otto@hack4impact.org"),
		CO_LEAD("cole@hack4impact.org"),
		LEAD("lena@hack4impact.org"),
		NATIONAL("nora@hack4impact.org"),
	}

	/** [allowed]: who gets past the access check. [refusal]: what everyone else signed in gets. [open]: no sign-in needed. */
	private enum class Rule(val allowed: Set<Who>, val refusal: Int = 403, val open: Boolean = false, val applicant: Boolean = false) {
		PUBLIC(Who.entries.toSet(), open = true),
		/** Its own check: Documenso's shared secret. */
		WEBHOOK(emptySet(), open = true),
		/** The applicant API before a session: the join page, codes, claim and roster links. */
		APPLY_OPEN(emptySet(), open = true, applicant = true),
		/** The applicant API with the applicant's cookie, which no Google sign-in stands in for. */
		APPLY_SESSION(emptySet(), applicant = true),
		SIGNED_IN(Who.entries.toSet()),
		/** Only the agreement's own person, and to anyone else it doesn't exist. */
		OWN_AGREEMENT(setOf(Who.SIGNER), refusal = 404),
		AGREEMENT_PDF(setOf(Who.SIGNER, Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		/** A lead or co-lead of any chapter, or national. */
		ANY_LEAD(setOf(Who.OTHER_CHAPTER_LEAD, Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		CHAPTER_READ(setOf(Who.VIEWER, Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		CHAPTER_MANAGE(setOf(Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		PROJECT_READ(setOf(Who.VIEWER, Who.PROJECT_LEAD, Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		PROJECT_TEAM(setOf(Who.PROJECT_LEAD, Who.CO_LEAD, Who.LEAD, Who.NATIONAL)),
		NATIONAL(setOf(Who.NATIONAL)),
	}

	private val json = JsonMapper.builder().build()

	/**
	 * The CSRF token as the frontend gets it: the cookie from any GET, sent back as a header. Not the test library's
	 * `csrf()`, which swaps the app's cookie store for its own for the rest of the run, and other tests that share this
	 * app instance check that cookie. Null only when an earlier test already made that swap: then `csrf()` it is.
	 */
	private val xsrf: Cookie? by lazy { mvc.perform(request(HttpMethod.GET, "/api/status")).andReturn().response.getCookie("XSRF-TOKEN") }
	private val spec: JsonNode = json.readTree(Files.readString(Path.of("openapi.json")))
	private lateinit var ada: UUID
	private lateinit var agreement: UUID

	private fun person(first: String, chapter: UUID, role: String? = null): UUID {
		val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active")
			.set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org").returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute()
		role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, it).execute() }
		return id
	}

	private fun project(chapter: UUID, name: String, slug: String, lead: UUID, leadRole: UUID): UUID {
		val id = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, chapter).set(PROJECT.NAME, name).set(PROJECT.SLUG, slug).set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, id).set(PROJECT_MEMBER.PERSON_ID, lead).set(PROJECT_MEMBER.PROJECT_ROLE_ID, leadRole).execute()
		return id
	}

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Hack4Impact Georgia Tech").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Rudy", umd)
		ada = person("Ada", umd)
		person("Vic", umd, "viewer")
		person("Gus", gt, "lead")
		person("Cole", umd, "co_lead")
		person("Lena", umd, "lead")
		dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, person("Nora", gt)).execute()
		val techLead = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Tech Lead").set(PROJECT_ROLE.IS_LEAD, true).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		val rise = project(umd, "RISE DC", "rise-dc", person("Pia", umd), techLead)
		project(umd, "Other", "other", person("Otto", umd), techLead)
		// Ada's signed project agreement, with its PDF.
		val template = dsl.fetchSingle(
			"INSERT INTO portal.agreement_template (kind, name, documenso_template_id, version) VALUES ('project', 'Project', 'tmpl', 1) RETURNING id",
		).get(0, UUID::class.java)
		agreement = dsl.fetchSingle(
			"INSERT INTO portal.agreement (template_id, person_id, project_id, status) VALUES (?, ?, ?, 'signed') RETURNING id", template, ada, rise,
		).get(0, UUID::class.java)
		dsl.execute("INSERT INTO portal.agreement_pdf (agreement_id, position, content, sha256) VALUES (?, 0, ?, 'x')", agreement, "%PDF".toByteArray())
	}

	/** "GET /api/chapters/{code}" for each of the app's own controller methods. */
	private fun endpoints(): Set<String> = mappings.handlerMethods
		.filter { (_, handler) -> handler.beanType.name.startsWith("org.hack4impact.portal") }
		.flatMap { (info, _) -> info.methodsCondition.methods.flatMap { method -> info.pathPatternsCondition!!.patternValues.map { "$method $it" } } }
		.toSet()

	@Test
	fun `every endpoint has a rule here`() {
		val listed = RULES.values.flatten()
		assertEquals(emptyList(), listed.groupBy { it }.filter { it.value.size > 1 }.keys.toList(), "listed under two rules")
		assertEquals(emptySet(), endpoints() - listed.toSet(), "endpoints without a rule: decide who may call each and add it to RULES")
		assertEquals(emptySet(), listed.toSet() - endpoints(), "rules for endpoints that no longer exist")
	}

	@TestFactory
	fun `each endpoint lets in only the people its rule names`(): List<DynamicTest> =
		RULES.flatMap { (rule, endpoints) -> endpoints.map { endpoint -> dynamicTest("$endpoint: $rule") { check(endpoint, rule) } } }

	private fun check(endpoint: String, rule: Rule) {
		val (method, pattern) = endpoint.split(" ")
		val write = method != "GET"
		assertEquals(if (rule.open) null else 401, call(method, pattern, null).takeIf { it == 401 || !rule.open }, "$endpoint signed out")
		if (rule.open && rule != Rule.WEBHOOK) assertTrue(call(method, pattern, null) !in setOf(401, 403), "$endpoint should need no sign-in")
		// Writes need the CSRF token, whoever asks (the webhook is the exception: Documenso can't send one).
		if (write && rule != Rule.WEBHOOK) assertEquals(403, call(method, pattern, Who.NATIONAL.takeUnless { rule.applicant }, csrf = false), "$endpoint without a CSRF token")
		if (rule.applicant || rule == Rule.WEBHOOK) return
		for (who in Who.entries) {
			if (who !in rule.allowed) assertEquals(rule.refusal, call(method, pattern, who), "$endpoint as $who should be refused")
			else if (!write) assertTrue(call(method, pattern, who) !in setOf(401, 403), "$endpoint as $who should be allowed")
		}
	}

	/** The status of one call, with the path's placeholders filled in and a body that fits the endpoint's schema. */
	private fun call(method: String, pattern: String, who: Who?, csrf: Boolean = true): Int {
		val operation = spec.path("paths").path(pattern).path(method.lowercase())
		val path = pattern
			.replace("{code}", "umd").replace("{slug}", "rise-dc").replace("{personId}", ada.toString())
			.replace("{tool}", "slack").replace("{kind}", "volunteer")
			.replace("{id}", if ("agreements/{id}" in pattern) agreement.toString() else UUID.randomUUID().toString())
			.replace(Regex("\\{\\w+}"), UUID.randomUUID().toString())
		val builder = request(HttpMethod.valueOf(method), path)
		operation.path("parameters").filter { it.path("in").asString() == "query" && it.path("required").asBoolean() }.forEach { builder.param(it.path("name").asString(), "x") }
		operation.path("requestBody").path("content").path("application/json").path("schema").takeIf { it.isObject }?.let {
			builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sample(it)))
		}
		who?.let { builder.with(oidcLogin().idToken { t -> t.subject("sub-${it.email}").claim("email", it.email).claim("email_verified", true).claim("hd", "hack4impact.org") }) }
		if (csrf) xsrf?.let { builder.cookie(it).header("X-XSRF-TOKEN", it.value) } ?: builder.with(csrf())
		return mvc.perform(builder).andReturn().response.status
	}

	/** A value that fits an OpenAPI schema: every property filled, so Kotlin's required fields are there. */
	private fun sample(schema: JsonNode): Any? {
		schema.path("\$ref").takeIf { it.isString }?.let { return sample(spec.path("components").path("schemas").path(it.asString().substringAfterLast('/'))) }
		schema.path("enum").takeIf { it.isArray && !it.isEmpty }?.let { return it[0].asString() }
		val type = schema.path("type").let { t -> if (t.isArray) (t as Iterable<JsonNode>).map { it.asString() }.firstOrNull { it != "null" } else t.asString(null) }
		return when (type) {
			"string" -> when (schema.path("format").asString("")) {
				"uuid" -> UUID.randomUUID().toString()
				"date" -> "2026-01-01"
				"date-time" -> "2026-01-01T00:00:00Z"
				else -> "x"
			}
			"integer", "number" -> 1
			"boolean" -> false
			"array" -> emptyList<Any>()
			else -> schema.path("properties").properties().associate { (name, property) -> name to sample(property) }
		}
	}

	private companion object {
		private const val IN_CHAPTER = "/api/chapters/{code}"
		private const val IN_PROJECT = "$IN_CHAPTER/projects/{slug}"
		private const val NATIONAL = "/api/national"

		val RULES: Map<Rule, List<String>> = mapOf(
			Rule.PUBLIC to listOf("GET /api/status"),
			Rule.WEBHOOK to listOf("POST /api/webhooks/documenso"),
			Rule.APPLY_OPEN to listOf(
				"GET /api/apply/chapters/{code}", "POST /api/apply/chapters/{code}/code", "POST /api/apply/chapters/{code}/verify",
				"POST /api/apply/claim", "POST /api/apply/roster",
			),
			Rule.APPLY_SESSION to listOf(
				"GET /api/apply/me", "PUT /api/apply/me", "POST /api/apply/me/agreements/{kind}", "POST /api/apply/me/submit",
				"GET /api/apply/github/start", "GET /api/apply/github/callback", "POST /api/apply/signout",
			),
			Rule.SIGNED_IN to listOf("GET /api/me", "GET /api/me/home", "GET /api/project-roles", "GET /api/tools"),
			Rule.OWN_AGREEMENT to listOf("POST /api/me/agreements/{id}/sign"),
			Rule.AGREEMENT_PDF to listOf("GET /api/agreements/{id}/pdf"),
			Rule.ANY_LEAD to listOf("GET /api/partners"),
			Rule.CHAPTER_READ to listOf(
				"GET $IN_CHAPTER", "GET $IN_CHAPTER/members", "GET $IN_CHAPTER/projects", "GET $IN_CHAPTER/registrations",
				"GET $IN_CHAPTER/registration-settings", "GET $IN_CHAPTER/roster",
			),
			Rule.CHAPTER_MANAGE to listOf(
				"GET $IN_CHAPTER/members/{personId}/access", "GET $IN_CHAPTER/past-projects", "GET $IN_CHAPTER/resources", "GET $IN_CHAPTER/terms",
				"GET $IN_CHAPTER/sync", "POST $IN_CHAPTER/sync/run",
				"POST $IN_CHAPTER/registrations/{id}/approve", "POST $IN_CHAPTER/registrations/{id}/decline",
				"PUT $IN_CHAPTER/registration-settings", "POST $IN_CHAPTER/roster",
				"POST $IN_CHAPTER/projects", "PUT $IN_PROJECT", "POST $IN_PROJECT/status",
				"POST $IN_PROJECT/resources", "PUT $IN_PROJECT/resources/{resourceId}", "DELETE $IN_PROJECT/resources/{resourceId}",
			),
			Rule.PROJECT_READ to listOf("GET $IN_PROJECT", "GET $IN_PROJECT/activity"),
			Rule.PROJECT_TEAM to listOf(
				"GET $IN_PROJECT/candidates", "POST $IN_PROJECT/members", "PUT $IN_PROJECT/members/{personId}", "DELETE $IN_PROJECT/members/{personId}",
			),
			Rule.NATIONAL to listOf(
				"GET $NATIONAL/agreement-templates", "POST $NATIONAL/agreement-templates", "POST $NATIONAL/agreement-templates/{id}/deactivate",
				"GET $NATIONAL/chapters/{code}/resources", "POST $NATIONAL/chapters/{code}/resources",
				"GET $NATIONAL/resources/{id}/link", "POST $NATIONAL/resources/{id}/link", "DELETE $NATIONAL/resources/{id}",
				"GET $NATIONAL/claims/preview", "POST $NATIONAL/claims/send", "POST $NATIONAL/claims/people/{personId}",
				"GET $NATIONAL/queue", "POST $NATIONAL/queue/{id}",
				"GET $NATIONAL/tools", "PUT $NATIONAL/tools/{tool}",
				"GET $NATIONAL/dead-letters", "POST $NATIONAL/dead-letters/retry", "POST $NATIONAL/sync/run",
				"GET $NATIONAL/member-db", "PUT $NATIONAL/member-db", "POST $NATIONAL/member-db/pull",
				"GET $NATIONAL/member-db/writes/{personId}/preview", "POST $NATIONAL/member-db/writes/{personId}/retry",
				"GET $NATIONAL/member-db/projects/{id}/preview", "POST $NATIONAL/member-db/projects/{id}/retry",
				"GET $NATIONAL/member-db/leadership/{chapterRoleId}/preview", "POST $NATIONAL/member-db/leadership/{chapterRoleId}/retry",
				"POST $NATIONAL/partners",
			),
		)
	}
}
