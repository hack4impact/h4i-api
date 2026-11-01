package org.hack4impact.portal.adapters

import org.hack4impact.portal.adapters.github.GitHubAppToken
import org.hack4impact.portal.adapters.github.GitHubReadAdapter
import org.hack4impact.portal.adapters.github.GitHubWriteAdapter
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.hack4impact.portal.adapters.google.GoogleReadAdapter
import org.hack4impact.portal.adapters.google.GoogleWriteAdapter
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.adapters.slack.SlackReadAdapter
import org.hack4impact.portal.adapters.slack.SlackWriteAdapter
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenApiKeyToken
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenReadAdapter
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenWriteAdapter
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Credentials for other services. As environment variables (from the vault, wiki decision 30) the names drop the
 * dashes, e.g. `PORTAL_ADAPTERS_GOOGLE_ENABLED`. Keys can be given inline or as a file path.
 */
@ConfigurationProperties("portal.adapters")
data class AdapterProperties(
	val github: GitHub = GitHub(),
	val slack: Slack = Slack(),
	val google: Google = Google(),
	val vaultwarden: Vaultwarden = Vaultwarden(),
	val documenso: Documenso = Documenso(),
) {
	/** The GitHub App on the national org (stage 2: teams and org invites). */
	data class GitHub(
		val enabled: Boolean = false,
		val baseUrl: String = "https://api.github.com",
		/** The national organization (wiki decision 78). */
		val org: String = "",
		val appId: String = "",
		val installationId: String = "",
		val privateKey: String = "",
		/** Also change GitHub; the App then needs Members: Read and write. */
		val write: Boolean = false,
	)

	/**
	 * The H4I workspace's Slack app (stage 2, decision 79): [botToken] its bot token. [write]: also change Slack. Off
	 * unless set, so a read-only env file can never change a tool.
	 */
	data class Slack(val enabled: Boolean = false, val baseUrl: String = "https://slack.com/api", val botToken: String = "", val write: Boolean = false)

	/**
	 * Google Workspace through one service account with domain-wide delegation, acting as [adminSubject]. [enabled]:
	 * read (since stage 2, also the sync's groups). [write]: also change groups (stage 2); delegation must then allow the
	 * group scope. [createAccounts]: create member accounts (stage 1).
	 */
	data class Google(
		val enabled: Boolean = false,
		val baseUrl: String = "https://admin.googleapis.com",
		val adminSubject: String = "",
		val serviceAccountKey: String = "",
		/** Stage 2: the Workspace customer, for listing users and groups. */
		val customer: String = "my_customer",
		/** Stage 2: only users in this org unit are listed (a sandbox setting); empty = everyone. */
		val orgUnitPath: String = "",
		/** Stage 2, regex: only matching groups (address or name) are read or written (decision 61). Empty = every group (production). */
		val groupScope: String = "",
		/** Stage 2: also change Google groups. */
		val write: Boolean = false,
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

	/** `vault.hack4impact.org` (stage 2: project collections, as the service admin with a personal API key). */
	data class Vaultwarden(
		val enabled: Boolean = false,
		val baseUrl: String = "",
		val organizationId: String = "",
		val clientId: String = "",
		val clientSecret: String = "",
		val deviceId: String = "h4i-portal",
		/** Local testing only: a CA certificate (PEM path) to trust instead of the JDK's. */
		val trustedCertificate: String = "",
		/** Also change Vaultwarden: needs the service admin's [masterPassword] to unlock the organization key. */
		val write: Boolean = false,
		val masterPassword: String = "",
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

	/** Stage 2 (3-Slack): channels, members and users, read with a bot token. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.slack.enabled")
	fun slackReadAdapter(p: AdapterProperties): ReadAdapter = with(p.slack) {
		val token = required("slack.bot-token", botToken)
		SlackReadAdapter(baseUrl, { token }, http = patient(Tool.SLACK))
	}

	/** Writes need the tool enabled and its own switch (`…_WRITE=true`); the tool's dry-run setting still applies on top. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.slack.write")
	fun slackWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.slack) {
		if (!enabled) error("portal.adapters.slack.write needs portal.adapters.slack.enabled")
		val token = required("slack.bot-token", botToken)
		SlackWriteAdapter(baseUrl, { token }, http = patient(Tool.SLACK))
	}

	/** Stage 2 (3-Google): users, groups and members, read with the read scopes. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.enabled")
	fun googleReadAdapter(p: AdapterProperties): ReadAdapter = with(p.google) {
		GoogleReadAdapter(
			baseUrl, customer, orgUnitPath,
			GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject), GoogleServiceAccountToken.READ_SCOPES),
			groupScope = groupScope.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
			http = patient(Tool.GOOGLE),
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.write")
	fun googleWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.google) {
		if (!enabled) error("portal.adapters.google.write needs portal.adapters.google.enabled")
		GoogleWriteAdapter(
			baseUrl,
			GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject), GoogleServiceAccountToken.WRITE_SCOPES),
			groupScope = groupScope.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
			http = patient(Tool.GOOGLE),
		)
	}

	/** Stage 2 (3-GitHub): the national org's members, pending invitations, teams and team members, through the GitHub App. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.github.enabled")
	fun gitHubReadAdapter(p: AdapterProperties): ReadAdapter = with(p.github) {
		GitHubReadAdapter(baseUrl, githubOrg(required("github.org", org)), gitHubToken(this), http = patient(Tool.GITHUB))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.github.write")
	fun gitHubWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.github) {
		if (!enabled) error("portal.adapters.github.write needs portal.adapters.github.enabled")
		GitHubWriteAdapter(baseUrl, githubOrg(required("github.org", org)), gitHubToken(this), http = patient(Tool.GITHUB))
	}

	private fun gitHubToken(g: AdapterProperties.GitHub) =
		GitHubAppToken(g.baseUrl, required("github.app-id", g.appId), required("github.installation-id", g.installationId), inlineOrFile(g.privateKey))

	/** Stage 2 (3-Vaultwarden): the organization's members and collections, read with the service admin's API key. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.enabled")
	fun vaultwardenReadAdapter(p: AdapterProperties): ReadAdapter = with(p.vaultwarden) {
		val url = required("vaultwarden.base-url", baseUrl).trimEnd('/')
		val http = vaultwardenHttp(this)
		VaultwardenReadAdapter(url, required("vaultwarden.organization-id", organizationId), vaultwardenToken(this, url, http), http)
	}

	/** Also needs the service admin's master password, to unlock the organization key (collection names, confirming members). */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.write")
	fun vaultwardenWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.vaultwarden) {
		if (!enabled) error("portal.adapters.vaultwarden.write needs portal.adapters.vaultwarden.enabled")
		val url = required("vaultwarden.base-url", baseUrl).trimEnd('/')
		val http = vaultwardenHttp(this)
		VaultwardenWriteAdapter(
			url, required("vaultwarden.organization-id", organizationId), vaultwardenToken(this, url, http),
			required("vaultwarden.master-password", masterPassword), http,
		)
	}

	private fun vaultwardenToken(v: AdapterProperties.Vaultwarden, url: String, http: HttpJson) =
		VaultwardenApiKeyToken(url, required("vaultwarden.client-id", v.clientId), required("vaultwarden.client-secret", v.clientSecret), v.deviceId, http)

	/** The tools' adapters wait out rate limits: syncs run in the background and would otherwise fail on large workspaces. */
	private fun patient(tool: Tool) = HttpJson(tool, rateLimits = RateLimitPolicy.PATIENT)

	private fun required(name: String, value: String) = value.ifBlank { error("portal.adapters.$name must be set when the adapter is enabled") }

	/** A key given inline (PEM or JSON) or as a path to a file holding it. */
	private fun inlineOrFile(value: String): String {
		require(value.isNotBlank()) { "A key is required when the adapter is enabled" }
		val trimmed = value.trim()
		return if (trimmed.startsWith("-----") || trimmed.startsWith("{")) trimmed else File(trimmed).readText()
	}
}

/** The organization's login, also when given as its URL or with a trailing slash ("hack4impact/", "https://github.com/hack4impact"). */
internal fun githubOrg(value: String): String =
	value.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/").trim('/').substringBefore('/')

/** Vaultwarden's client: [AdapterProperties.Vaultwarden.trustedCertificate] (local testing) replaces the JDK's trusted CAs. */
internal fun vaultwardenHttp(v: AdapterProperties.Vaultwarden): HttpJson =
	if (v.trustedCertificate.isBlank()) HttpJson(Tool.VAULTWARDEN, rateLimits = RateLimitPolicy.PATIENT)
	else HttpJson(Tool.VAULTWARDEN, client = trusting(v.trustedCertificate), rateLimits = RateLimitPolicy.PATIENT)

private fun trusting(pemPath: String): HttpClient {
	val certificate = File(pemPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
	val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
		load(null)
		setCertificateEntry("trusted", certificate)
	}
	val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
	val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
	return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).sslContext(tls).build()
}
