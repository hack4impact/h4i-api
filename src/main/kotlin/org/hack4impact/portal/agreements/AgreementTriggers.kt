package org.hack4impact.portal.agreements

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Someone joined [projectId] (or it went live): send its agreement now rather than at the next pass. */
data class AgreementsRequested(val projectId: UUID)

@Component
class AgreementRequests(private val events: ApplicationEventPublisher) {
	@Transactional
	fun request(projectId: UUID) = events.publishEvent(AgreementsRequested(projectId))
}

/** Sends after the requesting transaction commits, in the background (through the outbox, like sync requests). */
@Component
class AgreementListener(private val agreements: AgreementService, @Value("\${portal.agreements.on-request:true}") private val enabled: Boolean) {
	/** Outside any transaction, so an envelope Documenso already sent is never forgotten by a rollback (second review H2). */
	@ApplicationModuleListener(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	fun on(event: AgreementsRequested) {
		if (enabled) agreements.sendProjectAgreements(event.projectId)
	}
}

/**
 * The regular pass: send what's waiting, re-read open agreements (in case a webhook was missed, or Documenso can't
 * reach this portal at all, like staging on a laptop), remind, tell leads, cancel agreements of people who left.
 * Every 5 minutes; `portal.agreements.schedule-enabled=false` turns it off (tests, the sandbox suite).
 */
@Configuration(proxyBeanMethods = false)
class AgreementSchedule(private val agreements: AgreementService, @Value("\${portal.agreements.schedule-enabled:true}") private val enabled: Boolean) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${portal.agreements.pass-every:PT5M}", initialDelayString = "PT1M")
	fun pass() {
		if (!enabled) return
		val report = agreements.pass()
		if (report != AgreementService.PassReport()) log.info("Agreements: {}", report)
	}
}
