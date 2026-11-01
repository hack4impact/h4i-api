package org.hack4impact.portal.registration

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * The GitHub OAuth app applicants sign in with (PRD: link GitHub through OAuth; store the numeric user ID).
 * No scopes are requested: the portal only reads who they are. The callback is `<base-url>/api/apply/github/callback`.
 */
@ConfigurationProperties("portal.github-oauth")
data class GitHubOAuthProperties(
	val clientId: String = "",
	val clientSecret: String = "",
	val webUrl: String = "https://github.com",
	val apiUrl: String = "https://api.github.com",
)

data class GitHubUser(val id: String, val login: String)

@Component
class GitHubOAuth(private val properties: GitHubOAuthProperties, private val http: HttpJson = HttpJson(Tool.GITHUB)) {
	val configured get() = properties.clientId.isNotBlank() && properties.clientSecret.isNotBlank()

	fun authorizeUrl(state: String, callback: String) =
		"${properties.webUrl}/login/oauth/authorize?client_id=${HttpJson.encode(properties.clientId)}&redirect_uri=${HttpJson.encode(callback)}" +
			"&state=${HttpJson.encode(state)}&allow_signup=true"

	/** Exchanges the callback's code for the GitHub user it belongs to. */
	fun user(code: String, callback: String): GitHubUser {
		val token = http.postForm(
			"${properties.webUrl}/login/oauth/access_token",
			mapOf("client_id" to properties.clientId, "client_secret" to properties.clientSecret, "code" to code, "redirect_uri" to callback),
		).body.text("access_token") ?: error("GitHub didn't return a token")
		val user = http.get("${properties.apiUrl}/user", mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json")).body
		// Never "0" or a missing login stored as someone's GitHub account (review L7).
		val id = user.path("id").takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 } ?: error("GitHub's reply had no user ID")
		val login = user.text("login") ?: error("GitHub's reply had no login")
		return GitHubUser(id.toString(), login)
	}
}
