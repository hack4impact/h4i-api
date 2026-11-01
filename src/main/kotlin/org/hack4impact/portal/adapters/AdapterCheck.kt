package org.hack4impact.portal.adapters

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * Connects to every enabled tool and reports what it can read: the step 4 finish line ("each adapter lists real
 * members in its sandbox"). Read-only. Prints counts and a few sample logins and names.
 */
object AdapterCheck {
	data class Result(val ok: Boolean, val report: String)

	fun run(adapters: List<ReadAdapter>, samples: Int = 5, resourcesToOpen: Int = 3): Result {
		if (adapters.isEmpty()) return Result(false, "No adapters enabled. Set PORTAL_ADAPTERS_<TOOL>_ENABLED=true and its credentials.")
		var ok = true
		val report = buildString {
			for (adapter in adapters.sortedBy { it.tool }) {
				appendLine("== ${adapter.tool}")
				try {
					val accounts = adapter.accounts()
					appendLine("accounts: ${accounts.size} (${accounts.groupingBy { it.state }.eachCount().entries.joinToString { "${it.key.name.lowercase()} ${it.value}" }})")
					accounts.take(samples).forEach { appendLine("  ${it.login ?: it.email ?: it.externalId} [${it.state.name.lowercase()}]") }
					val resources = adapter.resources()
					appendLine("resources: ${resources.size}")
					for (resource in resources.take(resourcesToOpen)) {
						appendLine("  ${resource.name}${if (resource.archived) " (archived)" else ""}: ${adapter.members(resource.externalId).size} members")
					}
				} catch (e: AdapterException) {
					ok = false
					appendLine("FAILED: ${e.message}${if (e.retryable) " (temporary, try again)" else ""}")
				}
			}
		}
		return Result(ok, report)
	}
}

/** `./gradlew bootRun -PenvFile=<.env> --args='--spring.profiles.active=adapter-check'` */
@Component
@ConditionalOnBooleanProperty("portal.adapters.check")
class AdapterCheckRunner(private val adapters: List<ReadAdapter>, private val context: ConfigurableApplicationContext) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val result = AdapterCheck.run(adapters)
		LoggerFactory.getLogger(javaClass).info("Adapter check:\n{}", result.report)
		exitProcess(SpringApplication.exit(context, { if (result.ok) 0 else 1 }))
	}
}
