package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool

/**
 * A tool held in memory, for local development and tests: same contract as the real adapters, reads and writes,
 * including failures. Set [failure] to make every call throw it, or [failWrites] to fail only writes.
 * [writes] logs every write, e.g. `grant C1 U1 WRITE`.
 */
class InMemoryReadAdapter(
	override val tool: Tool,
	var accounts: List<ToolAccount> = emptyList(),
	var resources: Map<ToolResource, List<ResourceMember>> = emptyMap(),
	var failure: AdapterException? = null,
) : ReadAdapter, WriteAdapter {
	var failWrites: AdapterException? = null
	/** Called on every read of the tool's accounts (tests check what the caller's state is then). */
	var onRead: (() -> Unit)? = null
	val writes = mutableListOf<String>()

	override fun accounts(): List<ToolAccount> = guarded { onRead?.invoke(); accounts }

	override fun resources(): List<ToolResource> = guarded { resources.keys.toList() }

	override fun members(resourceId: String): List<ResourceMember> = guarded {
		resources.entries.firstOrNull { it.key.externalId == resourceId }?.value ?: throw NotFound(tool, resourceId)
	}

	override fun create(name: String): String = writing {
		val id = "new-$name"
		if (resources.keys.none { it.externalId == id }) resources = resources + (ToolResource(id, name) to emptyList())
		writes += "create $name"
		id
	}

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult = writing {
		val key = resources.keys.firstOrNull { it.externalId == resourceId } ?: throw NotFound(tool, resourceId)
		// Without an account ID the fake behaves like an invitation: the person has to accept first.
		val id = account.id ?: return@writing GrantResult.INVITED.also { writes += "invite $resourceId ${account.login ?: account.email}" }
		resources = resources + (key to (resources.getValue(key).filter { it.accountId != id } + ResourceMember(id, access)))
		writes += "grant $resourceId $id $access"
		GrantResult.DONE
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult = writing {
		val key = resources.keys.firstOrNull { it.externalId == resourceId } ?: throw NotFound(tool, resourceId)
		resources = resources + (key to resources.getValue(key).filter { it.accountId != accountId })
		writes += "revoke $resourceId $accountId"
		GrantResult.DONE
	}

	override fun archive(resourceId: String) = writing {
		resources = resources.filterKeys { it.externalId != resourceId }
		writes += "archive $resourceId"
	}

	private fun <T> guarded(read: () -> T): T {
		failure?.let { throw it }
		return read()
	}

	private fun <T> writing(write: () -> T): T {
		failure?.let { throw it }
		failWrites?.let { throw it }
		return write()
	}
}
