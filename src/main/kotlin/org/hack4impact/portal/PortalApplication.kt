package org.hack4impact.portal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Scheduled jobs: claim deadlines, nightly housekeeping, and the member database's pull and writes. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class PortalApplication

fun main(args: Array<String>) {
	runApplication<PortalApplication>(*args)
}
