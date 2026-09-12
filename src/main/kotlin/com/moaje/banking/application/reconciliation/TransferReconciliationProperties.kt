package com.moaje.banking.application.reconciliation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "moaje.banking.reconciliation.transfer")
class TransferReconciliationProperties {
    var enabled: Boolean = false
    var fixedDelayMs: Long = 60_000
    var initialDelayMs: Long = 30_000
    var batchSize: Int = 20
    var processingStaleAfterMs: Long = 120_000
    var processingStaleSafetyMarginMs: Long = 30_000
    var leaseDurationMs: Long = 30_000
    var maxAttempts: Int = 10
    var baseBackoffMs: Long = 5_000
    var maxBackoffMs: Long = 300_000
}
