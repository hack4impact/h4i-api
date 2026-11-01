package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The sync cases of the full portal's `ReviewFixTests` (2026-10-13 code reviews): recovery, triggers, one run at a time. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
class SyncRecoveryTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val locks: RunLocks,
	@Autowired private val syncs: SyncRequests,
	@Autowired private val recovery: SyncRecovery,
) {
	@BeforeEach
	fun empty() = dsl.emptyPortalTables()

	@Test
	fun `a run cut short by a crash is marked failed at startup and every tool is synced once (second review L5)`() {
		val finished = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "completed")
			.returning(SYNC_RUN.ID).fetchSingle().id
		val cut = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "running")
			.returning(SYNC_RUN.ID).fetchSingle().id
		val requested = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%recovery%'")) }
		val before = requested()
		assertEquals(1, recovery.recover())
		assertEquals("failed", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(cut)).fetchSingle().value1())
		assertEquals("completed", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(finished)).fetchSingle().value1())
		assertEquals(before + 1, requested())
		assertEquals(0, recovery.recover()) // nothing cut short: no extra sync
		assertEquals(before + 1, requested())
	}

	@Test
	fun `every trigger the code can ask for is one a sync run can record (review T1, H1)`() {
		for (trigger in SyncTriggers.ALL) {
			dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "dry_run").set(SYNC_RUN.TRIGGER, trigger).set(SYNC_RUN.STATUS, "completed").execute()
		}
		assertEquals(SyncTriggers.ALL.size, dsl.fetchCount(SYNC_RUN))
		// One the database would refuse fails where it's asked for, not later in the background.
		assertFailsWith<IllegalArgumentException> { syncs.request("typo") }
	}

	@Test
	fun `work under one lock never overlaps (review T2, H2)`() {
		val running = AtomicInteger()
		val most = AtomicInteger()
		val pool = Executors.newFixedThreadPool(4)
		repeat(8) {
			pool.submit {
				locks.exclusive(RunLocks.SYNC) {
					most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
					Thread.sleep(30)
					running.decrementAndGet()
				}
			}
		}
		pool.shutdown()
		pool.awaitTermination(30, TimeUnit.SECONDS)
		assertEquals(1, most.get())
	}
}
