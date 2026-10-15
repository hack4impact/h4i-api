package org.hack4impact.portal.auth

import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.system.exitProcess

@ConfigurationProperties("portal.grant")
data class GrantProperties(
	val enabled: Boolean = false,
	val email: String = "",
	/** lead, co_lead, viewer, or national_admin. */
	val role: String = "",
	/** Chapter code, e.g. umd. Not needed for national_admin. */
	val chapter: String = "",
	val title: String = "",
	/** Only used to create the person when nobody in the portal has this email yet. */
	val firstName: String = "",
	val lastName: String = "",
)

/**
 * Gives someone a chapter role or national admin, until there's a screen for it (wiki decision 41: leads are
 * entered by hand). Finds the person by their @hack4impact.org address, or creates them if names are given.
 */
@Component
class GrantRole(private val dsl: DSLContext, private val transactions: TransactionTemplate) {

	fun grant(p: GrantProperties): String = transactions.execute {
		val email = p.email.trim().lowercase()
		require(email.isNotEmpty()) { "Set --portal.grant.email" }
		require(p.role in setOf("lead", "co_lead", "viewer", "national_admin")) { "--portal.grant.role must be lead, co_lead, viewer or national_admin" }
		val (personId, created) = findPerson(email)?.let { it to false } ?: (createPerson(email, p) to true)
		val now = OffsetDateTime.now()
		val summary = if (p.role == "national_admin") {
			dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, personId).onConflictDoNothing().execute()
			audit(personId, null, "national_admin.grant", mapOf("email" to email))
			"$email is a national admin"
		} else {
			val chapterId = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq(p.chapter.trim().lowercase()), CHAPTER.DELETED_AT.isNull)
				.fetchOne(CHAPTER.ID) ?: error("No chapter with code '${p.chapter}' (set --portal.grant.chapter)")
			// One role per person per chapter: end any other active role there first.
			dsl.update(CHAPTER_ROLE).set(CHAPTER_ROLE.ENDS_AT, now)
				.where(CHAPTER_ROLE.PERSON_ID.eq(personId), CHAPTER_ROLE.CHAPTER_ID.eq(chapterId), CHAPTER_ROLE.ENDS_AT.isNull, CHAPTER_ROLE.ROLE.ne(p.role))
				.execute()
			val exists = dsl.fetchExists(CHAPTER_ROLE, CHAPTER_ROLE.PERSON_ID.eq(personId).and(CHAPTER_ROLE.CHAPTER_ID.eq(chapterId)).and(CHAPTER_ROLE.ROLE.eq(p.role)).and(CHAPTER_ROLE.ENDS_AT.isNull))
			if (!exists) {
				dsl.insertInto(CHAPTER_ROLE)
					.set(CHAPTER_ROLE.PERSON_ID, personId).set(CHAPTER_ROLE.CHAPTER_ID, chapterId).set(CHAPTER_ROLE.ROLE, p.role)
					.set(CHAPTER_ROLE.TITLE, p.title.ifBlank { null })
					.execute()
			}
			dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, personId).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapterId).onConflictDoNothing().execute()
			audit(personId, chapterId, "chapter_role.grant", mapOf("email" to email, "role" to p.role))
			"$email is ${p.role} of ${p.chapter.lowercase()}"
		}
		summary + if (created) " (new person created)" else ""
	}

	private fun findPerson(email: String): UUID? =
		dsl.select(PERSON.ID).from(PERSON).where(PERSON.DELETED_AT.isNull).and(
			DSL.lower(PERSON.ORG_EMAIL).eq(email).or(
				DSL.exists(DSL.selectOne().from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(PERSON.ID), TOOL_ACCOUNT.TOOL.eq("google"), DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(email))),
			),
		).orderBy(PERSON.CREATED_AT).limit(1).fetchOne(PERSON.ID)

	private fun createPerson(email: String, p: GrantProperties): UUID {
		require(p.firstName.isNotBlank() && p.lastName.isNotBlank()) {
			"Nobody in the portal has $email. Add --portal.grant.first-name and --portal.grant.last-name to create them."
		}
		return dsl.insertInto(PERSON)
			.set(PERSON.FIRST_NAME, p.firstName.trim()).set(PERSON.LAST_NAME, p.lastName.trim())
			.set(PERSON.ORG_EMAIL, email).set(PERSON.STATUS, "active")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
	}

	private fun audit(personId: UUID, chapterId: UUID?, action: String, after: Map<String, String>) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "system").set(AUDIT_EVENT.ACTION, action)
			.set(AUDIT_EVENT.TARGET_TYPE, "person").set(AUDIT_EVENT.TARGET_ID, personId)
			.set(AUDIT_EVENT.CHAPTER_ID, chapterId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(org.hack4impact.portal.adapters.HttpJson.MAPPER.writeValueAsString(after)))
			.execute()
	}
}

/** `./gradlew bootRun --args='--spring.profiles.active=grant --portal.grant.email=… --portal.grant.chapter=umd --portal.grant.role=lead'` */
@Component
@ConditionalOnBooleanProperty("portal.grant.enabled")
class GrantRoleRunner(
	private val properties: GrantProperties,
	private val grants: GrantRole,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val log = LoggerFactory.getLogger(javaClass)
		val code = try {
			log.info("Granted: {}", grants.grant(properties))
			0
		} catch (e: IllegalArgumentException) {
			log.error("Not granted: {}", e.message)
			1
		} catch (e: IllegalStateException) {
			log.error("Not granted: {}", e.message)
			1
		}
		exitProcess(SpringApplication.exit(context, { code }))
	}
}
