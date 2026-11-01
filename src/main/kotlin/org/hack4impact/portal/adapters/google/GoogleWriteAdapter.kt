package org.hack4impact.portal.adapters.google

import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import java.time.Duration

/**
 * Changes Google groups (build plan step 9) through the Directory API, as the delegated admin with
 * [GoogleServiceAccountToken.WRITE_SCOPES]. Resources are groups, by address. ADMIN is a group manager; READ and
 * WRITE are members. Groups take any address, so someone without a Workspace account is added by email.
 *
 * With [groupScope] set, only matching groups are created or changed (decisions 61 and 69): the sandbox shares
 * the real hack4impact.org Workspace.
 */
class GoogleWriteAdapter(
	private val baseUrl: String,
	private val token: () -> String,
	private val groupScope: Regex? = null,
	private val http: HttpJson = HttpJson(Tool.GOOGLE),
	/** A new group takes a moment before its members can be changed; wait this long between tries, [settleTries] times. */
	private val settle: Duration = Duration.ofSeconds(5),
	private val settleTries: Int = 6,
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : WriteAdapter {
	override val tool = Tool.GOOGLE
	private val directory get() = "$baseUrl/admin/directory/v1"

	override fun create(name: String): String {
		inScope(name)
		try {
			http.postJson("$directory/groups", mapOf("email" to name, "name" to name.substringBefore('@'), "description" to "Managed by the H4I portal"), headers())
		} catch (e: Rejected) {
			if (e.status == 409) throw Rejected(tool, "$name already exists: adopt it instead", 409)
			throw e
		}
		return name
	}

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult {
		inScope(resourceId)
		val role = if (access == Access.ADMIN) "MANAGER" else "MEMBER"
		val member = account.email?.let { mapOf("email" to it) } ?: account.id?.let { mapOf("id" to it) } ?: return GrantResult.WAITING
		val group = HttpJson.encode(resourceId)
		settling(resourceId) {
			try {
				http.postJson("$directory/groups/$group/members", member + ("role" to role), headers())
			} catch (e: Rejected) {
				if (e.status != 409) throw e
				// Already a member: make sure the role is right.
				http.putJson("$directory/groups/$group/members/${HttpJson.encode(member.values.single())}", mapOf("role" to role), headers())
			}
		}
		return GrantResult.DONE
	}

	/**
	 * Google answers "not found" for a group's members for a while after the group is created. Wait and try again;
	 * if it's still not there, report it as temporary, so the sync retries later instead of giving up.
	 */
	private fun settling(groupEmail: String, call: () -> Unit) {
		repeat(settleTries) {
			try {
				return call()
			} catch (e: NotFound) {
				sleep(settle)
			}
		}
		try {
			call()
		} catch (e: NotFound) {
			throw Unavailable(tool, "group $groupEmail isn't ready yet (new groups take a few minutes): will retry")
		}
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult {
		inScope(resourceId)
		try {
			http.delete("$directory/groups/${HttpJson.encode(resourceId)}/members/${HttpJson.encode(accountId)}", headers())
		} catch (e: NotFound) {
			// Not a member: nothing to remove.
		}
		return GrantResult.DONE
	}

	override fun archive(resourceId: String) {
		inScope(resourceId)
		try {
			http.delete("$directory/groups/${HttpJson.encode(resourceId)}", headers())
		} catch (e: NotFound) {
			// Already gone.
		}
	}

	private fun inScope(group: String) {
		if (groupScope != null && !groupScope.containsMatchIn(group)) throw Rejected(tool, "group $group is outside portal.adapters.google.group-scope")
	}

	private fun headers() = mapOf("Authorization" to "Bearer ${token()}")
}
