package org.hack4impact.portal

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Errors the services raise without an HTTP status, answered in words instead of a bare 500 (code review L1): a state
 * check that failed (approving twice) or a race on unique data (two chapters with one code) is a conflict, a bad
 * argument a bad request. Only the portal's own checks get that (`check`, `require` and `error` are inline, so the
 * exception's top frame is portal code); the same exceptions from a library or the framework are bugs, answered with
 * a 500 and logged with their stack trace (second review L1).
 */
@RestControllerAdvice
class ApiErrors {
	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(IllegalStateException::class)
	fun conflict(e: IllegalStateException): ProblemDetail =
		ours(e, HttpStatus.CONFLICT, "That can't be done now")

	@ExceptionHandler(IllegalArgumentException::class)
	fun badRequest(e: IllegalArgumentException): ProblemDetail =
		ours(e, HttpStatus.BAD_REQUEST, "That request isn't right")

	private fun ours(e: RuntimeException, status: HttpStatus, fallback: String): ProblemDetail {
		val where = e.stackTrace.firstOrNull()
		if (where == null || !where.className.startsWith("org.hack4impact.portal.")) {
			log.error("Unexpected error", e)
			return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Try again, and tell national if it keeps happening.")
		}
		log.info("{} at {}.{}:{}: {}", status.reasonPhrase, where.className.substringAfterLast('.'), where.methodName, where.lineNumber, e.message)
		return ProblemDetail.forStatusAndDetail(status, e.message ?: fallback)
	}

	@ExceptionHandler(DataIntegrityViolationException::class)
	fun duplicate(e: DataIntegrityViolationException): ProblemDetail {
		log.warn("Data conflict: {}", e.mostSpecificCause.message)
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Someone else just changed this, or it already exists. Reload and try again.")
	}
}
