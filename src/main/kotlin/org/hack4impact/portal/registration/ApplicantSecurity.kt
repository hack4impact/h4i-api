package org.hack4impact.portal.registration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.hack4impact.portal.db.tables.references.APPLICANT_SESSION
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Random tokens for links and sessions, stored only as SHA-256 hashes. */
object Tokens {
	private val random = SecureRandom()

	fun new(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

	fun code(): String = "%06d".format(random.nextInt(1_000_000))

	fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

	fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** In-memory sliding-window limits per key (one app instance). PRD: applicant endpoints are rate limited. */
@Component
class RateLimiter {
	private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()
	private val calls = java.util.concurrent.atomic.AtomicLong()

	companion object {
		/** Longer than any window asked for: a key with no hit this recent is forgotten (review L2). */
		private val FORGET_AFTER: Duration = Duration.ofHours(2)
	}

	/** Records a hit; false when [key] already had [limit] hits within [window]. */
	fun allow(key: String, limit: Int, window: Duration): Boolean {
		val now = Instant.now()
		if (calls.incrementAndGet() % 1000 == 0L) forget(now)
		val queue = hits.computeIfAbsent(key) { ArrayDeque() }
		synchronized(queue) {
			while (queue.isNotEmpty() && queue.first().isBefore(now.minus(window))) queue.removeFirst()
			if (queue.size >= limit) return false
			queue.addLast(now)
			return true
		}
	}

	fun reset() = hits.clear()

	/** Drops keys (an email, an IP) with no recent hit, so the map doesn't grow until a restart. */
	private fun forget(now: Instant) {
		hits.entries.removeIf { (_, queue) -> synchronized(queue) { queue.isEmpty() || queue.last().isBefore(now.minus(FORGET_AFTER)) } }
	}
}

/** Who an applicant session belongs to. */
data class Applicant(val sessionId: UUID, val registrationId: UUID)

class ApplicantAuthentication(val applicant: Applicant) : AbstractAuthenticationToken(AuthorityUtils.createAuthorityList("ROLE_APPLICANT")) {
	init { isAuthenticated = true }
	override fun getCredentials() = null
	override fun getPrincipal() = applicant
}

/** Applicant sessions (PRD: tied to one registration, at most 7 days, an httpOnly cookie). */
@Component
class ApplicantSessions(private val dsl: DSLContext, @Value("\${portal.base-url}") private val baseUrl: String) {
	companion object {
		const val COOKIE = "h4i_applicant"
		val LIFETIME: Duration = Duration.ofDays(7)
	}

	/** Starts a session for [registration] and sets its cookie. */
	fun start(registration: UUID, response: HttpServletResponse) {
		val token = Tokens.new()
		dsl.insertInto(APPLICANT_SESSION).set(APPLICANT_SESSION.REGISTRATION_ID, registration).set(APPLICANT_SESSION.TOKEN_HASH, Tokens.hash(token))
			.set(APPLICANT_SESSION.EXPIRES_AT, OffsetDateTime.now().plus(LIFETIME)).execute()
		response.addCookie(cookie(token, LIFETIME.seconds.toInt()))
	}

	fun find(token: String): Applicant? =
		dsl.select(APPLICANT_SESSION.ID, APPLICANT_SESSION.REGISTRATION_ID).from(APPLICANT_SESSION)
			.where(APPLICANT_SESSION.TOKEN_HASH.eq(Tokens.hash(token)), APPLICANT_SESSION.REVOKED_AT.isNull, APPLICANT_SESSION.EXPIRES_AT.gt(OffsetDateTime.now()))
			.fetchOne { Applicant(it.value1()!!, it.value2()!!) }

	/** Ends every session of [registration] (PRD: revoked after approval or claim). */
	fun revoke(registration: UUID) {
		dsl.update(APPLICANT_SESSION).set(APPLICANT_SESSION.REVOKED_AT, OffsetDateTime.now())
			.where(APPLICANT_SESSION.REGISTRATION_ID.eq(registration), APPLICANT_SESSION.REVOKED_AT.isNull).execute()
	}

	fun clear(response: HttpServletResponse) = response.addCookie(cookie("", 0))

	private fun cookie(value: String, maxAge: Int) = Cookie(COOKIE, value).apply {
		isHttpOnly = true
		secure = baseUrl.startsWith("https://")
		path = "/api/apply"
		this.maxAge = maxAge
		setAttribute("SameSite", "Lax") // sent on GitHub's redirect back (a top-level GET), not on cross-site posts
	}
}

class ApplicantSessionFilter(private val sessions: ApplicantSessions) : OncePerRequestFilter() {
	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		val token = request.cookies?.firstOrNull { it.name == ApplicantSessions.COOKIE }?.value
		token?.takeIf { it.isNotEmpty() }?.let(sessions::find)?.let { SecurityContextHolder.getContext().authentication = ApplicantAuthentication(it) }
		chain.doFilter(request, response)
	}
}

/**
 * The applicant filter chain (PRD: a second security filter chain for registration and claims): everything under /api/apply
 * only, no Google sign-in, no server session; starting points are public, the rest needs an applicant session.
 * Applicants can reach their own registration and nothing else.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class ApplicantSecurity {
	@Bean
	@Order(1)
	fun applicantChain(http: HttpSecurity, sessions: ApplicantSessions): SecurityFilterChain {
		http
			.securityMatcher("/api/apply/**")
			.authorizeHttpRequests {
				it.requestMatchers("/api/apply/chapters/**", "/api/apply/claim", "/api/apply/roster").permitAll()
				it.anyRequest().hasRole("APPLICANT")
			}
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.addFilterBefore(ApplicantSessionFilter(sessions), UsernamePasswordAuthenticationFilter::class.java)
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.csrf { it.spa() }
		return http.build()
	}
}
