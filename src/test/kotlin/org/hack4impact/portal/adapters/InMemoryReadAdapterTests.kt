package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import java.time.Duration

class InMemoryReadAdapterTests : ReadAdapterContract() {
	override val sandbox = Sandbox(
		listOf(
			ToolAccount("U1", "ada", "ada@hack4impact.org", "Ada", AccountState.ACTIVE),
			ToolAccount("U2", "alan", "alan@hack4impact.org", "Alan", AccountState.SUSPENDED),
			ToolAccount(null, "grace", "grace@example.org", null, AccountState.INVITED),
		),
		mapOf(
			ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U1", Access.ADMIN), ResourceMember("U2", Access.WRITE)),
			ToolResource("C2", "umd-old", archived = true) to emptyList(),
		),
	)

	override fun adapter(sandbox: Sandbox, failure: Failure?) = InMemoryReadAdapter(
		Tool.SLACK, sandbox.accounts, sandbox.resources,
		when (failure) {
			Failure.RATE_LIMIT -> RateLimited(Tool.SLACK, Duration.ofSeconds(30))
			Failure.SERVER_ERROR -> Unavailable(Tool.SLACK, "down")
			Failure.BAD_CREDENTIALS -> AuthFailed(Tool.SLACK, "bad token")
			null -> null
		},
	)
}
