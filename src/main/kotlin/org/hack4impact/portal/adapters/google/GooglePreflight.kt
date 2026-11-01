package org.hack4impact.portal.adapters.google

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.preflight.Check
import org.hack4impact.portal.preflight.ToolPreflight
import org.hack4impact.portal.resolver.Tool
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Component
import java.io.File

/**
 * Preflight for Google groups (stage 2, package 8's plan): domain-wide delegation grants a token for the read scopes
 * and, with the write switch, the group write scope. Google refuses a token for scopes delegation doesn't allow
 * (`unauthorized_client`), so asking for one shows what's granted without trying a write.
 */
@Component
@ConditionalOnBooleanProperty("portal.adapters.google.enabled")
class GooglePreflight(private val adapters: AdapterProperties) : ToolPreflight {
	override val tool = Tool.GOOGLE

	/** A token for [scopes], or an exception saying why not. Tests replace it. */
	internal var token: (List<String>) -> String = { scopes ->
		val google = adapters.google
		val key = google.serviceAccountKey.trim().let { if (it.startsWith("{")) it else File(it).readText() }
		GoogleServiceAccountToken(key, google.adminSubject, scopes)()
	}

	override fun checks(): List<Check> {
		val name = "Google permissions"
		val google = adapters.google
		val read = granted(GoogleServiceAccountToken.READ_SCOPES)
		val write = granted(GoogleServiceAccountToken.WRITE_SCOPES)
		return listOf(
			when {
				read != null -> Check("Sync", name, Check.Level.FAIL, "delegation doesn't allow the read scopes for ${google.adminSubject}: $read")
				write == null -> Check("Sync", name, Check.Level.OK, "delegation allows the read scopes and the group write scope for ${google.adminSubject}")
				google.write -> Check("Sync", name, Check.Level.FAIL, "writes are on but delegation doesn't allow admin.directory.group: $write")
				else -> Check("Sync", name, Check.Level.WARN, "delegation doesn't allow admin.directory.group (writes are off): $write")
			},
		)
	}

	/** Null when Google gives a token for [scopes]; else why not. */
	private fun granted(scopes: List<String>): String? = try {
		token(scopes)
		null
	} catch (e: Exception) {
		(e.message ?: e.javaClass.simpleName).lineSequence().first().take(200)
	}
}
