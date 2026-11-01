package org.hack4impact.portal.sync

import org.hack4impact.portal.resolver.Tool
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.system.exitProcess

/**
 * Asks for a sync. Published inside a transaction, so Spring Modulith stores it in the outbox (event_publication)
 * together with whatever data change caused it, and retries it if the app stops before the sync finishes.
 * [trigger] is one of [SyncTriggers.ALL] (sync_run.trigger).
 */
data class SyncRequested(val trigger: String, val tools: Set<Tool>? = null)

object SyncTriggers {
	/** What may start a sync; `sync_run_trigger_check` (V16) allows exactly these (a test keeps them in step). */
	val ALL = setOf("schedule", "release", "manual", "agreement", "rule", "change", "recovery")
}

/** Asks for a dry run of every tool: each is planned and nothing is changed, even in tools that are out of dry run. */
data class DryRunRequested(val trigger: String = "manual")

@Component
class SyncRequests(private val events: ApplicationEventPublisher) {
	/** A dry run of every tool, after commit and in the background (a project page's "Run dry run"). */
	@Transactional
	fun dryRun() = events.publishEvent(DryRunRequested())

	@Transactional
	fun request(trigger: String, tools: Set<Tool>? = null) {
		// Fails here, in the request that asked, rather than later in the background when the run is recorded (review H1).
		require(trigger in SyncTriggers.ALL) { "Unknown sync trigger $trigger" }
		events.publishEvent(SyncRequested(trigger, tools))
	}
}

/**
 * Runs requested syncs after the requesting transaction commits, in the background. `portal.sync.on-request=false`
 * turns that off (the sandbox suite runs the engine itself and mustn't overlap a background run).
 */
@Component
class SyncListener(private val engine: SyncEngine, @org.springframework.beans.factory.annotation.Value("\${portal.sync.on-request:true}") private val enabled: Boolean) {
	private val log = LoggerFactory.getLogger(javaClass)

	// Requests that arrived while a run was going: they're merged into one more run when it finishes (review H2).
	private var running = false
	private var allTools = false
	private val tools = mutableSetOf<Tool>()
	private var trigger: String? = null

	/**
	 * Outside any transaction (second review H2): each run, change and created resource commits as it happens, so a
	 * failure later on can't roll back the record of changes already made in the tools, and no row stays locked for
	 * the whole run.
	 */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	fun on(event: SyncRequested) {
		if (!enabled) return
		synchronized(this) {
			if (event.tools == null) allTools = true else tools += event.tools
			if (trigger == null) trigger = event.trigger
			if (running) return // the run going now starts another when it finishes, which reads this request's change
			running = true
		}
		while (true) {
			val (which, these) = synchronized(this) {
				if (!allTools && tools.isEmpty()) {
					running = false
					return
				}
				val next = (trigger ?: "manual") to (if (allTools) null else tools.toSet())
				allTools = false
				tools.clear()
				trigger = null
				next
			}
			try {
				engine.run(which, these).forEach { log.info("Sync {} ({}): {}", it.tool, which, it) }
			} catch (e: RuntimeException) {
				log.error("Sync ({}) failed", which, e)
			}
		}
	}
}

/**
 * Runs requested dry runs in the background, outside any transaction like [SyncListener], and never applying: a lead
 * asking to see the plan mustn't change Slack or Google because those tools are live.
 */
@Component
class DryRunListener(private val engine: SyncEngine, @org.springframework.beans.factory.annotation.Value("\${portal.sync.on-request:true}") private val enabled: Boolean) {
	private val log = LoggerFactory.getLogger(javaClass)

	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	fun on(event: DryRunRequested) {
		if (!enabled) return
		engine.dryRun(event.trigger).forEach { log.info("Dry run {} ({}): {}", it.tool, event.trigger, it) }
	}
}

/**
 * After a crash (second review L5). A request that arrives during a run is merged into the next one and its outbox
 * entry completes at once, so if the app stops mid-run, the outbox replays only the run's own request. Such a run is
 * left "running"; at startup, holding the sync lock (so no live run is caught), those are marked failed and every
 * tool is synced once, which covers whatever was waiting.
 */
@Component
class SyncRecovery(private val dsl: org.jooq.DSLContext, private val locks: RunLocks, private val requests: SyncRequests) {
	private val log = LoggerFactory.getLogger(javaClass)

	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
	fun recover(): Int {
		val run = org.hack4impact.portal.db.tables.references.SYNC_RUN
		val interrupted = locks.exclusive(RunLocks.SYNC) {
			dsl.update(run).set(run.STATUS, "failed").set(run.ERROR, "The portal stopped during this run").set(run.FINISHED_AT, java.time.OffsetDateTime.now())
				.where(run.STATUS.eq("running")).execute()
		}
		if (interrupted > 0) {
			log.warn("{} sync runs were cut short when the portal stopped; syncing every tool once", interrupted)
			requests.request("recovery")
		}
		return interrupted
	}
}

/** The nightly sync (PRD: drift detection). Off unless PORTAL_SYNC_CRON is set, e.g. "0 0 3 * * *". */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SyncSchedule(private val requests: SyncRequests) {
	@Scheduled(cron = "\${portal.sync.cron:-}", zone = "America/New_York")
	fun nightly() = requests.request("schedule")
}

/**
 * One dry run, printed, then exit: `./gradlew bootRun -PenvFile=sandbox.env --args='--spring.profiles.active=sync-dry-run'`.
 * Runs directly rather than through the outbox, so the report can be printed.
 */
@Component
@ConditionalOnBooleanProperty("portal.sync.dry-run-once")
class SyncDryRunOnce(private val engine: SyncEngine, private val context: ConfigurableApplicationContext) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val reports = engine.dryRun("manual")
		val text = buildString {
			if (reports.isEmpty()) appendLine("No adapters enabled.")
			reports.forEach { r ->
				appendLine("== ${r.tool}: ${r.status}${r.error?.let { " ($it)" } ?: ""}")
				if (r.error == null) {
					appendLine("   add ${r.adds} · change ${r.changes} · remove ${r.removals} · drift ${r.drift} · unmatched accounts ${r.unmatchedAccounts} · missing resources ${r.missingResources} · create ${r.creates}")
					r.wouldPause?.let { appendLine("   a real run would pause: $it") }
					appendLine("   details: sync_change where run_id = ${r.runId}")
				}
			}
		}
		LoggerFactory.getLogger(javaClass).info("Dry run:\n{}", text)
		exitProcess(SpringApplication.exit(context, { if (reports.any { it.status == "failed" }) 1 else 0 }))
	}
}
