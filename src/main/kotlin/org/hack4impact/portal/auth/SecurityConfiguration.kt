package org.hack4impact.portal.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

@ConfigurationProperties("portal.auth")
data class AuthProperties(val allowedDomain: String = "hack4impact.org")

/**
 * Sign-in with Google, limited to one Workspace domain and its chapter subdomains (PRD: Security). The `hd` parameter
 * only hints Google's account picker; the real check is [DomainCheck] on the returned ID token. Applicant sessions for registration
 * and claims get their own filter chain under /api/apply in step 10.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfiguration {
	private val log = org.slf4j.LoggerFactory.getLogger(javaClass)


	@Bean
	fun portalSecurity(
		http: HttpSecurity,
		clients: ClientRegistrationRepository,
		users: OAuth2UserService<OidcUserRequest, OidcUser>,
		properties: AuthProperties,
		@Value("\${portal.frontend-url}") frontendUrl: String,
	): SecurityFilterChain {
		val api = PathPatternRequestMatcher.withDefaults().matcher("/api/**")
		val frontend = frontendUrl.trimEnd('/')
		http
			.authorizeHttpRequests {
				it.requestMatchers("/api/status", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
				// Documenso calling back: no sign-in, verified by its shared secret instead.
				it.requestMatchers("/api/webhooks/**").permitAll()
				it.requestMatchers("/api/**", "/v3/api-docs", "/v3/api-docs/**").authenticated()
				// Scalar's API reference page and its scripts; the spec it fetches stays behind sign-in (above).
				it.requestMatchers("/scalar", "/scalar/**").permitAll()
				it.anyRequest().permitAll() // there's no UI here (the frontend is a separate app), so the rest is 404s and /error
			}
			.oauth2Login {
				it.authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(workspaceHint(clients)) }
				it.userInfoEndpoint { info -> info.oidcUserService(users) }
				it.defaultSuccessUrl("$frontend/", true)
				it.failureUrl("$frontend/?signin=denied")
			}
			.logout { it.logoutSuccessUrl("$frontend/") }
			// The API answers 401 instead of redirecting to Google; the frontend starts the sign-in itself.
			.exceptionHandling { it.defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api) }
			// Cookie-based CSRF token the frontend sends back as a header (Spring Security's SPA mode).
			.csrf {
				it.spa()
				it.ignoringRequestMatchers("/api/webhooks/**")
			}
		return http.build()
	}

	@Bean
	fun portalUsers(properties: AuthProperties, viewers: Viewers): OAuth2UserService<OidcUserRequest, OidcUser> {
		val google = OidcUserService()
		return OAuth2UserService { request ->
			val user = google.loadUser(request)
			DomainCheck.rejection(user.claims, properties.allowedDomain)?.let { throw OAuth2AuthenticationException(OAuth2Error("domain_not_allowed", it, null)) }
			viewers.linkGoogleAccount(user)
			// One line per sign-in, so "not linked to a member" can be traced (the address, never tokens).
			val email = user.email
			if (email.isNullOrBlank()) log.warn("Sign-in without an email claim (claims: {})", user.claims.keys.sorted())
			else log.info("Sign-in: {} ({})", email, if (viewers.of(user).personId != null) "member" else "not linked to a member")
			user
		}
	}

	/**
	 * `hd=*`: Google's picker offers Workspace accounts only. Not `hd=<allowed domain>`: Google then takes the address's
	 * domain as fixed and a chapter account (chase@umd.hack4impact.org) can't be typed in, though [DomainCheck] allows it.
	 */
	private fun workspaceHint(clients: ClientRegistrationRepository) =
		DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization").apply {
			setAuthorizationRequestCustomizer { it.additionalParameters { params -> params["hd"] = "*" } }
		}
}

/**
 * The server-side domain rule for Google ID tokens: the allowed domain or one of its subdomains, e.g. chapter
 * domains like umd.hack4impact.org (only whoever controls the domain's DNS can add those to a Workspace).
 * The OAuth consent screen is Internal, so Google already limits sign-in to H4I's own Workspace; this is
 * the second layer.
 */
object DomainCheck {
	/** Why these claims may not sign in, or null if they may. */
	fun rejection(claims: Map<String, Any?>, allowedDomain: String): String? {
		val email = (claims["email"] as? String)?.lowercase()
		val allowed = allowedDomain.lowercase()
		return when {
			email == null -> "no email in the Google account"
			claims["email_verified"] != true -> "email not verified"
			// `hd` is only present for Google Workspace accounts; consumer Gmail accounts never have it.
			!within((claims["hd"] as? String)?.lowercase(), allowed) -> "not a $allowedDomain account"
			!within(email.substringAfterLast('@'), allowed) -> "not a $allowedDomain address"
			else -> null
		}
	}

	/** [domain] is [allowed] itself or a subdomain of it (a dot boundary, so "evilhack4impact.org" doesn't count). */
	private fun within(domain: String?, allowed: String) = domain != null && (domain == allowed || domain.endsWith(".$allowed"))
}
