package org.hack4impact.portal.memberdb

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * The national member database as an integration (wiki: staged-rollout, decision 162). As environment variables:
 * `MEMBER_DB_URL`, `MEMBER_DB_USERNAME`, `MEMBER_DB_PASSWORD`, `PORTAL_MEMBERDB_PULL`, `PORTAL_MEMBERDB_WRITE`.
 */
@ConfigurationProperties("portal.member-db")
data class MemberDbProperties(
	val url: String = "",
	val username: String = "",
	val password: String = "",
	/** Pull on a schedule ([pullInterval]); off unless set. */
	val pull: Boolean = false,
	val pullInterval: Duration = Duration.ofMinutes(15),
	/** The write switch: without it the portal never writes, whatever national sets (like the tools' write switches). */
	val write: Boolean = false,
)

/** The connection: its own small pool, not the portal's data source. Nothing connects until it's first used. */
@Component
class MemberDb(private val properties: MemberDbProperties) : AutoCloseable {
	val configured get() = properties.url.isNotBlank()

	private val pool = lazy {
		check(configured) { "The member database isn't configured (MEMBER_DB_URL)" }
		HikariDataSource(
			HikariConfig().apply {
				jdbcUrl = properties.url
				username = properties.username
				password = properties.password
				maximumPoolSize = 2
				poolName = "member-db"
				connectionTimeout = Duration.ofSeconds(10).toMillis()
			},
		)
	}

	private val dataSource by pool

	val jdbc: JdbcClient by lazy { JdbcClient.create(dataSource) }

	private val transactions by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }

	/** One member-database transaction: all of [block]'s writes commit together or not at all. */
	fun <T> inTransaction(block: (JdbcClient) -> T): T = transactions.execute { block(jdbc) }!!

	override fun close() {
		if (pool.isInitialized()) dataSource.close()
	}
}
