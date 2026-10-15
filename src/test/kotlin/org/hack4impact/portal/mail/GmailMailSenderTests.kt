package org.hack4impact.portal.mail

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.HttpJson
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmailMailSenderTests {
	@Test
	fun `messages are plain-text RFC 822 with an encoded subject, sent as the from address`() {
		val server = WireMockServer(options().dynamicPort()).apply { start() }
		try {
			server.stubFor(post(urlEqualTo("/gmail/v1/users/me/messages/send")).withHeader("Authorization", equalTo("Bearer ya29"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"m1"}""")))
			GmailMailSender("noreply@hack4impact.org", { "ya29" }, baseUrl = server.baseUrl()).send("ada@umd.edu", "Your code: 123456 ✓", "Hello")
			val raw = HttpJson.MAPPER.readTree(server.allServeEvents.single().request.bodyAsString).path("raw").asString()
			val message = String(Base64.getUrlDecoder().decode(raw))
			assertTrue("From: H4I Portal <noreply@hack4impact.org>\r\n" in message)
			assertTrue("To: ada@umd.edu\r\n" in message)
			val subject = Regex("Subject: =\\?UTF-8\\?B\\?(.+)\\?=").find(message)!!.groupValues[1]
			assertEquals("Your code: 123456 ✓", String(Base64.getDecoder().decode(subject)))
			assertTrue(message.endsWith("\r\n\r\nHello"))
		} finally {
			server.stop()
		}
	}
}
