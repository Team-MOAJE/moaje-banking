package com.moaje.banking.application.reconciliation

import com.moaje.banking.repository.transfer.BankingTransferRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class TransferReconciliationMetrics(
    private val bankingTransferRepository: BankingTransferRepository,
    private val properties: TransferReconciliationProperties,
    meterRegistry: MeterRegistry,
) {
    private val claimedCounter = Counter.builder("banking.reconciliation.transfer.claimed")
        .description("Number of transfer reconciliation leases claimed")
        .register(meterRegistry)
    private val resolvedCounter = Counter.builder("banking.reconciliation.transfer.resolved")
        .description("Number of transfer reconciliation items resolved")
        .register(meterRegistry)
    private val lookupFailedCounter = Counter.builder("banking.reconciliation.transfer.lookup.failed")
        .description("Number of transfer reconciliation lookup failures")
        .register(meterRegistry)
    private val retryExhaustedCounter = Counter.builder("banking.reconciliation.transfer.retry.exhausted.total")
        .description("Number of transfer reconciliation items moved to retry exhausted")
        .register(meterRegistry)
    private val reversedCounter = Counter.builder("banking.reconciliation.transfer.reversed")
        .description("Number of already reversed transfers discovered by reconciliation")
        .register(meterRegistry)

    init {
        /**
         * Gauge는 현재 DB에 남아 있는 운영 backlog를 보여준다.
         * transferId나 사용자 ID를 tag로 쓰면 cardinality가 폭발하므로, 전체 개수만 노출한다.
         */
        Gauge.builder("banking.reconciliation.transfer.pending", this) { it.pendingCount().toDouble() }
            .description("Current number of pending transfer reconciliation candidates")
            .register(meterRegistry)
        Gauge.builder("banking.reconciliation.transfer.retry.exhausted", this) { it.retryExhaustedCount().toDouble() }
            .description("Current number of transfer reconciliation items with exhausted retries")
            .register(meterRegistry)
        Gauge.builder("banking.reconciliation.transfer.locked", this) { it.lockedCount().toDouble() }
            .description("Current number of transfer reconciliation candidates leased by workers")
            .register(meterRegistry)
    }

    fun recordClaimed() {
        claimedCounter.increment()
    }

    fun recordResolved() {
        resolvedCounter.increment()
    }

    fun recordReversed() {
        reversedCounter.increment()
    }

    fun recordLookupFailed(retryExhausted: Boolean) {
        lookupFailedCounter.increment()
        if (retryExhausted) {
            retryExhaustedCounter.increment()
        }
    }

    private fun pendingCount(): Long {
        val now = LocalDateTime.now()
        val staleBefore = now.minus(Duration.ofMillis(properties.processingStaleAfterMs.coerceAtLeast(1)))
        return bankingTransferRepository.countTransferReconciliationPending(now, staleBefore)
    }

    private fun retryExhaustedCount(): Long {
        return bankingTransferRepository.countByReconciliationRetryExhaustedTrue()
    }

    private fun lockedCount(): Long {
        return bankingTransferRepository.countTransferReconciliationLocked(LocalDateTime.now())
    }
}
