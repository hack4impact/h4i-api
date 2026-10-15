package org.hack4impact.portal.adapters.google

import com.google.auth.oauth2.ServiceAccountCredentials

/**
 * Access tokens for a service account with domain-wide delegation, acting as [subject]: the limited provisioning
 * admin to create member accounts ([USER_SCOPES]), or the noreply@ mailbox to send email.
 */
class GoogleServiceAccountToken(keyJson: String, subject: String, scopes: List<String>) : () -> String {
	private val credentials = ServiceAccountCredentials.fromStream(keyJson.byteInputStream()).createScoped(scopes).createDelegated(subject)

	@Synchronized
	override fun invoke(): String {
		credentials.refreshIfExpired()
		return checkNotNull(credentials.accessToken) { "Google returned no access token" }.tokenValue
	}

	companion object {
		/** Create (and, in the sandbox, delete) member accounts. */
		val USER_SCOPES = listOf("https://www.googleapis.com/auth/admin.directory.user")
	}
}
