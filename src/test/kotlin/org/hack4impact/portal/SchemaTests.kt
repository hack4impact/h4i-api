package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.PERSON
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
}
