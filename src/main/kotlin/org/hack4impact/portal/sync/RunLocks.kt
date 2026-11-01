package org.hack4impact.portal.sync

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource

/**
 * Runs work one at a time per key: within this process (a lock) and across processes on the same database (a Postgres
 * advisory lock, held on a connection of its own for as long as the work runs). Code review H2 and M12: overlapping
 * syncs could create a channel twice or lose a Vaultwarden change; overlapping agreement sends sent one twice.
 */
@Component
class RunLocks(private val dataSource: DataSource) {
	companion object {
		const val SYNC = 7_244_501L
		const val AGREEMENTS = 7_244_502L
	}

	private val locks = ConcurrentHashMap<Long, ReentrantLock>()

	fun <T> exclusive(key: Long, block: () -> T): T {
		val lock = locks.computeIfAbsent(key) { ReentrantLock() }
		lock.lock()
		try {
			dataSource.connection.use { connection ->
				connection.prepareStatement("SELECT pg_advisory_lock(?)").use { it.setLong(1, key); it.execute() }
				try {
					return block()
				} finally {
					connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { it.setLong(1, key); it.execute() }
				}
			}
		} finally {
			lock.unlock()
		}
	}
}
