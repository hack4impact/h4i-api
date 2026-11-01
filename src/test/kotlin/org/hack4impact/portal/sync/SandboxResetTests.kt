package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.tools.ToolRegistry
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The sandbox reset empties only a sandbox's database (package 9 review R3), and puts back the national template. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
class SandboxResetTests(@Autowired private val dsl: DSLContext, @Autowired private val dataSource: DataSource, @Autowired private val tools: ToolRegistry) {
	@BeforeEach
	fun empty() = dsl.emptyPortalTables()

	private fun person(sourceId: UUID? = null) =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Ada").set(PERSON.LAST_NAME, "Lovelace").set(PERSON.SOURCE_ID, sourceId).execute()

	private fun reset() = resetSandboxDatabase(dsl, dataSource, tools, confirmed = true, flag = "portal.sandbox-e2e.reset-database")

	@Test
	fun `a database with someone imported from the member database is refused, and nothing is emptied`() {
		person(UUID.randomUUID())
		val e = assertFailsWith<IllegalStateException> { reset() }
		assertContains(e.message!!, "looks like a deployment's")
		assertEquals(1, dsl.fetchCount(PERSON))
	}

	@Test
	fun `a database with more people than a sandbox holds is refused`() {
		repeat(MAX_SANDBOX_PEOPLE + 1) { person() }
		assertFailsWith<IllegalStateException> { reset() }
		assertEquals(MAX_SANDBOX_PEOPLE + 1, dsl.fetchCount(PERSON))
	}

	@Test
	fun `a sandbox's database is emptied, with the national template and tool settings put back`() {
		repeat(3) { person() }
		reset()
		assertEquals(0, dsl.fetchCount(PERSON))
		assertEquals(4, dsl.fetchCount(RESOURCE_TEMPLATE, RESOURCE_TEMPLATE.CHAPTER_ID.isNull))
	}
}
