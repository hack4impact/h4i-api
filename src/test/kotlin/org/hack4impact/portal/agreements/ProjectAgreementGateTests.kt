package org.hack4impact.portal.agreements

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.assertEquals

/** Stage 1's send gate for project agreements too: Documenso connected but its write switch off sends nothing. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ProjectAgreementGateTests(@Autowired private val dsl: DSLContext, @Autowired private val agreements: AgreementService) {
	companion object {
		val documenso = WireMockServer(options().dynamicPort()).apply { start() }

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portal.adapters.documenso.enabled") { "true" }
			registry.add("portal.adapters.documenso.base-url") { documenso.baseUrl() }
			registry.add("portal.adapters.documenso.token") { "api_test" }
			registry.add("portal.adapters.documenso.write") { "false" }
			registry.add("portal.agreements.on-request") { "false" }
			registry.add("portal.agreements.schedule-enabled") { "false" }
		}
	}

	@Test
	fun `without the write switch no project agreement is sent`() {
		dsl.emptyPortalTables()
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val ada = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Ada").set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "ada@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, ada).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val role = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, ada).set(PROJECT_MEMBER.PROJECT_ROLE_ID, role).execute()
		dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, "project").set(AGREEMENT_TEMPLATE.NAME, "Project agreement")
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, "tmpl-project").set(AGREEMENT_TEMPLATE.VERSION, 1).execute()

		assertEquals(false, agreements.canSend())
		assertEquals(0, agreements.pass().sent)
		assertEquals(0, dsl.fetchCount(AGREEMENT))
		documenso.verify(0, postRequestedFor(urlEqualTo("/api/v2/envelope/use")))
	}
}
