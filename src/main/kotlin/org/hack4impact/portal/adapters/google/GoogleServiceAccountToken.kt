package org.hack4impact.portal.adapters.google

import com.google.auth.oauth2.ServiceAccountCredentials

/**
 * Access tokens for a service account with domain-wide delegation, acting as [subject]: the limited provisioning
 * admin to create member accounts ([USER_SCOPES]) and, since stage 2, to read groups ([READ_SCOPES]) and change them
 * ([WRITE_SCOPES]); or the noreply@ mailbox to send email. One class for both: the full portal kept a copy inside its
 * GoogleReadAdapter.kt (wiki: staged-rollout-stage-2, 3-Google).
 */
class GoogleServiceAccountToken(keyJson: String, subject: String, scopes: List<String>) : () -> String {
	private val credentials = ServiceAccountCredentials.fromStream(keyJson.byteInputStream()).createScoped(scopes).createDelegated(subject)

	@Synchronized
	override fun invoke(): String {
		credentials.refreshIfExpired()
		return checkNotNull(credentials.accessToken) { "Google returned no access token" }.tokenValue
	}

	companion object {
		/** Stage 2: read users, groups and their members (the sync's Google adapter). */
		val READ_SCOPES = listOf(
			"https://www.googleapis.com/auth/admin.directory.user.readonly",
			"https://www.googleapis.com/auth/admin.directory.group.readonly",
			"https://www.googleapis.com/auth/admin.directory.group.member.readonly",
		)

		/** Stage 2: create groups and manage their members. Domain-wide delegation must authorize it too. */
		val WRITE_SCOPES = listOf("https://www.googleapis.com/auth/admin.directory.group")

		/** Create (and, in the sandbox, delete) member accounts. */
		val USER_SCOPES = listOf("https://www.googleapis.com/auth/admin.directory.user")
	}
}
