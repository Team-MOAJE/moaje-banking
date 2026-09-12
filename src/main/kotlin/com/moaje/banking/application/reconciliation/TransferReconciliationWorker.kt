package com.moaje.banking.application.reconciliation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TransferReconciliationWorker(
    private val transferReconciliationService: TransferReconciliationService,
    private val properties: TransferReconciliationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${moaje.banking.reconciliation.transfer.fixed-delay-ms:60000}",
        initialDelayString = "\${moaje.banking.reconciliation.transfer.initial-delay-ms:30000}",
    )
    fun reconcile() {
        if (!properties.enabled) {
            return
        }

        val result = transferReconciliationService.reconcileBatch()
        if (result.scanned > 0) {
            log.info(
                "Transfer reconciliation finished. scanned={}, resolved={}, failed={}, stillUnknown={}",
                result.scanned,
                result.resolved,
                result.failed,
                result.stillUnknown,
            )
        }
    }
}
