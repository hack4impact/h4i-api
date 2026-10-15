package org.hack4impact.portal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The web app's TypeScript types are generated from web/openapi.json, so it must match the real API.
 * After changing an API: `./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec`, then `npm run gen:api` in web/.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTests(@Autowired private val mvc: MockMvc) {
	private val committed = Path.of("web/openapi.json")

	@Test
	fun `web openapi json matches the live API`() {
		val live = mvc.get("/v3/api-docs") { with(oidcLogin()) }.andReturn().response.contentAsString
		val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
		// Stable output: no server URL (it's the test's random host), keys sorted (the order of paths depends on how
		// the context started), pretty-printed, trailing newline.
		val tree = mapper.readTree(live).apply { (this as tools.jackson.databind.node.ObjectNode).remove("servers") }
		val pretty = mapper.writeValueAsString(sorted(mapper, tree)) + "\n"
		if (System.getProperty("updateApiSpec") == "true") Files.writeString(committed, pretty)
		assertEquals(
			mapper.writeValueAsString(sorted(mapper, mapper.readTree(Files.readString(committed)))) + "\n", pretty,
			"web/openapi.json is out of date. Run: ./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec, then npm run gen:api in web/",
		)
	}

	/** The tree with every object's keys in alphabetical order (arrays keep theirs). */
	private fun sorted(mapper: JsonMapper, node: tools.jackson.databind.JsonNode): tools.jackson.databind.JsonNode = when {
		node.isObject -> mapper.createObjectNode().also { out -> node.properties().sortedBy { it.key }.forEach { (k, v) -> out.set(k, sorted(mapper, v)) } }
		node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(sorted(mapper, it)) } }
		else -> node
	}
}
