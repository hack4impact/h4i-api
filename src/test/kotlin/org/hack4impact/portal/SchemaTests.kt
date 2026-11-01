package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class SchemaTests(@Autowired private val dsl: DSLContext) {

	@Test
	fun `person status must be one of the PRD statuses`() {
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B").set(PERSON.STATUS, "hiatus").execute()
		}
	}

	@Test
	fun `a tool account needs an ID or a login`() {
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "github").execute()
		}
	}

	@Test
	fun `tool names are well-formed (which tools exist is the app's tool registry, V15)`() {
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		// A plugin's tool needs no migration (checked first: the refused insert at the end aborts the transaction).
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "figma").set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "x").execute()
		// Asked without inserting: a refused insert would abort the test's transaction for the rest.
		for (bad in listOf("LinkedIn", "linked in", "", "1password")) {
			assertEquals(false, dsl.fetchValue("SELECT pg_input_is_valid(?, 'portal.tool_name')", bad), bad)
		}
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "Bad Tool").set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "x").execute()
		}
	}

	@Test
	fun `every project member has a role, and one open membership per project (V4)`() {
		val chapter = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "tst").set(CHAPTER.NAME, "Test")
			.returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		val role = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer")
			.returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, chapter).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		assertEquals("draft", dsl.select(PROJECT.STATUS).from(PROJECT).where(PROJECT.ID.eq(project)).fetchSingle().value1())

		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, person)
			.set(PROJECT_MEMBER.PROJECT_ROLE_ID, role).execute()
		// Asked without inserting: a refused insert would abort the test's transaction for the rest.
		assertEquals(
			false,
			dsl.fetchValue("SELECT is_nullable = 'YES' FROM information_schema.columns WHERE table_schema = 'portal' AND table_name = 'project_member' AND column_name = 'project_role_id'"),
		)
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, person)
				.set(PROJECT_MEMBER.PROJECT_ROLE_ID, role).execute()
		}
	}

	@Test
	fun `the national template gives a team its channel and group, and its GitHub team and vault collection after the agreement (V4)`() {
		val template = dsl.selectFrom(RESOURCE_TEMPLATE).where(RESOURCE_TEMPLATE.CHAPTER_ID.isNull).orderBy(RESOURCE_TEMPLATE.POSITION).fetch()
		assertEquals(listOf("slack", "google", "github", "vaultwarden"), template.map { it.tool })
		assertEquals(listOf(false, false, true, true), template.map { it.requiresAgreement })
		assertTrue(template.all { it.audience == "team" && it.accessLevel == "write" })
	}
}
