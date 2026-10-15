package org.hack4impact.portal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class StatusControllerTests(@Autowired private val mvc: MockMvc) {

	@Test
	fun `status reports service name`() {
		mvc.get("/api/status").andExpect {
			status { isOk() }
			jsonPath("$.service") { value("portal") }
		}
	}

	@Test
	fun `health endpoint is up`() {
		mvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
		}
	}
}
