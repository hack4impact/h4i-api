package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** Two fake tools whose actual state each test sets (reads and writes). */
@TestConfiguration(proxyBeanMethods = false)
class FakeTools {
	@Bean
	fun slack() = InMemoryReadAdapter(Tool.SLACK)

	@Bean
	fun github() = InMemoryReadAdapter(Tool.GITHUB)
}
