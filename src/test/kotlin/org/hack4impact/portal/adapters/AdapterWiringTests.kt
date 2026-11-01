package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * From the full portal's, trimmed to what exists in stage 2 package 3. Each tool's adapter item adds its case to
 * "every adapter is created from environment variables with the documented names".
 */
class AdapterWiringTests {
	private val runner = ApplicationContextRunner().withUserConfiguration(AdapterConfiguration::class.java)

	@Test
	fun `no adapter is created unless enabled`() {
		runner.run {
			assertTrue(it.getBeansOfType(ReadAdapter::class.java).isEmpty())
			assertTrue(it.getBeansOfType(WriteAdapter::class.java).isEmpty())
		}
	}

	/** Sets properties the way the vault delivers them: as environment variables. */
	private fun env(vararg vars: Pair<String, String>) = runner.withInitializer { ctx ->
		ctx.environment.propertySources.addFirst(org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", vars.toMap()))
	}

	@Test
	fun `each tool's adapters are created from environment variables with the documented names`() {
		env("PORTAL_ADAPTERS_SLACK_ENABLED" to "true", "PORTAL_ADAPTERS_SLACK_BOTTOKEN" to "xoxb-test").run { ctx ->
			assertEquals(setOf(Tool.SLACK), ctx.getBeansOfType(ReadAdapter::class.java).values.map { it.tool }.toSet())
			assertTrue(ctx.getBeansOfType(WriteAdapter::class.java).isEmpty()) // reading only, without the write switch
		}
		env("PORTAL_ADAPTERS_SLACK_ENABLED" to "true", "PORTAL_ADAPTERS_SLACK_BOTTOKEN" to "xoxb-test", "PORTAL_ADAPTERS_SLACK_WRITE" to "true").run { ctx ->
			assertEquals(setOf(Tool.SLACK), ctx.getBeansOfType(WriteAdapter::class.java).values.map { it.tool }.toSet())
		}
	}

	/** A service-account key Google's library parses (the token is only fetched when first used). */
	private val googleKey: String by lazy {
		val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
		val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(key) + "\n-----END PRIVATE KEY-----\n"
		"""{"type":"service_account","client_email":"portal@h4i-portal.iam.gserviceaccount.com","client_id":"1","private_key_id":"k","private_key":${HttpJson.MAPPER.writeValueAsString(pem)},"token_uri":"https://oauth2.googleapis.com/token"}"""
	}

	@Test
	fun `Google groups are read and changed with the documented settings`() {
		env(
			"PORTAL_ADAPTERS_GOOGLE_ENABLED" to "true", "PORTAL_ADAPTERS_GOOGLE_ADMINSUBJECT" to "provisioning@hack4impact.org",
			"PORTAL_ADAPTERS_GOOGLE_SERVICEACCOUNTKEY" to googleKey, "PORTAL_ADAPTERS_GOOGLE_ORGUNITPATH" to "/Portal Sandbox",
			"PORTAL_ADAPTERS_GOOGLE_GROUPSCOPE" to "^sandbox-", "PORTAL_ADAPTERS_GOOGLE_WRITE" to "true",
		).run { ctx ->
			assertEquals(setOf(Tool.GOOGLE), ctx.getBeansOfType(ReadAdapter::class.java).values.map { it.tool }.toSet())
			assertEquals(setOf(Tool.GOOGLE), ctx.getBeansOfType(WriteAdapter::class.java).values.map { it.tool }.toSet())
			assertEquals("/Portal Sandbox", ctx.getBean(AdapterProperties::class.java).google.orgUnitPath)
		}
		env("PORTAL_ADAPTERS_GOOGLE_ENABLED" to "true", "PORTAL_ADAPTERS_GOOGLE_SERVICEACCOUNTKEY" to googleKey).run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.google.admin-subject must be set")
		}
		env("PORTAL_ADAPTERS_GOOGLE_WRITE" to "true").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.google.write needs portal.adapters.google.enabled")
		}
	}

	@Test
	fun `GitHub is read and changed through the App with the documented settings`() {
		val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(
			java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded,
		) + "\n-----END PRIVATE KEY-----\n"
		env(
			"PORTAL_ADAPTERS_GITHUB_ENABLED" to "true", "PORTAL_ADAPTERS_GITHUB_ORG" to "https://github.com/h4i-portal-sandbox",
			"PORTAL_ADAPTERS_GITHUB_APPID" to "123", "PORTAL_ADAPTERS_GITHUB_INSTALLATIONID" to "456", "PORTAL_ADAPTERS_GITHUB_PRIVATEKEY" to pem,
			"PORTAL_ADAPTERS_GITHUB_WRITE" to "true",
		).run { ctx ->
			assertEquals(setOf(Tool.GITHUB), ctx.getBeansOfType(ReadAdapter::class.java).values.map { it.tool }.toSet())
			assertEquals(setOf(Tool.GITHUB), ctx.getBeansOfType(WriteAdapter::class.java).values.map { it.tool }.toSet())
		}
		env("PORTAL_ADAPTERS_GITHUB_ENABLED" to "true", "PORTAL_ADAPTERS_GITHUB_APPID" to "123").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.github.org must be set")
		}
	}

	@Test
	fun `Vaultwarden is read with the API key and changed with the master password`() {
		val vault = arrayOf(
			"PORTAL_ADAPTERS_VAULTWARDEN_ENABLED" to "true", "PORTAL_ADAPTERS_VAULTWARDEN_BASEURL" to "https://vault.example.test/",
			"PORTAL_ADAPTERS_VAULTWARDEN_ORGANIZATIONID" to "org1", "PORTAL_ADAPTERS_VAULTWARDEN_CLIENTID" to "user.x", "PORTAL_ADAPTERS_VAULTWARDEN_CLIENTSECRET" to "s",
		)
		env(*vault).run { ctx ->
			assertEquals(setOf(Tool.VAULTWARDEN), ctx.getBeansOfType(ReadAdapter::class.java).values.map { it.tool }.toSet())
			assertTrue(ctx.getBeansOfType(WriteAdapter::class.java).isEmpty())
		}
		env(*vault, "PORTAL_ADAPTERS_VAULTWARDEN_WRITE" to "true", "PORTAL_ADAPTERS_VAULTWARDEN_MASTERPASSWORD" to "pw").run { ctx ->
			assertEquals(setOf(Tool.VAULTWARDEN), ctx.getBeansOfType(WriteAdapter::class.java).values.map { it.tool }.toSet())
		}
		env(*vault, "PORTAL_ADAPTERS_VAULTWARDEN_WRITE" to "true").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.vaultwarden.master-password must be set")
		}
		env("PORTAL_ADAPTERS_VAULTWARDEN_ENABLED" to "true", "PORTAL_ADAPTERS_VAULTWARDEN_BASEURL" to "https://vault.example.test").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.vaultwarden.organization-id must be set")
		}
	}

	@Test
	fun `an enabled adapter without credentials fails at startup, naming the missing setting`() {
		env("PORTAL_ADAPTERS_SLACK_ENABLED" to "true").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.slack.bot-token must be set")
		}
		env("PORTAL_ADAPTERS_SLACK_WRITE" to "true", "PORTAL_ADAPTERS_SLACK_BOTTOKEN" to "xoxb-test").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.slack.write needs portal.adapters.slack.enabled")
		}
	}

	@Test
	fun `the check reports what each tool has, and fails when a tool can't be read`() {
		val slack = InMemoryReadAdapter(
			Tool.SLACK,
			listOf(ToolAccount("U1", "ada", null, null, AccountState.ACTIVE), ToolAccount("U2", "alan", null, null, AccountState.SUSPENDED)),
			mapOf(ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U1", Access.WRITE))),
		)
		val ok = AdapterCheck.run(listOf(slack))
		assertTrue(ok.ok)
		assertContains(ok.report, "accounts: 2 (active 1, suspended 1)")
		assertContains(ok.report, "umd-rise-dc: 1 members")

		val github = InMemoryReadAdapter(Tool.GITHUB, failure = AuthFailed(Tool.GITHUB, "HTTP 401"))
		val failed = AdapterCheck.run(listOf(slack, github))
		assertFalse(failed.ok)
		assertContains(failed.report, "FAILED: GITHUB: HTTP 401")
		assertFalse(AdapterCheck.run(emptyList()).ok)
	}
}
