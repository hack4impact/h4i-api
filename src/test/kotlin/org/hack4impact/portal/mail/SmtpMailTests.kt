package org.hack4impact.portal.mail

import org.hack4impact.portal.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mail.javamail.JavaMailSender
import kotlin.test.assertNotNull

/**
 * smtp mode (Mailpit in development and staging) starts: Spring Boot's JavaMailSender and the portal's sender used to
 * share the bean name `mailSender`, so setting `spring.mail.host` stopped the app at startup.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.mail.mode=smtp", "spring.mail.host=localhost", "spring.mail.port=1026"])
class SmtpMailTests(@Autowired private val sender: MailSender, @Autowired private val smtp: JavaMailSender) {
	@Test
	fun `the app starts in smtp mode with both senders`() {
		assertNotNull(sender)
		assertNotNull(smtp)
	}
}
