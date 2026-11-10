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

	@Volatile
	private var failed = false

	/** Whether a run failed outright since this was last asked (and forgets it). */
	fun takeFailed(): Boolean = failed.also { failed = false }

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
				failed = true // its request is spent: the retry pass asks again (2026-11-09 review H2)
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

	// Requests that arrive while a dry run is going are merged into one more (2026-11-09 review M4): each reads every
	// tool and holds the sync lock, so a lead clicking repeatedly mustn't queue a run per click.
	private var running = false
	private var again = false

	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	fun on(event: DryRunRequested) {
		if (!enabled) return
		synchronized(this) {
			if (running) {
				again = true
				return
			}
			running = true
		}
		while (true) {
			try {
				engine.dryRun(event.trigger).forEach { log.info("Dry run {} ({}): {}", it.tool, event.trigger, it) }
			} catch (e: RuntimeException) {
				log.error("Dry run failed", e)
			}
			synchronized(this) {
				if (!again) {
					running = false
					return
				}
				again = false
			}
		}
	}
}

/**
 * Asks for a sync of tools with unfinished work (2026-11-09 review H2): without it a run happens only when a change
 * asks for one, so a failed change's backoff, a created resource's retry, an accepted invitation waiting to be confirmed
 * and a run that failed outright would all wait for an unrelated change. Only tools that are on and out of dry run:
 *  - a failed change or a resource creation whose retry is due: at the next pass;
 *  - someone waited for (an invitation not accepted yet, no account in the tool yet): when the tool's last run is older
 *    than [waitingEvery], since only reading the tool again shows whether they've acted.
 * `portal.sync.retry-every` (default 15 minutes) sets the pass; the one-shot commands don't schedule it.
 */
@Component
class SyncRetries(
	private val dsl: org.jooq.DSLContext,
	private val requests: SyncRequests,
	private val listener: SyncListener,
	@org.springframework.beans.factory.annotation.Value("\${portal.sync.waiting-every:PT1H}") private val waitingEvery: java.time.Duration,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${portal.sync.retry-every:PT15M}", initialDelayString = "PT5M")
	fun pass() {
		if (listener.takeFailed()) {
			log.info("Retry pass: the last sync failed outright; syncing every tool")
			requests.request("schedule")
			return
		}
		val tools = due(java.time.OffsetDateTime.now())
		if (tools.isNotEmpty()) {
			log.info("Retry pass: syncing {} for unfinished changes", tools)
			requests.request("schedule", tools)
		}
	}

	/** The tools with work a run could finish now. */
	fun due(now: java.time.OffsetDateTime): Set<Tool> {
		val record = org.hack4impact.portal.db.tables.references.SYNC_RECORD
		val resource = org.hack4impact.portal.db.tables.references.RESOURCE
		val setting = org.hack4impact.portal.db.tables.references.TOOL_SETTING
		val run = org.hack4impact.portal.db.tables.references.SYNC_RUN
		val live = dsl.select(setting.TOOL).from(setting).where(setting.ENABLED.isTrue, setting.DRY_RUN.isFalse).fetch().mapNotNull { it.value1() }.toSet()
		if (live.isEmpty()) return emptySet()
		val failed = dsl.selectDistinct(resource.TOOL).from(record).join(resource).on(resource.ID.eq(record.RESOURCE_ID))
			.where(record.STATUS.eq("failed"), record.NEXT_RETRY_AT.le(now), resource.ARCHIVED_AT.isNull).fetch().mapNotNull { it.value1() }
		val creating = dsl.selectDistinct(resource.TOOL).from(resource)
			.where(resource.EXTERNAL_ID.isNull, resource.ARCHIVED_AT.isNull, resource.CREATE_RETRY_AT.le(now)).fetch().mapNotNull { it.value1() }
		val lastRun = dsl.select(run.TOOL, org.jooq.impl.DSL.max(run.STARTED_AT)).from(run).where(run.MODE.eq("apply")).groupBy(run.TOOL).fetch()
			.associate { it.value1() to it.value2() }
		val waiting = dsl.selectDistinct(resource.TOOL).from(record).join(resource).on(resource.ID.eq(record.RESOURCE_ID))
			.where(record.STATUS.eq("waiting_on_member"), resource.ARCHIVED_AT.isNull).fetch().mapNotNull { it.value1() }
			.filter { tool -> lastRun[tool]?.isAfter(now.minus(waitingEvery)) != true }
		return (failed + creating + waiting).filter { it in live }.mapNotNull { Tool.fromKey(it) }.toSet()
	}
}

/**
 * After a crash (second review L5). A request that arrives during a run is merged into the next one and its outbox
 * entry completes at once, so if the app stops mid-run, the outbox replays only the run's own request. Such a run is
 * left "running"; at startup, holding the sync lock (so no live run is caught), those are marked failed and every
 * tool is synced once, which covers whatever was waiting.
 */
@Component
class SyncRecovery(
	private val dsl: org.jooq.DSLContext,
	private val locks: RunLocks,
	private val requests: SyncRequests,
	@org.springframework.beans.factory.annotation.Value("\${portal.sync.recovery:true}") private val enabled: Boolean,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** At startup, unless turned off (the one-shot profiles: a preflight mustn't mark a live app's run failed or start a sync). */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
	fun onStartup() {
		if (enabled) recover()
	}

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

/** The nightly sync of every tool (PRD: drift detection): 3 a.m. Eastern unless PORTAL_SYNC_CRON says otherwise ("-" turns it off; decision 181). */
@Configuration(proxyBeanMethods = false)
class SyncSchedule(private val requests: SyncRequests) {
	@Scheduled(cron = "\${portal.sync.cron:0 0 3 * * *}", zone = "America/New_York")
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
