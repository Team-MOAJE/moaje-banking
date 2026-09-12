package com.moaje.banking.application.outbox

import com.moaje.banking.domain.outbox.BankingOutboxStatus
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class BankingOutboxMaintenanceService(
    private val outboxEventRepository: BankingOutboxEventRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun resetRetryExhausted(eventId: String): Boolean {
        return transactionTemplate.execute {
            val event = outboxEventRepository.findByEventIdForUpdate(eventId)
                ?: return@execute false
            if (event.status != BankingOutboxStatus.RETRY_EXHAUSTED) {
                return@execute false
            }
            event.resetForManualRetry(Instant.now())
            true
        } ?: false
    }
}
