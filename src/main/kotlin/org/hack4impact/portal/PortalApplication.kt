package org.hack4impact.portal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
class PortalApplication

/**
 * Scheduled jobs: claim deadlines, nightly housekeeping, the member database's pull and writes, the agreement pass and
 * the nightly sync. Off in the one-shot profiles (`portal.scheduling.enabled=false`, the `one-shot` profile), so a
 * preflight or an import never starts them (package 3 review R5).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = ["portal.scheduling.enabled"], matchIfMissing = true)
@EnableScheduling
class Scheduling

fun main(args: Array<String>) {
	runApplication<PortalApplication>(*args)
}
