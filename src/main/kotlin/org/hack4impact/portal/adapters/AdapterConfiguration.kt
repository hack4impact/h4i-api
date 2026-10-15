package org.hack4impact.portal.adapters

import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.tools.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

/**
 * Credentials for other services. As environment variables (from the vault, wiki decision 30) the names drop the
 * dashes, e.g. `PORTAL_ADAPTERS_GOOGLE_ENABLED`. Keys can be given inline or as a file path.
 */
@ConfigurationProperties("portal.adapters")
data class AdapterProperties(val google: Google = Google(), val documenso: Documenso = Documenso()) {
	data class Google(
		val enabled: Boolean = false,
		val baseUrl: String = "https://admin.googleapis.com",
		val adminSubject: String = "",
		val serviceAccountKey: String = "",
		/** Create member accounts on approval and claim; domain-wide delegation must allow the user scope. */
		val createAccounts: Boolean = false,
		/** Org-unit path: accounts are only created in (and deleted from) org units under it. Empty = any (production). */
		val userScope: String = "",
	)

	/**
	 * Registration agreements (Q52): [baseUrl] is the instance (`https://sign.hack4impact.org`), [token] a team API
	 * token, [webhookSecret] the secret set on the team's webhook to `<portal>/api/webhooks/documenso`. [write]: also
	 * send agreements; otherwise the portal only reads their status.
	 */
	data class Documenso(
		val enabled: Boolean = false,
		val baseUrl: String = "",
		val token: String = "",
		val webhookSecret: String = "",
		val write: Boolean = false,
	)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdapterProperties::class)
class AdapterConfiguration {
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.create-accounts")
	fun googleAccounts(p: AdapterProperties): GoogleAccounts = with(p.google) {
		if (!enabled) error("portal.adapters.google.create-accounts needs portal.adapters.google.enabled")
		GoogleAccounts(
			baseUrl,
			GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject), GoogleServiceAccountToken.USER_SCOPES),
			orgUnitScope = userScope,
			// Accounts are created while a lead's approval request waits: a rate limit fails at once and becomes a national
			// task (approval still completes) rather than holding the request for minutes (review M11).
			http = HttpJson(Tool.GOOGLE),
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.documenso.enabled")
	fun documensoClient(p: AdapterProperties) = with(p.documenso) {
		org.hack4impact.portal.adapters.documenso.DocumensoClient(required("documenso.base-url", baseUrl).trimEnd('/'), required("documenso.token", token))
	}

	private fun required(name: String, value: String) = value.ifBlank { error("portal.adapters.$name must be set when the adapter is enabled") }

	/** A key given inline (JSON) or as a path to a file holding it. */
	private fun inlineOrFile(value: String): String {
		require(value.isNotBlank()) { "A key is required when the adapter is enabled" }
		val trimmed = value.trim()
		return if (trimmed.startsWith("-----") || trimmed.startsWith("{")) trimmed else File(trimmed).readText()
	}
}
