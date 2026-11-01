package org.hack4impact.portal.adapters.github

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Installation access tokens for a GitHub App: signs a short-lived JWT with the app's private key, exchanges it
 * for an installation token, and reuses that until a minute before it expires.
 */
class GitHubAppToken(
	private val baseUrl: String,
	private val appId: String,
	private val installationId: String,
	privateKeyPem: String,
	private val http: HttpJson = HttpJson(Tool.GITHUB),
	private val clock: Clock = Clock.systemUTC(),
) : () -> String {
	private val key = rsaPrivateKey(privateKeyPem)
	private var cached: Pair<String, Instant>? = null

	@Synchronized
	override fun invoke(): String {
		cached?.let { (token, expires) -> if (clock.instant().isBefore(expires.minusSeconds(60))) return token }
		val body = http.post("$baseUrl/app/installations/$installationId/access_tokens", mapOf(
			"Authorization" to "Bearer ${jwt()}",
			"Accept" to "application/vnd.github+json",
		)).body
		val token = body.text("token")!!
		cached = token to Instant.parse(body.text("expires_at")!!)
		return token
	}

	/** RS256 JWT as GitHub requires: issued a minute ago (clock skew), valid nine minutes, issuer = app ID. */
	internal fun jwt(): String {
		val now = clock.instant().epochSecond
		val encoder = Base64.getUrlEncoder().withoutPadding()
		val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
		val payload = encoder.encodeToString("""{"iat":${now - 60},"exp":${now + 540},"iss":"$appId"}""".toByteArray())
		val signature = Signature.getInstance("SHA256withRSA").apply {
			initSign(key)
			update("$header.$payload".toByteArray())
		}.sign()
		return "$header.$payload.${encoder.encodeToString(signature)}"
	}

	companion object {
		/** Reads a PEM private key. GitHub issues PKCS#1 ("BEGIN RSA PRIVATE KEY"), which the JDK can't read, so it's wrapped as PKCS#8. */
		fun rsaPrivateKey(pem: String): PrivateKey {
			val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
			val pkcs8 = if ("BEGIN RSA PRIVATE KEY" in pem) wrapPkcs1(der) else der
			return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
		}

		private val RSA_ALGORITHM = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)

		private fun wrapPkcs1(pkcs1: ByteArray) = der(0x30, byteArrayOf(0x02, 0x01, 0x00) + RSA_ALGORITHM + der(0x04, pkcs1))

		private fun der(tag: Int, content: ByteArray): ByteArray {
			val n = content.size
			val length = when {
				n < 0x80 -> byteArrayOf(n.toByte())
				n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
				n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
				else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
			}
			return byteArrayOf(tag.toByte()) + length + content
		}
	}
}
