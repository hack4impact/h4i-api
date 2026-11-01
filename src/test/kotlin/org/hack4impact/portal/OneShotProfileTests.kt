package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.sync.SyncRecovery
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-shot profile every one-shot command joins (package 3 review R5): no scheduled jobs, no outbox replay, no sync
 * recovery or listeners, so a preflight against a deployment's database changes nothing there.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("one-shot")
class OneShotProfileTests(
	@Autowired private val context: ApplicationContext,
	@Autowired private val environment: Environment,
	@Autowired private val dsl: DSLContext,
	@Autowired private val recovery: SyncRecovery,
	@Autowired private val bootstrap: org.hack4impact.portal.tools.ToolSettingsBootstrap,
) {
	@Test
	fun `nothing runs in the background`() {
		assertTrue(context.getBeansOfType(Scheduling::class.java).isEmpty())
		assertEquals("false", environment.getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
		assertEquals("false", environment.getProperty("portal.sync.on-request"))
		assertEquals("false", environment.getProperty("portal.agreements.on-request"))

		dsl.emptyPortalTables()
		val run = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "running")
			.returning(SYNC_RUN.ID).fetchSingle().id
		recovery.onStartup()
		assertEquals("running", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(run)).fetchSingle().value1()) // left for the live app

		// No tool settings written either (package 8 review R2): the running app adds them.
		val setting = org.hack4impact.portal.db.tables.references.TOOL_SETTING
		dsl.deleteFrom(setting).execute()
		bootstrap.afterSingletonsInstantiated()
		assertEquals(0, dsl.fetchCount(setting))
	}

	@Test
	fun `every one-shot command joins it`() {
		val groups = listOf("preflight", "adapter-check", "import", "grant", "sync-dry-run", "sandbox-check", "sandbox-e2e")
		groups.forEach { assertEquals("one-shot", environment.getProperty("spring.profiles.group.$it")?.trim(), it) }
	}
}
