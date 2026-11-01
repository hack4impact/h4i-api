package org.hack4impact.portal.mail

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.db.tables.references.EMAIL_MESSAGE
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.util.Base64
import java.util.UUID

/** Sends one plain-text email. */
fun interface MailSender {
	fun send(to: String, subject: String, text: String)
}

/**
 * [mode]: `gmail` (production: the Gmail API as [from], through the Google service account with the gmail.send
 * scope, decision 109), `smtp` (Mailpit in development and staging, `spring.mail.*`), or `log` (tests and quick
 * local runs). [logBodies] prints whole messages in log mode, codes and links included: local development only.
 */
@ConfigurationProperties("portal.mail")
data class MailProperties(val mode: String = "log", val from: String = "noreply@hack4impact.org", val logBodies: Boolean = false)

@Configuration(proxyBeanMethods = false)
class MailConfiguration {
	/** Not named `mailSender`: Spring Boot's own JavaMailSender has that name once `spring.mail.host` is set (smtp mode). */
	@Bean
	fun portalMailSender(properties: MailProperties, adapters: AdapterProperties, smtp: ObjectProvider<JavaMailSender>): MailSender {
		val log = LoggerFactory.getLogger(MailSender::class.java)
		return when (properties.mode) {
			"gmail" -> {
				val key = adapters.google.serviceAccountKey.let { if (it.trimStart().startsWith("{")) it else File(it).readText() }
				require(key.isNotBlank()) { "portal.mail.mode=gmail needs portal.adapters.google.service-account-key" }
				GmailMailSender(properties.from, GoogleServiceAccountToken(key, properties.from, listOf("https://www.googleapis.com/auth/gmail.send")))
			}
			"smtp" -> {
				val sender = smtp.ifAvailable ?: error("portal.mail.mode=smtp needs spring.mail.host")
				MailSender { to, subject, text ->
					sender.send(SimpleMailMessage().apply { setFrom(properties.from); setTo(to); setSubject(subject); setText(text) })
				}
			}
			"log" -> MailSender { to, subject, text ->
				if (properties.logBodies) log.info("Email to {}: {}\n{}", to, subject, text) else log.info("Email to {}: {} (body not logged)", to, subject)
			}
			else -> error("portal.mail.mode must be gmail, smtp or log")
		}
	}
}

/** Sends through the Gmail API as [from] (a real hack4impact.org mailbox), impersonated by the service account. */
class GmailMailSender(
	private val from: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GOOGLE),
	private val baseUrl: String = "https://gmail.googleapis.com",
) : MailSender {
	override fun send(to: String, subject: String, text: String) {
		val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.toByteArray()) + "?="
		val message = "From: H4I Portal <$from>\r\nTo: $to\r\nSubject: $encodedSubject\r\nMIME-Version: 1.0\r\n" +
			"Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n$text"
		http.postJson(
			"$baseUrl/gmail/v1/users/me/messages/send",
			mapOf("raw" to Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray())),
			mapOf("Authorization" to "Bearer ${token()}"),
		)
	}
}

/**
 * Sends an email and records that it was sent (never the body), so failures are visible and auditable. Inside a
 * transaction it waits for the commit: nothing goes out for a change that rolled back, and no lock is held while
 * it sends (code review M8).
 */
@Component
class Mailer(private val sender: MailSender, private val dsl: DSLContext, private val transactions: PlatformTransactionManager) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Returns whether it was sent; true when it waits for the transaction to commit. */
	fun send(email: Email, to: String, registration: UUID? = null, person: UUID? = null): Boolean {
		// Only a real transaction is waited for: a NOT_SUPPORTED listener has synchronization but nothing to commit.
		if (!TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isActualTransactionActive()) {
			return deliver(email, to, registration, person)
		}
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() {
				deliver(email, to, registration, person)
			}
		})
		return true
	}

	private fun deliver(email: Email, to: String, registration: UUID?, person: UUID?): Boolean {
		val error = try {
			sender.send(to, email.subject, email.text); null
		} catch (e: Exception) {
			log.warn("Email {} to {} failed: {}", email.template, to, e.message); e.message ?: e.javaClass.simpleName
		}
		// A transaction of its own: after a commit, the finished one can't take more writes.
		TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }.executeWithoutResult {
			dsl.insertInto(EMAIL_MESSAGE).set(EMAIL_MESSAGE.TO_ADDRESS, to).set(EMAIL_MESSAGE.TEMPLATE, email.template).set(EMAIL_MESSAGE.SUBJECT, email.subject)
				.set(EMAIL_MESSAGE.STATUS, if (error == null) "sent" else "failed").set(EMAIL_MESSAGE.ERROR, error?.take(500))
				.set(EMAIL_MESSAGE.REGISTRATION_ID, registration).set(EMAIL_MESSAGE.PERSON_ID, person)
				.execute()
		}
		return error == null
	}
}

data class Email(val template: String, val subject: String, val text: String)

/** The portal's emails, in plain text (PRD: emails for codes, claim links, approval, decline and account setup). */
object Emails {
	fun code(chapter: String, code: String) = Email(
		"email_code", "Your Hack4Impact code: $code",
		"Your code to apply to $chapter is $code\n\nIt works for 10 minutes. If you didn't ask for it, you can ignore this email.",
	)

	fun claim(name: String, link: String, days: Int) = Email(
		"claim_link", "Claim your Hack4Impact account",
		"Hi $name,\n\nHack4Impact has a new member portal. Claim your record to keep your access to Slack, GitHub and the rest:\n\n$link\n\n" +
			"The link works once, for $days days. If you're no longer involved, open it anyway and say so: you'll keep your account as an alumnus.",
	)

	fun rosterInvite(name: String, chapter: String, link: String) = Email(
		"roster_invite", "You're in: finish joining $chapter",
		"Hi $name,\n\n$chapter added you to its member roster. Finish joining here (it takes a few minutes, and you won't need to be approved again):\n\n$link",
	)

	fun approved(name: String, chapter: String, account: NewAccount?, signIn: String) = Email(
		"approved", "Welcome to $chapter",
		buildString {
			append("Hi $name,\n\nYou've been approved as a member of $chapter.\n\n")
			if (account != null) {
				append("Your Hack4Impact Google account:\n  Address: ${account.email}\n  Temporary password: ${account.temporaryPassword}\n")
				append("Sign in at https://accounts.google.com within a few days and choose your own password.\n\n")
			}
			append("Then join Slack with that address (it's on the approved-domain list), and sign in to the portal: $signIn\n")
			append("Your channels, GitHub team and other tools follow automatically within a day.")
		},
	)

	fun claimed(name: String, account: NewAccount?, signIn: String) = Email(
		"claimed", "Your Hack4Impact account is claimed",
		buildString {
			append("Hi $name,\n\nThanks for claiming your record.\n\n")
			if (account != null) append("Your new Hack4Impact Google account:\n  Address: ${account.email}\n  Temporary password: ${account.temporaryPassword}\nSign in at https://accounts.google.com and choose your own password.\n\n")
			append("Portal: $signIn")
		},
	)

	/** Stage 2 (decision 131, without drafts): after a project change, each member whose projects or access changed gets one summary. */
	fun accessSummary(name: String, chapter: String, joins: List<String>, leaves: List<String>, gains: List<String>, loses: List<String>, afterAgreement: List<String>, link: String, agreementReady: Boolean = true) = Email(
		"access_summary", "Your $chapter projects and access",
		buildString {
			append("Hi $name,\n\nYour $chapter projects or access just changed:\n")
			if (joins.isNotEmpty()) append("\nYou're on: ${joins.joinToString(", ")}\n")
			if (leaves.isNotEmpty()) append("\nYou're no longer on: ${leaves.joinToString(", ")}\n")
			if (gains.isNotEmpty()) append("\nYou now get:\n${gains.joinToString("\n") { "  - $it" }}\n")
			if (afterAgreement.isNotEmpty()) {
				// Package 6 review R1: no promise of an email that won't come.
				append(if (agreementReady) "\nOnce you sign the project agreement (Documenso emails it to you):\n" else "\nOnce national sets up the project agreement and you sign it:\n")
				append("${afterAgreement.joinToString("\n") { "  - $it" }}\n")
			}
			if (loses.isNotEmpty()) append("\nYou no longer get:\n${loses.joinToString("\n") { "  - $it" }}\n")
			append("\nThe tools follow shortly. The project: $link")
		},
	)

	/** Stage 2: a project agreement still unsigned after a week, to the project's leads (else the chapter's). */
	fun agreementOverdue(lead: String, person: String, project: String, days: Long) = Email(
		"agreement_overdue", "$person hasn't signed the $project agreement",
		"Hi $lead,\n\n$person was sent the project agreement for $project $days days ago and hasn't signed it yet. " +
			"Documenso has reminded them; until they sign, they won't get the project's GitHub team or vault collection.\n\n" +
			"You may want to check in with them.",
	)

	/** Stage 2: a project agreement declined, with the reason given. It isn't sent again unless they're removed and added back. */
	fun agreementDeclined(lead: String, person: String, project: String, reason: String?) = Email(
		"agreement_declined", "$person declined the $project agreement",
		"Hi $lead,\n\n$person declined the project agreement for $project" + (reason?.let { ":\n\n  $it\n\n" } ?: ".\n\n") +
			"They won't get the project's GitHub team or vault collection. Talk with them, or remove them from the project.",
	)

	fun declined(name: String, chapter: String, reason: String) = Email(
		"declined", "Your application to $chapter",
		"Hi $name,\n\n$chapter didn't approve your application this time. The chapter's reason:\n\n$reason\n\nQuestions go to the chapter's leads.",
	)
}

/** A Google account created for a new member. */
data class NewAccount(val userId: String, val email: String, val temporaryPassword: String)
