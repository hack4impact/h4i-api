package org.hack4impact.portal.preflight

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.documenso.DocumensoClient
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_QUESTION
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.MEMBER_DB_STATE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.mail.MailProperties
import org.hack4impact.portal.memberdb.MemberDb
import org.hack4impact.portal.memberdb.MemberDbProperties
import org.hack4impact.portal.memberdb.MemberDbPull
import org.hack4impact.portal.registration.GitHubOAuth
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.system.exitProcess

/** [chapters]: chapter codes to check (e.g. the pilot); empty = every active chapter taking applications. */
@ConfigurationProperties("portal.preflight")
data class PreflightProperties(val enabled: Boolean = false, val chapters: List<String> = emptyList())

/** One result. FAIL: stage 1 won't work as configured; WARN: works, but check it's meant. */
data class Check(val area: String, val name: String, val level: Level, val detail: String) {
	enum class Level { OK, WARN, FAIL }
}

/**
 * Checks a deployment before it goes live (wiki: staged-rollout): settings, the member database's grants and Khoa's
 * changes, Google, Documenso, GitHub sign-in, and each chapter's setup. Only reads: nothing is created, written or
 * emailed. Run with the `preflight` profile against the real settings; see the README.
 */
@Component
class Preflight(
	private val dsl: DSLContext,
	private val memberDb: MemberDb,
	private val memberDbProperties: MemberDbProperties,
	private val pull: MemberDbPull,
	private val adapters: AdapterProperties,
	private val googleAccounts: ObjectProvider<GoogleAccounts>,
	private val documensoClient: ObjectProvider<DocumensoClient>,
	private val gitHubOAuth: GitHubOAuth,
	private val mail: MailProperties,
	private val environment: Environment,
	private val properties: PreflightProperties,
) {
	fun run(): List<Check> = buildList {
		portal()
		memberDatabase()
		googleAccounts()
		agreements()
		chapters()
	}

	private fun MutableList<Check>.ok(area: String, name: String, detail: String) = add(Check(area, name, Check.Level.OK, detail))
	private fun MutableList<Check>.warn(area: String, name: String, detail: String) = add(Check(area, name, Check.Level.WARN, detail))
	private fun MutableList<Check>.fail(area: String, name: String, detail: String) = add(Check(area, name, Check.Level.FAIL, detail))

	/** Runs [block]; an exception is a FAIL with its message. */
	private fun MutableList<Check>.attempt(area: String, name: String, block: MutableList<Check>.() -> Unit) = try {
		block()
	} catch (e: Exception) {
		fail(area, name, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(300))
	}

	private fun MutableList<Check>.portal() {
		val area = "Portal"
		val baseUrl = environment.getProperty("portal.base-url").orEmpty()
		if (baseUrl.startsWith("https://")) ok(area, "Address", baseUrl) else warn(area, "Address", "$baseUrl: links in emails and the OAuth callbacks use it (PORTAL_BASE_URL)")
		val signIn = environment.getProperty("spring.security.oauth2.client.registration.google.client-id")
		if (signIn.isNullOrBlank() || signIn == "not-configured") fail(area, "Google sign-in", "PORTAL_GOOGLE_CLIENT_ID and _SECRET aren't set: leads can't sign in")
		else ok(area, "Google sign-in", "client set")
		if (gitHubOAuth.configured) ok(area, "GitHub sign-in", "OAuth app set; its callback must be $baseUrl/api/apply/github/callback")
		else fail(area, "GitHub sign-in", "portal.github-oauth.client-id and client-secret aren't set: applicants can't link GitHub")
		when (mail.mode) {
			"log" -> fail(area, "Email", "portal.mail.mode is log: codes and claim links are only written to the log (PORTAL_MAIL_MODE)")
			else -> ok(area, "Email", "${mail.mode}, from ${mail.from} (no test message sent)")
		}
		val admins = dsl.fetchCount(NATIONAL_ADMIN)
		if (admins == 0) fail(area, "National admins", "none: nobody can reach the National page (the grant profile adds one)") else ok(area, "National admins", "$admins")
	}

	private fun MutableList<Check>.memberDatabase() {
		val area = "Member database"
		if (!memberDb.configured) {
			if (memberDbProperties.pull || memberDbProperties.write) fail(area, "Connection", "pull or write is on but MEMBER_DB_URL isn't set")
			else warn(area, "Connection", "not configured: no pull and no writes")
			return
		}
		val db = memberDb.jdbc
		attempt(area, "Connection") { ok(area, "Connection", "connected as ${db.sql("SELECT current_user").query(String::class.java).single()}") }
		if (last().level == Check.Level.FAIL) return
		attempt(area, "Change check") { pull.signature(); ok(area, "Change check", "row counts and updated_at read on all six tables") }

		val reads = listOf("volunteers", "academic_profiles", "volunteer_accounts", "chapters", "academic_institutions", "academic_terms")
		val writes = listOf("volunteers", "academic_profiles", "volunteer_accounts")
		attempt(area, "Grants") {
			fun has(table: String, privilege: String) = db.sql("SELECT has_table_privilege(current_user, ?, ?)").param(table).param(privilege).query(Boolean::class.java).single()
			val noRead = reads.filterNot { has(it, "SELECT") }
			if (noRead.isEmpty()) ok(area, "Read grants", "SELECT on all six tables") else fail(area, "Read grants", "no SELECT on ${noRead.joinToString()}")
			val noWrite = writes.flatMap { t -> listOf("INSERT", "UPDATE").filterNot { has(t, it) }.map { "$it on $t" } }
			when {
				noWrite.isEmpty() -> ok(area, "Write grants", "INSERT and UPDATE on ${writes.joinToString()}")
				memberDbProperties.write -> fail(area, "Write grants", "writes are on but it lacks ${noWrite.joinToString()}")
				else -> warn(area, "Write grants", "lacks ${noWrite.joinToString()} (writes are off)")
			}
			val delete = writes.filter { has(it, "DELETE") }
			if (delete.isNotEmpty()) warn(area, "No delete grant", "DELETE is granted on ${delete.joinToString()}: the portal never deletes, so portal_sync shouldn't be able to")
			else ok(area, "No delete grant", "no DELETE on what it writes")
		}
		attempt(area, "Khoa's changes") {
			fun primaryKey(table: String) = db.sql("SELECT EXISTS (SELECT 1 FROM pg_index WHERE indrelid = to_regclass(?) AND indisprimary)").param(table).query(Boolean::class.java).single()
			val noKey = listOf("volunteers", "volunteer_accounts").filterNot(::primaryKey)
			val unique = db.sql(
				"""
				SELECT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = to_regclass('volunteer_accounts') AND i.indisunique
				  AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))
				      = ARRAY['external_id', 'provider'])
				""".trimIndent(),
			).query(Boolean::class.java).single()
			val noTrigger = reads.filter { it != "academic_terms" }.filter {
				db.sql("SELECT count(*) FROM pg_trigger WHERE tgrelid = to_regclass(?) AND NOT tgisinternal").param(it).query(Int::class.java).single() == 0
			}
			val missing = noKey.map { "primary key on $it.id" } + listOfNotNull("unique (provider, external_id) on volunteer_accounts".takeUnless { unique }) +
				noTrigger.map { "updated_at trigger on $it" }
			when {
				missing.isEmpty() -> ok(area, "Khoa's changes", "keys, unique accounts and updated_at triggers in place (scripts/member-db-changes.sql)")
				memberDbProperties.write -> fail(area, "Khoa's changes", "missing ${missing.joinToString()}: run scripts/member-db-changes.sql first")
				else -> warn(area, "Khoa's changes", "missing ${missing.joinToString()} (needed before writes go on)")
			}
		}
		val state = dsl.selectFrom(MEMBER_DB_STATE).fetchOne()
		val switches = "pull ${if (memberDbProperties.pull) "on" else "off"}, write switch ${if (memberDbProperties.write) "on" else "off"}, " +
			"${if (state?.dryRun != false) "dry run" else "live"}${if (state?.paused == true) ", paused" else ""}"
		if (memberDbProperties.write && state?.dryRun == false && state.paused != true) warn(area, "Switches", "$switches: writes go straight to the member database")
		else ok(area, "Switches", switches)
	}

	private fun MutableList<Check>.googleAccounts() {
		val area = "Google"
		val accounts = googleAccounts.ifAvailable
		if (accounts == null) {
			warn(area, "Accounts", "portal.adapters.google.create-accounts is off: approvals and claims don't create @hack4impact.org addresses")
			return
		}
		attempt(area, "Accounts") {
			val admin = adapters.google.adminSubject
			if (accounts.exists(admin)) ok(area, "Accounts", "signed in as $admin through the service account")
			else fail(area, "Accounts", "the service account works but $admin wasn't found")
		}
		if (adapters.google.userScope.isNotBlank()) warn(area, "Org-unit scope", "accounts only under ${adapters.google.userScope} (a sandbox setting; empty in production)")
	}

	private fun MutableList<Check>.agreements() {
		val area = "Documenso"
		val client = documensoClient.ifAvailable
		val templates = dsl.selectFrom(AGREEMENT_TEMPLATE).where(AGREEMENT_TEMPLATE.ACTIVE.isTrue).fetch()
		if (client == null) {
			warn(area, "Agreements", "the adapter is off: Join doesn't ask for a signature (Q52 says it should)")
			return
		}
		if (!adapters.documenso.write) warn(area, "Agreements", "portal.adapters.documenso.write is off: Join doesn't ask for a signature")
		if (templates.isEmpty()) warn(area, "Templates", "no active template: national adds it on the National page")
		for (t in templates) attempt(area, "Template ${t.kind}") {
			client.templateSigner(t.documensoTemplateId!!)
			ok(area, "Template ${t.kind}", "${t.name} (${t.documensoTemplateId}) is a template with a signer")
		}
		if (adapters.documenso.webhookSecret.isBlank()) warn(area, "Webhook", "no webhook secret: signatures show up on the regular pass (5 minutes) instead of at once")
		else ok(area, "Webhook", "secret set; the team webhook must point at ${environment.getProperty("portal.base-url")}/api/webhooks/documenso")
	}

	private fun MutableList<Check>.chapters() {
		val area = "Chapter"
		val now = OffsetDateTime.now()
		val chosen = properties.chapters.map { it.trim().lowercase() }
		val chapters = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.GOOGLE_DOMAIN, CHAPTER.GOOGLE_ORG_UNIT, CHAPTER.INSTITUTION_ID, INSTITUTION.EMAIL_DOMAINS)
			.from(CHAPTER).leftJoin(INSTITUTION).on(INSTITUTION.ID.eq(CHAPTER.INSTITUTION_ID))
			.where(CHAPTER.DELETED_AT.isNull, if (chosen.isEmpty()) CHAPTER.STATUS.eq("active").and(CHAPTER.APPLICATIONS_OPEN.isTrue) else CHAPTER.CODE.`in`(chosen))
			.orderBy(CHAPTER.CODE).fetch()
		(chosen - chapters.map { it.value2()!! }.toSet()).forEach { fail(area, it, "no such chapter") }
		if (chapters.isEmpty() && chosen.isEmpty()) warn(area, "Chapters", "no active chapter takes applications")
		val creating = googleAccounts.ifAvailable != null
		val year = LocalDate.now().year.toShort()
		for (c in chapters) {
			val (id, code, domain, orgUnit, institution, emailDomains) = c
			val problems = mutableListOf<String>()
			val warnings = mutableListOf<String>()
			if (emailDomains.isNullOrEmpty()) problems += "no school email domains (applicants can't verify)"
			if (domain.isNullOrBlank() || orgUnit.isNullOrBlank()) (if (creating) problems else warnings) += "no Google domain or org unit (no address is created)"
			val leads = dsl.fetchCount(CHAPTER_ROLE, CHAPTER_ROLE.CHAPTER_ID.eq(id), CHAPTER_ROLE.ROLE.`in`("lead", "co_lead"),
				CHAPTER_ROLE.ENDS_AT.isNull.or(CHAPTER_ROLE.ENDS_AT.gt(now)))
			if (leads == 0) problems += "no lead or co-lead (nobody can approve)"
			if (dsl.fetchCount(CHAPTER_QUESTION, CHAPTER_QUESTION.CHAPTER_ID.eq(id)) == 0) warnings += "no questions of its own"
			if (institution == null || dsl.fetchCount(TERM, TERM.INSTITUTION_ID.eq(institution), TERM.YEAR.gt(year)) == 0) {
				warnings += "no academic terms after $year (later graduations aren't linked; Q51: seed academic_terms)"
			}
			when {
				problems.isNotEmpty() -> fail(area, code!!, (problems + warnings).joinToString("; "))
				warnings.isNotEmpty() -> warn(area, code!!, warnings.joinToString("; "))
				else -> ok(area, code!!, "domains ${emailDomains!!.joinToString()}, ${domain ?: "no Google domain"}, $leads lead(s)")
			}
		}
	}
}

/**
 * `--spring.profiles.active=preflight`: prints each check and exits 1 if any failed. The scheduled jobs start a
 * minute or more after startup, so they don't run before it exits.
 */
@Component
@ConditionalOnBooleanProperty("portal.preflight.enabled")
class PreflightRunner(private val preflight: Preflight, private val context: ConfigurableApplicationContext) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		val checks = preflight.run()
		val report = checks.joinToString("\n") { "%-4s  %-16s  %-22s  %s".format(it.level, it.area, it.name, it.detail) }
		val failed = checks.count { it.level == Check.Level.FAIL }
		log.info("Preflight:\n{}\n{} failed, {} warnings", report, failed, checks.count { it.level == Check.Level.WARN })
		exitProcess(SpringApplication.exit(context, { if (failed > 0) 1 else 0 }))
	}
}

